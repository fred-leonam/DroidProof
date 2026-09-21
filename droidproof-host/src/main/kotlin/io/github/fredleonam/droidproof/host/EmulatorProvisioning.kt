package io.github.fredleonam.droidproof.host

import io.github.fredleonam.droidproof.device.CommandRequest
import io.github.fredleonam.droidproof.device.CommandRunner
import io.github.fredleonam.droidproof.device.ProcessCommandRunner
import io.github.fredleonam.droidproof.evidence.Sha256Calculator
import io.github.fredleonam.droidproof.model.Sha256
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.ByteArrayInputStream
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.nio.file.attribute.BasicFileAttributes
import java.util.concurrent.atomic.AtomicBoolean

/** Portable identity of the SDK inputs, deliberately excluding host paths. */
@Serializable
data class EmulatorProvisioningContractV1(
    val schemaVersion: Int,
    val systemImagePackage: String,
    val systemImageRevision: String,
    val apiLevel: Int,
    val abi: String,
    val emulatorRevision: String,
    val platformToolsRevision: String? = null,
    val commandLineToolsRevision: String,
    val deviceProfile: String,
    val buildFingerprint: String? = null,
) {
    init {
        require(schemaVersion == 1)
        require(PACKAGE.matches(systemImagePackage) && IMAGE_REVISION.matches(systemImageRevision))
        require(apiLevel in 1..999 && ABI.matches(abi) && REVISION.matches(emulatorRevision))
        require(platformToolsRevision == null || REVISION.matches(platformToolsRevision))
        require(REVISION.matches(commandLineToolsRevision) && PROFILE.matches(deviceProfile))
        require(buildFingerprint == null || buildFingerprint.length in 1..256 && buildFingerprint.all { it.code in 0x21..0x7e })
        require(systemImagePackage.contains(";android-$apiLevel;") && systemImagePackage.endsWith(";$abi"))
    }

    companion object {
        val PACKAGE = Regex("[A-Za-z0-9._-]+(;[A-Za-z0-9._-]+)+")
        val REVISION = Regex("[0-9]+(\\.[0-9]+){1,5}")

        // System-image source.properties commonly uses a single component (for example, "1").
        val IMAGE_REVISION = Regex("[0-9]+(\\.[0-9]+){0,5}")
        val ABI = Regex("[A-Za-z0-9._-]{1,64}")
        val PROFILE = Regex("[A-Za-z0-9._ -]{1,128}")
    }
}

data class AcceptedProvisioningContract(val contract: EmulatorProvisioningContractV1, val exactBytes: ByteArray, val sha256: Sha256)

object EmulatorProvisioningContractLoader {
    fun load(path: Path): AcceptedProvisioningContract {
        val attributes = Files.readAttributes(path, BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS)
        require(
            attributes.isRegularFile && !attributes.isSymbolicLink,
        ) { "Provisioning contract must be a regular non-symbolic-link file." }
        require(attributes.size() in 1..MAX_BYTES) { "Provisioning contract must contain 1 to $MAX_BYTES bytes." }
        val bytes = Files.newInputStream(path, LinkOption.NOFOLLOW_LINKS).use { it.readNBytes(MAX_BYTES.toInt() + 1) }
        require(bytes.size.toLong() in 1..MAX_BYTES) { "Provisioning contract exceeds accepted byte bounds." }
        val text =
            StandardCharsets.UTF_8.newDecoder().onMalformedInput(
                CodingErrorAction.REPORT,
            ).onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString()
        val version = json.parseToJsonElement(text).jsonObject.getValue("schemaVersion").jsonPrimitive.int
        require(version == 1) { "Unsupported provisioning-contract schema version: $version." }
        return AcceptedProvisioningContract(json.decodeFromString(text), bytes, Sha256Calculator.calculate(ByteArrayInputStream(bytes)))
    }

    private const val MAX_BYTES = 64L * 1024L
    private val json =
        Json {
            ignoreUnknownKeys = false
            isLenient = false
            allowSpecialFloatingPointValues = false
        }
}

data class EmulatorProvisioningConfiguration(
    val accepted: AcceptedProvisioningContract,
    val sdkRoot: Path,
    val stateRoot: Path,
    val avdManagerPath: Path,
    val emulatorPath: Path,
    val adbPath: Path,
    val port: Int,
    val timeoutMillis: Long = 120_000,
    val shutdownTimeoutMillis: Long = 30_000,
    /** Explicit executable used only by the android-cli backend. */
    val androidCliPath: Path? = null,
) {
    init {
        require(stateRoot.isAbsolute && sdkRoot.isAbsolute)
        require(port in 5554..5682 && port % 2 == 0)
        require(timeoutMillis in 1..3_600_000 && shutdownTimeoutMillis in 1..3_600_000)
    }
}

interface ProvisionedEmulator : ManagedEmulatorSession {
    val avdDirectory: Path
    val accepted: AcceptedProvisioningContract
}

interface EmulatorProvisioner {
    fun provision(configuration: EmulatorProvisioningConfiguration): ProvisionedEmulator
}

open class EmulatorProvisioningException(message: String, cause: Throwable? = null) : RuntimeException(message, cause)

/** Legacy Android SDK command-line backend. It never invokes a shell or changes SDK packages. */
class LegacySdkEmulatorProvisioner(
    private val runner: CommandRunner = ProcessCommandRunner(),
    private val lifecycle: EmulatorLifecycleManager = LegacyEmulatorLifecycleManager(),
) : EmulatorProvisioner {
    override fun provision(configuration: EmulatorProvisioningConfiguration): ProvisionedEmulator {
        val contract = configuration.accepted.contract
        verify(configuration.sdkRoot.resolve("emulator/source.properties"), contract.emulatorRevision, "emulator")
        contract.platformToolsRevision?.let {
            verify(
                configuration.sdkRoot.resolve("platform-tools/source.properties"),
                it,
                "platform-tools",
            )
        }
        verify(
            configuration.sdkRoot.resolve("cmdline-tools").resolve(contract.commandLineToolsRevision).resolve("source.properties"),
            contract.commandLineToolsRevision,
            "command-line-tools",
        )
        val image = configuration.sdkRoot.resolve(contract.systemImagePackage.replace(';', '/')).resolve("source.properties")
        verify(image, contract.systemImageRevision, "system image", contract.systemImagePackage)
        val root = configuration.stateRoot.toAbsolutePath().normalize()
        Files.createDirectories(root)
        val key = configuration.accepted.sha256.value.take(24)
        val avdName = "droidproof-$key"
        val avd = root.resolve(key).normalize()
        require(avd.parent == root) { "Owned AVD escapes provisioning root." }
        if (Files.exists(
                avd,
            )
        ) {
            throw EmulatorProvisioningException(
                "Refusing existing provisioning directory; ownership cannot be established for this operation.",
            )
        }
        Files.createDirectory(avd)
        val avdHome = avd.resolve("avd-home")
        Files.createDirectory(avdHome)
        val marker = avd.resolve(".droidproof-owned.json")
        Files.writeString(
            marker,
            "{\"schemaVersion\":1,\"contractSha256\":\"${configuration.accepted.sha256.value}\"," +
                "\"avdName\":\"$avdName\",\"ownedDirectory\":\"$key\"}",
            StandardOpenOption.CREATE_NEW,
        )
        try {
            val create =
                runner.execute(
                    CommandRequest(
                        listOf(
                            configuration.avdManagerPath.toString(), "create", "avd", "--name", avdName,
                            "--package", contract.systemImagePackage, "--device", contract.deviceProfile,
                            "--path", avd.toString(), "--force",
                        ),
                        configuration.timeoutMillis,
                        environment = mapOf("ANDROID_AVD_HOME" to avdHome.toString()),
                    ),
                )
            if (create.failure != null || create.exitCode != 0) {
                throw EmulatorProvisioningException(
                    "avdmanager could not create the owned AVD (missing licenses or SDK input are not accepted automatically).",
                )
            }
            val config = avd.resolve("config.ini")
            val content = Files.readString(config)
            val expectedImage = "image.sysdir.1=${contract.systemImagePackage.replace(';', '/')}"
            if (!content.contains(expectedImage) || !content.contains("abi.type=${contract.abi}")) {
                throw EmulatorProvisioningException(
                    "Created AVD configuration does not match requested system image or ABI.",
                )
            }
            val session =
                lifecycle.start(
                    EmulatorLifecycleConfiguration(
                        null,
                        avdName,
                        configuration.emulatorPath,
                        configuration.adbPath,
                        configuration.port,
                        configuration.timeoutMillis,
                        configuration.shutdownTimeoutMillis,
                        avd,
                        mapOf("ANDROID_AVD_HOME" to avdHome.toString()),
                    ),
                )
            try {
                verifyRuntime(session.serial, configuration, contract)
            } catch (error: Throwable) {
                try {
                    session.close()
                } catch (cleanup: Throwable) {
                    error.addSuppressed(cleanup)
                }
                throw error
            }
            return OwnedSession(session, avd, configuration.accepted, marker)
        } catch (error: Throwable) {
            try {
                deleteOwned(avd, marker, configuration.accepted)
            } catch (cleanup: Throwable) {
                error.addSuppressed(cleanup)
            }
            throw error
        }
    }

    private fun verifyRuntime(
        serial: String,
        configuration: EmulatorProvisioningConfiguration,
        contract: EmulatorProvisioningContractV1,
    ) {
        fun property(name: String): String {
            val r =
                runner.execute(
                    CommandRequest(
                        listOf(configuration.adbPath.toString(), "-s", serial, "shell", "getprop", name),
                        configuration.timeoutMillis,
                    ),
                )
            if (r.failure != null || r.exitCode != 0) {
                throw EmulatorProvisioningException(
                    "Could not verify provisioned runtime property $name.",
                )
            }
            return r.stdout.trim()
        }
        if (property("ro.build.version.sdk") != contract.apiLevel.toString()) {
            throw EmulatorProvisioningException(
                "Provisioned API level does not match contract.",
            )
        }
        if (property("ro.product.cpu.abi") != contract.abi) throw EmulatorProvisioningException("Provisioned ABI does not match contract.")
        contract.buildFingerprint?.let {
            if (property("ro.build.fingerprint") != it) {
                throw EmulatorProvisioningException(
                    "Provisioned build fingerprint does not match contract.",
                )
            }
        }
    }

    private fun verify(
        file: Path,
        expected: String,
        label: String,
        expectedPackage: String? = null,
    ) {
        if (!Files.isRegularFile(
                file,
                LinkOption.NOFOLLOW_LINKS,
            )
        ) {
            throw EmulatorProvisioningException("Requested $label metadata is missing.")
        }
        val lines = Files.readAllLines(file)
        val revision = lines.firstOrNull { it.startsWith("Pkg.Revision=") }?.substringAfter('=')
        if (revision != expected) throw EmulatorProvisioningException("Requested $label revision does not match installed SDK metadata.")
        val packageName = lines.firstOrNull { it.startsWith("Pkg.Path=") }?.substringAfter('=')
        if (expectedPackage != null && packageName != null && packageName != expectedPackage) {
            throw EmulatorProvisioningException("Requested system image package does not match installed SDK metadata.")
        }
    }

    private fun deleteOwned(
        directory: Path,
        marker: Path,
        accepted: AcceptedProvisioningContract,
    ) {
        val normalized = directory.toAbsolutePath().normalize()
        val parent = normalized.parent ?: return
        if (normalized.parent != parent || !Files.isDirectory(normalized, LinkOption.NOFOLLOW_LINKS)) return
        if (!Files.isRegularFile(marker, LinkOption.NOFOLLOW_LINKS) || Files.isSymbolicLink(marker)) return
        val attributes = Files.readAttributes(marker, BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS)
        if (attributes.size() !in 1..4096) return
        val expectedName = "droidproof-${accepted.sha256.value.take(24)}"
        val markerText = Files.newInputStream(marker, LinkOption.NOFOLLOW_LINKS).use { it.readNBytes(4097) }
        if (markerText.size > 4096) return
        val markerJson =
            runCatching {
                json.decodeFromString<OwnershipMarker>(markerText.toString(StandardCharsets.UTF_8))
            }.getOrNull() ?: return
        if (markerJson != OwnershipMarker(1, accepted.sha256.value, expectedName, normalized.fileName.toString())) return
        Files.walk(normalized).use { stream ->
            stream.sorted(Comparator.reverseOrder()).forEach { path ->
                if (!path.toAbsolutePath().normalize().startsWith(normalized)) return@forEach
                Files.deleteIfExists(path)
            }
        }
    }

    @Serializable
    private data class OwnershipMarker(val schemaVersion: Int, val contractSha256: String, val avdName: String, val ownedDirectory: String)

    private val json =
        Json {
            ignoreUnknownKeys = false
            isLenient = false
        }

    private inner class OwnedSession(
        private val delegate: ManagedEmulatorSession,
        override val avdDirectory: Path,
        override val accepted: AcceptedProvisioningContract,
        private val marker: Path,
    ) : ProvisionedEmulator {
        private val closed = AtomicBoolean(false)
        override val serial get() = delegate.serial

        override fun close() {
            if (!closed.compareAndSet(false, true)) return
            var failure: Throwable? = null
            try {
                delegate.close()
            } catch (e: Throwable) {
                failure = e
            }
            try {
                deleteOwned(avdDirectory, marker, accepted)
            } catch (e: Throwable) {
                if (failure != null) failure.addSuppressed(e) else throw e
            }
            if (failure != null) throw failure
        }
    }
}
