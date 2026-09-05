package io.github.fredleonam.droidproof.evidence

import io.github.fredleonam.droidproof.model.BundleRelativePath
import io.github.fredleonam.droidproof.model.EvidenceBundleManifest
import io.github.fredleonam.droidproof.model.TimelineDocument
import kotlinx.serialization.SerializationException
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.IOException
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.util.Locale

enum class VerificationSeverity {
    ERROR,
    WARNING,
}

enum class VerificationIssueCode {
    MISSING_MANIFEST,
    MISSING_TIMELINE,
    MALFORMED_JSON,
    UNSUPPORTED_SCHEMA,
    MISSING_REFERENCED_EVIDENCE_FILE,
    UNREGISTERED_TIMELINE_REFERENCE,
    DUPLICATE_INVENTORY_PATH,
    CASE_INSENSITIVE_PATH_COLLISION,
    UNSAFE_INVENTORY_PATH,
    INVENTORY_NOT_SORTED,
    FILE_SIZE_MISMATCH,
    SHA256_MISMATCH,
    MEDIA_TYPE_CONFLICT,
    UNEXPECTED_EVIDENCE_FILE,
    SYMBOLIC_LINK,
    NON_REGULAR_FILE,
    FILE_INTEGRITY_UNAVAILABLE,
}

data class VerificationIssue(
    val code: VerificationIssueCode,
    val severity: VerificationSeverity,
    val message: String,
    val path: String? = null,
)

data class EvidenceBundleVerificationResult(
    val schemaVersion: Int?,
    val issues: List<VerificationIssue>,
) {
    val errors: List<VerificationIssue> get() = issues.filter { it.severity == VerificationSeverity.ERROR }
    val warnings: List<VerificationIssue> get() = issues.filter { it.severity == VerificationSeverity.WARNING }
    val isValid: Boolean get() = errors.isEmpty()
}

class EvidenceBundleVerifier {
    fun verify(bundle: Path): EvidenceBundleVerificationResult {
        val root = bundle.toAbsolutePath().normalize()
        val issues = mutableListOf<VerificationIssue>()
        if (Files.isSymbolicLink(root)) {
            issues.error(VerificationIssueCode.SYMBOLIC_LINK, "Bundle root must not be a symbolic link.")
            return EvidenceBundleVerificationResult(null, issues)
        }
        val manifestText = readCoreFile(root, MANIFEST_FILE, VerificationIssueCode.MISSING_MANIFEST, issues)
        val timelineText = readCoreFile(root, TIMELINE_FILE, VerificationIssueCode.MISSING_TIMELINE, issues)
        if (manifestText == null || timelineText == null) return EvidenceBundleVerificationResult(null, issues)

        val manifestObject =
            parseObject(manifestText, MANIFEST_FILE, issues)
                ?: return EvidenceBundleVerificationResult(null, issues)
        val timelineObject =
            parseObject(timelineText, TIMELINE_FILE, issues)
                ?: return EvidenceBundleVerificationResult(manifestObject.schemaVersion(), issues)
        val schemaVersion = manifestObject.schemaVersion()
        if (schemaVersion !in setOf(1, CURRENT_SCHEMA_VERSION)) {
            issues.error(
                VerificationIssueCode.UNSUPPORTED_SCHEMA,
                "Unsupported evidence schema version: ${schemaVersion ?: "missing"}.",
                MANIFEST_FILE,
            )
            return EvidenceBundleVerificationResult(schemaVersion, issues)
        }

        var rawPaths = emptyList<String>()
        if (schemaVersion == CURRENT_SCHEMA_VERSION) {
            val rawInventory = manifestObject["evidenceFiles"] as? JsonArray
            if (rawInventory == null) {
                issues.error(VerificationIssueCode.MALFORMED_JSON, "Schema version 2 requires evidenceFiles.", MANIFEST_FILE)
                return EvidenceBundleVerificationResult(schemaVersion, issues)
            }
            rawPaths = validateRawInventoryPaths(rawInventory, issues)
            validateInventoryOrder(rawPaths, issues)
            if (issues.any { it.code == VerificationIssueCode.UNSAFE_INVENTORY_PATH }) {
                scanUnexpectedFiles(root, rawPaths.toSet(), issues)
                return EvidenceBundleVerificationResult(schemaVersion, issues)
            }
        }

        val manifest = decodeManifest(manifestText, issues) ?: return EvidenceBundleVerificationResult(schemaVersion, issues)
        val timeline =
            decodeTimeline(timelineObject, timelineText, issues)
                ?: return EvidenceBundleVerificationResult(schemaVersion, issues)
        if (schemaVersion == 1) {
            issues.warning(
                VerificationIssueCode.FILE_INTEGRITY_UNAVAILABLE,
                "Schema version 1 does not bind evidence files; file integrity was not verified.",
            )
            return EvidenceBundleVerificationResult(schemaVersion, issues)
        }

        val inventoryByPath = manifest.evidenceFiles.associateBy { it.path }
        timeline.events.flatMap { it.evidence }.forEach { reference ->
            val descriptor = inventoryByPath[reference.path]
            if (descriptor == null) {
                issues.error(
                    VerificationIssueCode.UNREGISTERED_TIMELINE_REFERENCE,
                    "Timeline references an unregistered evidence file.",
                    reference.path.value,
                )
            } else if (reference.mediaType != null && reference.mediaType != descriptor.mediaType) {
                issues.error(
                    VerificationIssueCode.MEDIA_TYPE_CONFLICT,
                    "Timeline and inventory media types differ.",
                    reference.path.value,
                )
            }
        }
        manifest.evidenceFiles.forEach { verifyEvidenceFile(root, it.path, it.byteSize, it.sha256.value, issues) }
        scanUnexpectedFiles(root, rawPaths.toSet(), issues)
        return EvidenceBundleVerificationResult(schemaVersion, issues)
    }

    private fun readCoreFile(
        root: Path,
        fileName: String,
        missingCode: VerificationIssueCode,
        issues: MutableList<VerificationIssue>,
    ): String? {
        val path = root.resolve(fileName)
        if (!Files.exists(path, LinkOption.NOFOLLOW_LINKS)) {
            issues.error(missingCode, "Required bundle file is missing.", fileName)
            return null
        }
        if (Files.isSymbolicLink(path)) {
            issues.error(VerificationIssueCode.SYMBOLIC_LINK, "Bundle files must not be symbolic links.", fileName)
            return null
        }
        if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) {
            issues.error(VerificationIssueCode.NON_REGULAR_FILE, "Bundle file is not a regular file.", fileName)
            return null
        }
        return try {
            Files.readString(path)
        } catch (error: IOException) {
            issues.error(VerificationIssueCode.NON_REGULAR_FILE, "Bundle file cannot be read: ${error.message}", fileName)
            null
        }
    }

    private fun parseObject(
        text: String,
        fileName: String,
        issues: MutableList<VerificationIssue>,
    ): JsonObject? =
        try {
            evidenceJson.parseToJsonElement(text).jsonObject
        } catch (error: Exception) {
            issues.error(VerificationIssueCode.MALFORMED_JSON, "Malformed JSON: ${error.message}", fileName)
            null
        }

    private fun decodeManifest(
        text: String,
        issues: MutableList<VerificationIssue>,
    ): EvidenceBundleManifest? =
        try {
            evidenceJson.decodeFromString(text)
        } catch (error: Exception) {
            issues.error(VerificationIssueCode.MALFORMED_JSON, "Invalid manifest: ${error.message}", MANIFEST_FILE)
            null
        }

    private fun decodeTimeline(
        parsed: JsonObject,
        text: String,
        issues: MutableList<VerificationIssue>,
    ): TimelineDocument? {
        if (parsed["events"] == null) {
            issues.error(VerificationIssueCode.MALFORMED_JSON, "Timeline must contain events.", TIMELINE_FILE)
            return null
        }
        return try {
            evidenceJson.decodeFromString(text)
        } catch (error: SerializationException) {
            issues.error(VerificationIssueCode.MALFORMED_JSON, "Invalid timeline: ${error.message}", TIMELINE_FILE)
            null
        } catch (error: IllegalArgumentException) {
            issues.error(VerificationIssueCode.MALFORMED_JSON, "Invalid timeline: ${error.message}", TIMELINE_FILE)
            null
        }
    }

    private fun validateRawInventoryPaths(
        inventory: JsonArray,
        issues: MutableList<VerificationIssue>,
    ): List<String> {
        val paths = mutableListOf<String>()
        val mediaTypesByPath = mutableMapOf<String, MutableSet<String>>()
        inventory.forEach { element ->
            val entry = element as? JsonObject
            val path = entry?.get("path")?.let { runCatching { it.jsonPrimitive.content }.getOrNull() }
            if (path == null) {
                issues.error(VerificationIssueCode.MALFORMED_JSON, "Inventory entry has no string path.", MANIFEST_FILE)
                return@forEach
            }
            paths += path
            entry["mediaType"]?.let { value ->
                runCatching { value.jsonPrimitive.content }.getOrNull()?.let { mediaType ->
                    mediaTypesByPath.getOrPut(path) { mutableSetOf() }.add(mediaType)
                }
            }
            if (runCatching { BundleRelativePath(path) }.isFailure) {
                issues.error(VerificationIssueCode.UNSAFE_INVENTORY_PATH, "Inventory path is unsafe.", path)
            }
        }
        paths.groupingBy { it }.eachCount().filterValues { it > 1 }.keys.forEach { path ->
            issues.error(VerificationIssueCode.DUPLICATE_INVENTORY_PATH, "Inventory path is duplicated.", path)
        }
        paths.groupBy { it.lowercase(Locale.ROOT) }.filterValues { values -> values.distinct().size > 1 }.values.forEach { values ->
            issues.error(
                VerificationIssueCode.CASE_INSENSITIVE_PATH_COLLISION,
                "Inventory paths collide on case-insensitive filesystems: ${values.distinct()}.",
            )
        }
        mediaTypesByPath.filterValues { it.size > 1 }.keys.forEach { path ->
            issues.error(VerificationIssueCode.MEDIA_TYPE_CONFLICT, "Inventory has conflicting media types.", path)
        }
        return paths
    }

    private fun validateInventoryOrder(
        paths: List<String>,
        issues: MutableList<VerificationIssue>,
    ) {
        if (paths != paths.sorted()) {
            issues.error(
                VerificationIssueCode.INVENTORY_NOT_SORTED,
                "Evidence inventory is not ordered lexicographically by path.",
                MANIFEST_FILE,
            )
        }
    }

    private fun verifyEvidenceFile(
        root: Path,
        relativePath: BundleRelativePath,
        expectedSize: Long,
        expectedSha256: String,
        issues: MutableList<VerificationIssue>,
    ) {
        val path = resolve(root, relativePath)
        val symbolicLink = firstSymbolicLink(root, relativePath)
        if (symbolicLink != null) {
            issues.error(VerificationIssueCode.SYMBOLIC_LINK, "Evidence path contains a symbolic link.", symbolicLink)
            return
        }
        if (!Files.exists(path, LinkOption.NOFOLLOW_LINKS)) {
            issues.error(
                VerificationIssueCode.MISSING_REFERENCED_EVIDENCE_FILE,
                "Registered evidence file is missing.",
                relativePath.value,
            )
            return
        }
        if (Files.isSymbolicLink(path)) {
            issues.error(VerificationIssueCode.SYMBOLIC_LINK, "Evidence file must not be a symbolic link.", relativePath.value)
            return
        }
        if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) {
            issues.error(VerificationIssueCode.NON_REGULAR_FILE, "Evidence path is not a regular file.", relativePath.value)
            return
        }
        val actualSize = Files.size(path)
        if (actualSize != expectedSize) {
            issues.error(
                VerificationIssueCode.FILE_SIZE_MISMATCH,
                "Expected $expectedSize bytes but found $actualSize.",
                relativePath.value,
            )
        }
        val actualSha256 = Sha256Calculator.calculate(path).value
        if (actualSha256 != expectedSha256) {
            issues.error(VerificationIssueCode.SHA256_MISMATCH, "Evidence SHA-256 does not match.", relativePath.value)
        }
    }

    private fun scanUnexpectedFiles(
        root: Path,
        registeredPaths: Set<String>,
        issues: MutableList<VerificationIssue>,
    ) {
        if (!Files.isDirectory(root, LinkOption.NOFOLLOW_LINKS)) return
        Files.walk(root).use { paths ->
            paths.filter { it != root && !Files.isDirectory(it, LinkOption.NOFOLLOW_LINKS) }.forEach { path ->
                val relative = root.relativize(path).joinToString("/")
                if (Files.isSymbolicLink(path) && relative !in registeredPaths) {
                    issues.error(VerificationIssueCode.SYMBOLIC_LINK, "Unexpected symbolic link in bundle.", relative)
                } else if (relative !in registeredPaths && relative !in CORE_FILES) {
                    issues.error(VerificationIssueCode.UNEXPECTED_EVIDENCE_FILE, "File is not registered in inventory.", relative)
                }
            }
        }
    }

    private fun resolve(
        root: Path,
        relativePath: BundleRelativePath,
    ): Path = relativePath.value.split('/').fold(root) { current, segment -> current.resolve(segment) }

    private fun firstSymbolicLink(
        root: Path,
        relativePath: BundleRelativePath,
    ): String? {
        var current = root
        relativePath.value.split('/').forEach { segment ->
            current = current.resolve(segment)
            if (Files.isSymbolicLink(current)) return root.relativize(current).joinToString("/")
        }
        return null
    }

    private fun JsonObject.schemaVersion(): Int? = this["schemaVersion"]?.jsonPrimitive?.intOrNull

    private fun MutableList<VerificationIssue>.error(
        code: VerificationIssueCode,
        message: String,
        path: String? = null,
    ) = add(VerificationIssue(code, VerificationSeverity.ERROR, message, path))

    private fun MutableList<VerificationIssue>.warning(
        code: VerificationIssueCode,
        message: String,
        path: String? = null,
    ) = add(VerificationIssue(code, VerificationSeverity.WARNING, message, path))

    private companion object {
        val CORE_FILES = setOf(MANIFEST_FILE, TIMELINE_FILE)
    }
}
