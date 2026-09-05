package io.github.fredleonam.droidproof.evidence

import io.github.fredleonam.droidproof.model.BundleRelativePath
import io.github.fredleonam.droidproof.model.EvidenceBundleManifest
import io.github.fredleonam.droidproof.model.EvidenceFileDescriptor
import io.github.fredleonam.droidproof.model.EvidenceFileRole
import io.github.fredleonam.droidproof.model.Sha256
import io.github.fredleonam.droidproof.model.TimelineDocument
import io.github.fredleonam.droidproof.model.TimelineEvent
import io.github.fredleonam.droidproof.model.isValidMediaType
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.IOException
import java.nio.channels.Channels
import java.nio.charset.StandardCharsets
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.FileAlreadyExistsException
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.nio.file.attribute.BasicFileAttributes
import java.security.MessageDigest
import java.util.Locale

const val CURRENT_SCHEMA_VERSION = 2
const val MANIFEST_FILE = "manifest.json"
const val TIMELINE_FILE = "timeline.json"

/** Canonical JSON configuration shared by evidence-bundle readers and writers. */
@OptIn(ExperimentalSerializationApi::class)
val evidenceJson =
    Json {
        encodeDefaults = true
        explicitNulls = false
        prettyPrint = true
        prettyPrintIndent = "  "
        ignoreUnknownKeys = false
    }

class EvidenceBundleValidationException(message: String) : IllegalArgumentException(message)

data class EvidenceFileInput(
    val source: Path,
    val destination: BundleRelativePath,
    val mediaType: String,
    val role: EvidenceFileRole? = null,
) {
    init {
        require(isValidMediaType(mediaType)) { "Evidence media type must be a valid type/subtype." }
    }
}

data class EvidenceBundleRequest(
    val manifest: EvidenceBundleManifest,
    val events: List<TimelineEvent>,
    val evidenceFiles: List<EvidenceFileInput> = emptyList(),
)

internal interface BundleFileOperations {
    fun moveDirectory(
        source: Path,
        target: Path,
    )
}

internal object NioBundleFileOperations : BundleFileOperations {
    override fun moveDirectory(
        source: Path,
        target: Path,
    ) {
        try {
            Files.move(source, target, StandardCopyOption.ATOMIC_MOVE)
        } catch (_: AtomicMoveNotSupportedException) {
            Files.move(source, target)
        }
    }
}

class EvidenceBundleWriter internal constructor(
    private val fileOperations: BundleFileOperations,
) {
    constructor() : this(NioBundleFileOperations)

    /** Compatibility overload for bundles without evidence files. New writes require schema version 2. */
    fun write(
        destination: Path,
        manifest: EvidenceBundleManifest,
        events: List<TimelineEvent>,
        overwrite: Boolean = false,
    ): Path = write(EvidenceBundleRequest(manifest, events), destination, overwrite)

    /** Fully constructs a schema-v2 bundle beside [destination] before installing it. */
    fun write(
        request: EvidenceBundleRequest,
        destination: Path,
        overwrite: Boolean = false,
    ): Path {
        validate(request)
        val target = safeDestination(destination)
        val parent = requireNotNull(target.parent) { "Bundle destination must have a parent directory." }
        Files.createDirectories(parent)
        if (Files.exists(target, LinkOption.NOFOLLOW_LINKS) && !overwrite) {
            throw FileAlreadyExistsException(target.toString())
        }
        if (Files.exists(target, LinkOption.NOFOLLOW_LINKS) && !Files.isDirectory(target, LinkOption.NOFOLLOW_LINKS)) {
            throw EvidenceBundleValidationException("Bundle destination is not a directory: $target")
        }

        val staging = Files.createTempDirectory(parent, ".droidproof-staging-")
        try {
            val inventory = request.evidenceFiles.map { copyEvidenceFile(staging, it) }.sortedBy { it.path.value }
            val manifest = request.manifest.copy(evidenceFiles = inventory)
            writeJsonDocuments(staging, manifest, request.events)
            val verification = EvidenceBundleVerifier().verify(staging)
            if (!verification.isValid) {
                throw EvidenceBundleValidationException("Constructed bundle failed verification: ${verification.issues}")
            }
            install(staging, target, overwrite)
        } finally {
            deleteTreeIfExists(staging)
        }
        return target
    }

    fun validate(request: EvidenceBundleRequest) {
        if (request.manifest.schemaVersion != CURRENT_SCHEMA_VERSION) {
            throw EvidenceBundleValidationException(
                "Unsupported schema version ${request.manifest.schemaVersion}; expected $CURRENT_SCHEMA_VERSION.",
            )
        }
        if (request.manifest.evidenceFiles.isNotEmpty()) {
            throw EvidenceBundleValidationException("Evidence inventory is generated from evidence-file inputs and must be empty.")
        }
        val duplicateIds = request.events.groupingBy { it.id }.eachCount().filterValues { it > 1 }.keys
        if (duplicateIds.isNotEmpty()) {
            throw EvidenceBundleValidationException("Timeline event IDs must be unique: $duplicateIds")
        }

        val mediaTypesByExactPath = mutableMapOf<String, String>()
        val portablePaths = mutableSetOf<String>()
        request.evidenceFiles.forEach { input ->
            val path = input.destination.value
            val existingMediaType = mediaTypesByExactPath.putIfAbsent(path, input.mediaType)
            if (existingMediaType != null) {
                if (existingMediaType != input.mediaType) {
                    throw EvidenceBundleValidationException("Conflicting media types for evidence destination: $path")
                }
                throw EvidenceBundleValidationException("Duplicate evidence destination: $path")
            }
            if (!portablePaths.add(path.lowercase(Locale.ROOT))) {
                throw EvidenceBundleValidationException("Case-insensitive evidence path collision: $path")
            }
            validateSource(input.source)
        }

        val inputsByPath = request.evidenceFiles.associateBy { it.destination }
        request.events.flatMap { it.evidence }.forEach { reference ->
            val input =
                inputsByPath[reference.path]
                    ?: throw EvidenceBundleValidationException(
                        "Timeline evidence is not supplied as an evidence file: ${reference.path}",
                    )
            if (reference.mediaType != null && reference.mediaType != input.mediaType) {
                throw EvidenceBundleValidationException("Conflicting media types for ${reference.path}.")
            }
        }
    }

    /** Compatibility overload for validating requests without evidence-file inputs. */
    fun validate(
        manifest: EvidenceBundleManifest,
        events: List<TimelineEvent>,
    ) = validate(EvidenceBundleRequest(manifest, events))

    private fun validateSource(source: Path) {
        val attributes =
            try {
                Files.readAttributes(source, BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS)
            } catch (error: IOException) {
                throw EvidenceBundleValidationException("Evidence source cannot be read: $source (${error.message})")
            }
        if (attributes.isSymbolicLink || !attributes.isRegularFile) {
            throw EvidenceBundleValidationException("Evidence source must be a regular non-symbolic-link file: $source")
        }
    }

    private fun copyEvidenceFile(
        staging: Path,
        input: EvidenceFileInput,
    ): EvidenceFileDescriptor {
        val output = resolve(staging, input.destination)
        Files.createDirectories(requireNotNull(output.parent))
        val digest = MessageDigest.getInstance("SHA-256")
        var byteSize = 0L
        Files.newByteChannel(input.source, setOf(StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS)).use { channel ->
            val source = Channels.newInputStream(channel)
            Files.newOutputStream(output).use { destination ->
                val buffer = ByteArray(COPY_BUFFER_SIZE)
                while (true) {
                    val count = source.read(buffer)
                    if (count < 0) break
                    if (count > 0) {
                        destination.write(buffer, 0, count)
                        digest.update(buffer, 0, count)
                        byteSize += count
                    }
                }
            }
        }
        return EvidenceFileDescriptor(
            path = input.destination,
            sha256 = Sha256(digest.digest().joinToString("") { byte -> "%02x".format(byte) }),
            byteSize = byteSize,
            mediaType = input.mediaType,
            role = input.role,
        )
    }

    private fun writeJsonDocuments(
        directory: Path,
        manifest: EvidenceBundleManifest,
        events: List<TimelineEvent>,
    ) {
        Files.writeString(
            directory.resolve(MANIFEST_FILE),
            evidenceJson.encodeToString(manifest) + "\n",
            StandardCharsets.UTF_8,
        )
        Files.writeString(
            directory.resolve(TIMELINE_FILE),
            evidenceJson.encodeToString(TimelineDocument(canonicalEvents(events))) + "\n",
            StandardCharsets.UTF_8,
        )
    }

    private fun install(
        staging: Path,
        target: Path,
        overwrite: Boolean,
    ) {
        if (!Files.exists(target, LinkOption.NOFOLLOW_LINKS)) {
            fileOperations.moveDirectory(staging, target)
            return
        }
        if (!overwrite) throw FileAlreadyExistsException(target.toString())

        val backup = Files.createTempDirectory(requireNotNull(target.parent), ".droidproof-backup-")
        Files.delete(backup)
        fileOperations.moveDirectory(target, backup)
        var installed = false
        try {
            fileOperations.moveDirectory(staging, target)
            installed = true
        } catch (replacementError: Exception) {
            try {
                fileOperations.moveDirectory(backup, target)
            } catch (restoreError: Exception) {
                replacementError.addSuppressed(restoreError)
            }
            throw replacementError
        } finally {
            if (installed) deleteTreeIfExists(backup)
        }
    }

    private fun canonicalEvents(events: List<TimelineEvent>): List<TimelineEvent> =
        events
            .sortedWith(compareBy<TimelineEvent> { it.timestamp }.thenBy { it.id.value })
            .map { event ->
                event.copy(
                    attributes = event.attributes.toSortedMap(),
                    evidence = event.evidence.sortedBy { it.path.value },
                )
            }

    private fun safeDestination(destination: Path): Path {
        require(destination.iterator().asSequence().none { it.toString() == ".." }) {
            "Bundle destination must not contain parent-directory traversal."
        }
        return destination.toAbsolutePath().normalize()
    }

    private fun resolve(
        root: Path,
        relativePath: BundleRelativePath,
    ): Path = relativePath.value.split('/').fold(root) { current, segment -> current.resolve(segment) }

    private fun deleteTreeIfExists(root: Path) {
        if (!Files.exists(root, LinkOption.NOFOLLOW_LINKS)) return
        Files.walk(root).use { paths ->
            paths.sorted(Comparator.reverseOrder()).forEach(Files::deleteIfExists)
        }
    }

    private companion object {
        const val COPY_BUFFER_SIZE = 8 * 1024
    }
}
