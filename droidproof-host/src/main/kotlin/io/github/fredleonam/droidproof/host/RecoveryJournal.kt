package io.github.fredleonam.droidproof.host

import io.github.fredleonam.droidproof.model.EmulatorCapabilityObservationV1
import io.github.fredleonam.droidproof.model.EmulatorEnvironmentState
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.nio.file.StandardCopyOption.REPLACE_EXISTING
import java.nio.file.StandardOpenOption
import java.security.MessageDigest

const val RECOVERY_JOURNAL_SCHEMA_VERSION = 1
private const val MAX_JOURNAL_BYTES = 32 * 1024

@Serializable
enum class RecoveryJournalPhase {
    PREPARED,
    MUTATION_STARTED,
    APPLIED_VERIFIED,
    RESTORATION_STARTED,
    RESTORED_VERIFIED,
    RECOVERY_REQUIRED,
}

@Serializable
data class EmulatorRecoveryJournalV1(
    val recoveryJournalSchemaVersion: Int = RECOVERY_JOURNAL_SCHEMA_VERSION,
    val transactionId: String,
    val deviceSerial: String,
    val initialCapability: EmulatorCapabilityObservationV1,
    val originalEnvironment: EmulatorEnvironmentState,
    val phase: RecoveryJournalPhase,
    val createdAt: String,
    val updatedAt: String,
    val detail: String,
) {
    init {
        require(recoveryJournalSchemaVersion == RECOVERY_JOURNAL_SCHEMA_VERSION) { "Unsupported recovery journal version." }
        require(SAFE_RECOVERY_ID.matches(transactionId)) { "Recovery transaction ID is invalid." }
        require(DEVICE_SERIAL.matches(deviceSerial)) { "Recovery journal device serial is invalid." }
        require(SAFE_TIMESTAMP.matches(createdAt) && SAFE_TIMESTAMP.matches(updatedAt)) { "Recovery journal timestamps are invalid." }
        require(
            detail.isNotBlank() && detail.length <= 256 && detail.all { it.code in 0x20..0x7e },
        ) { "Recovery journal detail is unsafe." }
    }

    val isResolved: Boolean get() = phase == RecoveryJournalPhase.RESTORED_VERIFIED

    fun transition(
        next: RecoveryJournalPhase,
        at: String,
        safeDetail: String,
    ): EmulatorRecoveryJournalV1 {
        require(SAFE_TIMESTAMP.matches(at)) { "Recovery journal timestamp is invalid." }
        require(safeDetail.isNotBlank() && safeDetail.length <= 256 && safeDetail.all { it.code in 0x20..0x7e })
        require(next in allowedNext(phase)) { "Invalid recovery journal transition: $phase -> $next" }
        return copy(phase = next, updatedAt = at, detail = safeDetail)
    }

    private fun allowedNext(from: RecoveryJournalPhase): Set<RecoveryJournalPhase> =
        when (from) {
            RecoveryJournalPhase.PREPARED ->
                setOf(
                    RecoveryJournalPhase.MUTATION_STARTED,
                    RecoveryJournalPhase.RESTORATION_STARTED,
                    RecoveryJournalPhase.RECOVERY_REQUIRED,
                )
            RecoveryJournalPhase.MUTATION_STARTED ->
                setOf(
                    RecoveryJournalPhase.APPLIED_VERIFIED,
                    RecoveryJournalPhase.RESTORATION_STARTED,
                    RecoveryJournalPhase.RECOVERY_REQUIRED,
                )
            RecoveryJournalPhase.APPLIED_VERIFIED -> setOf(RecoveryJournalPhase.RESTORATION_STARTED, RecoveryJournalPhase.RECOVERY_REQUIRED)
            RecoveryJournalPhase.RESTORATION_STARTED ->
                setOf(
                    RecoveryJournalPhase.RESTORED_VERIFIED,
                    RecoveryJournalPhase.RECOVERY_REQUIRED,
                )
            RecoveryJournalPhase.RECOVERY_REQUIRED ->
                setOf(
                    RecoveryJournalPhase.RESTORATION_STARTED,
                    RecoveryJournalPhase.RECOVERY_REQUIRED,
                )
            RecoveryJournalPhase.RESTORED_VERIFIED -> emptySet()
        }
}

internal val SAFE_RECOVERY_ID = Regex("[a-z0-9][a-z0-9-]{0,63}")
private val SAFE_TIMESTAMP = Regex("[0-9]{4}-[0-9]{2}-[0-9]{2}T[0-9:.+-]{1,40}Z")

interface EmulatorRecoveryJournalStore {
    /** null means no journal exists for the serial. Malformed journals are fail-closed exceptions. */
    fun load(serial: String): EmulatorRecoveryJournalV1?

    fun save(journal: EmulatorRecoveryJournalV1)

    fun pathFor(serial: String): Path
}

class RecoveryJournalException(message: String, cause: Throwable? = null) : RuntimeException(message, cause)

class FileEmulatorRecoveryJournalStore(private val root: Path) : EmulatorRecoveryJournalStore {
    override fun pathFor(serial: String): Path {
        require(DEVICE_SERIAL.matches(serial)) { "Invalid device serial." }
        val digest =
            MessageDigest.getInstance("SHA-256").digest(serial.toByteArray(Charsets.UTF_8))
                .joinToString("") { "%02x".format(it) }
        return root.resolve("$digest.recovery.json")
    }

    override fun load(serial: String): EmulatorRecoveryJournalV1? {
        val path = pathFor(serial)
        if (!Files.exists(path, NOFOLLOW_LINKS)) return null
        requireRegular(path)
        val bytes =
            try {
                Files.readAllBytes(path)
            } catch (
                e: Exception,
            ) {
                throw RecoveryJournalException("Recovery journal could not be read.", e)
            }
        if (bytes.size > MAX_JOURNAL_BYTES) throw RecoveryJournalException("Recovery journal exceeds its size limit.")
        val text =
            try {
                Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes)).toString()
            } catch (e: CharacterCodingException) {
                throw RecoveryJournalException("Recovery journal is not valid UTF-8.", e)
            }
        val journal =
            try {
                val fields = strictJson.parseToJsonElement(text).jsonObject.keys
                if (fields != JOURNAL_FIELDS) throw RecoveryJournalException("Recovery journal fields are invalid.")
                strictJson.decodeFromString<EmulatorRecoveryJournalV1>(text)
            } catch (
                e: Exception,
            ) {
                throw RecoveryJournalException("Recovery journal is malformed or unsupported.", e)
            }
        if (journal.deviceSerial != serial) throw RecoveryJournalException("Recovery journal serial does not match its location.")
        return journal
    }

    override fun save(journal: EmulatorRecoveryJournalV1) {
        val path = pathFor(journal.deviceSerial)
        try {
            Files.createDirectories(root)
            if (Files.isSymbolicLink(root) || !Files.isDirectory(root, NOFOLLOW_LINKS)) {
                throw RecoveryJournalException(
                    "Recovery journal root is unsafe.",
                )
            }
            if (Files.exists(path, NOFOLLOW_LINKS)) requireRegular(path)
            val temporary = Files.createTempFile(root, ".recovery-", ".tmp")
            try {
                val bytes = (strictJson.encodeToString(journal) + "\n").toByteArray(Charsets.UTF_8)
                FileChannel.open(temporary, StandardOpenOption.WRITE).use {
                        channel ->
                    channel.write(ByteBuffer.wrap(bytes))
                    channel.force(true)
                }
                try {
                    Files.move(temporary, path, ATOMIC_MOVE, REPLACE_EXISTING)
                } catch (
                    _: AtomicMoveNotSupportedException,
                ) {
                    Files.move(temporary, path, REPLACE_EXISTING)
                }
                runCatching { FileChannel.open(root, StandardOpenOption.READ).use { it.force(true) } }
            } finally {
                Files.deleteIfExists(temporary)
            }
        } catch (
            e: RecoveryJournalException,
        ) {
            throw e
        } catch (e: Exception) {
            throw RecoveryJournalException("Recovery journal could not be persisted.", e)
        }
    }

    private fun requireRegular(path: Path) {
        if (Files.isSymbolicLink(path) || !Files.isRegularFile(path, NOFOLLOW_LINKS)) {
            throw RecoveryJournalException("Recovery journal path is not a regular file.")
        }
    }

    private companion object {
        val strictJson =
            Json {
                encodeDefaults = true
                ignoreUnknownKeys = false
                isLenient = false
            }
        val JOURNAL_FIELDS =
            setOf(
                "recoveryJournalSchemaVersion",
                "transactionId",
                "deviceSerial",
                "initialCapability",
                "originalEnvironment",
                "phase",
                "createdAt",
                "updatedAt",
                "detail",
            )
    }
}
