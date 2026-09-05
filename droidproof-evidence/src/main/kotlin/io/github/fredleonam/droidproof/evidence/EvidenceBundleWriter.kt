package io.github.fredleonam.droidproof.evidence

import io.github.fredleonam.droidproof.model.EvidenceBundleManifest
import io.github.fredleonam.droidproof.model.TimelineDocument
import io.github.fredleonam.droidproof.model.TimelineEvent
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.nio.charset.StandardCharsets
import java.nio.file.FileAlreadyExistsException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption

/** Canonical JSON configuration for the version 1 evidence bundle schema. */
@OptIn(ExperimentalSerializationApi::class)
val evidenceJson =
    Json {
        encodeDefaults = true
        explicitNulls = false
        prettyPrint = true
        prettyPrintIndent = "  "
    }

class EvidenceBundleValidationException(message: String) : IllegalArgumentException(message)

class EvidenceBundleWriter {
    /**
     * Writes a bundle to [destination]. Existing bundles are rejected unless [overwrite] is true.
     * Event order is timestamp ascending, then lexicographic stable event ID for equal timestamps.
     */
    fun write(
        destination: Path,
        manifest: EvidenceBundleManifest,
        events: List<TimelineEvent>,
        overwrite: Boolean = false,
    ): Path {
        validate(manifest, events)
        val target = safeDestination(destination)
        val manifestJson = evidenceJson.encodeToString(manifest) + "\n"
        val timelineJson = evidenceJson.encodeToString(TimelineDocument(canonicalEvents(events))) + "\n"

        if (Files.exists(target)) {
            if (!overwrite) throw FileAlreadyExistsException(target.toString())
            require(Files.isDirectory(target)) { "Bundle destination is not a directory: $target" }
            writeFiles(target, manifestJson, timelineJson)
            return target
        }

        Files.createDirectories(requireNotNull(target.parent))
        val staging = Files.createTempDirectory(target.parent, ".droidproof-")
        try {
            writeFiles(staging, manifestJson, timelineJson)
            moveDirectory(staging, target)
        } finally {
            if (Files.exists(staging)) Files.deleteIfExists(staging)
        }
        return target
    }

    fun validate(
        manifest: EvidenceBundleManifest,
        events: List<TimelineEvent>,
    ) {
        if (manifest.schemaVersion != CURRENT_SCHEMA_VERSION) {
            throw EvidenceBundleValidationException(
                "Unsupported schema version ${manifest.schemaVersion}; expected $CURRENT_SCHEMA_VERSION.",
            )
        }
        val duplicateIds = events.groupingBy { it.id }.eachCount().filterValues { it > 1 }.keys
        if (duplicateIds.isNotEmpty()) {
            throw EvidenceBundleValidationException("Timeline event IDs must be unique: $duplicateIds")
        }
    }

    private fun canonicalEvents(events: List<TimelineEvent>): List<TimelineEvent> =
        events
            .sortedWith(compareBy<TimelineEvent> { it.timestamp }.thenBy { it.id.value })
            .map { event -> event.copy(attributes = event.attributes.toSortedMap()) }

    private fun safeDestination(destination: Path): Path {
        require(destination.iterator().asSequence().none { it.toString() == ".." }) {
            "Bundle destination must not contain parent-directory traversal."
        }
        return destination.toAbsolutePath().normalize()
    }

    private fun writeFiles(
        directory: Path,
        manifestJson: String,
        timelineJson: String,
    ) {
        Files.writeString(directory.resolve(MANIFEST_FILE), manifestJson, StandardCharsets.UTF_8)
        Files.writeString(directory.resolve(TIMELINE_FILE), timelineJson, StandardCharsets.UTF_8)
    }

    private fun moveDirectory(
        staging: Path,
        target: Path,
    ) {
        try {
            Files.move(staging, target, StandardCopyOption.ATOMIC_MOVE)
        } catch (_: java.nio.file.AtomicMoveNotSupportedException) {
            Files.move(staging, target)
        }
    }

    private companion object {
        const val CURRENT_SCHEMA_VERSION = 1
        const val MANIFEST_FILE = "manifest.json"
        const val TIMELINE_FILE = "timeline.json"
    }
}
