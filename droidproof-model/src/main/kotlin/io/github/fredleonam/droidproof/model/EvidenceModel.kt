package io.github.fredleonam.droidproof.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import java.text.Normalizer
import java.time.Instant
import java.util.Locale

/** A lower-case SHA-256 digest encoded as 64 hexadecimal characters. */
@Serializable
@JvmInline
value class Sha256(val value: String) {
    init {
        require(SHA_256.matches(value)) { "SHA-256 must be 64 lower-case hexadecimal characters." }
    }

    override fun toString(): String = value

    private companion object {
        val SHA_256 = Regex("[0-9a-f]{64}")
    }
}

@Serializable
@JvmInline
value class BundleId(val value: String) {
    init {
        require(SLUG.matches(value)) { "Bundle ID must be a lower-case hyphenated slug." }
    }

    override fun toString(): String = value
}

@Serializable
@JvmInline
value class ScenarioId(val value: String) {
    init {
        require(SLUG.matches(value)) { "Scenario ID must be a lower-case hyphenated slug." }
    }

    override fun toString(): String = value
}

@Serializable
@JvmInline
value class EventId(val value: String) {
    init {
        require(EVENT_ID.matches(value)) { "Event ID must be 1-128 URL-safe identifier characters." }
    }

    override fun toString(): String = value
}

@Serializable
@JvmInline
value class GitCommit(val value: String) {
    init {
        require(GIT_COMMIT.matches(value)) { "Git commit must be a 7-64 character lower-case hexadecimal revision." }
    }

    override fun toString(): String = value
}

@Serializable
@JvmInline
value class DroidProofVersion(val value: String) {
    init {
        require(VERSION.matches(value)) { "DroidProof version must be a semantic version." }
    }

    override fun toString(): String = value
}

/** An ISO-8601 UTC instant. The serialized form always ends in `Z`. */
@Serializable
@JvmInline
value class UtcTimestamp(val value: String) : Comparable<UtcTimestamp> {
    init {
        require(value.endsWith("Z")) { "Timestamp must use UTC and end with Z." }
        runCatching { Instant.parse(value) }.getOrElse { throw IllegalArgumentException("Invalid UTC timestamp.", it) }
    }

    fun asInstant(): Instant = Instant.parse(value)

    override fun compareTo(other: UtcTimestamp): Int = asInstant().compareTo(other.asInstant())

    override fun toString(): String = value
}

@Serializable
enum class AndroidArtifactType {
    APK,
    APP_BUNDLE,
}

@Serializable
data class AndroidArtifactIdentity(
    val type: AndroidArtifactType,
    val sha256: Sha256,
    val signingCertificateSha256: Sha256? = null,
)

@Serializable
data class ScenarioIdentity(
    val id: ScenarioId,
    val dataSha256: Sha256,
)

@Serializable
data class DeviceInformation(
    val fingerprint: String,
    val apiLevel: Int,
) {
    init {
        require(fingerprint.isNotBlank()) { "Device fingerprint must not be blank." }
        require(apiLevel > 0) { "Android API level must be positive." }
    }
}

@Serializable
enum class Orientation {
    PORTRAIT,
    LANDSCAPE,
}

@Serializable
data class AnimationConfiguration(
    val windowScale: Double,
    val transitionScale: Double,
    val animatorScale: Double,
) {
    init {
        require(listOf(windowScale, transitionScale, animatorScale).all { it.isFinite() && it >= 0.0 }) {
            "Animation scales must be finite and non-negative."
        }
    }
}

@Serializable
data class ControlledClock(
    val initialTime: UtcTimestamp,
    val isFrozen: Boolean,
)

@Serializable
data class EnvironmentContract(
    val device: DeviceInformation,
    val locale: String,
    val orientation: Orientation,
    val animations: AnimationConfiguration,
    val randomSeed: Long,
    val controlledClock: ControlledClock? = null,
) {
    init {
        require(LOCALE.matches(locale)) { "Locale must be a BCP 47 language tag such as en-US." }
    }
}

@Serializable
data class EvidenceBundleManifest(
    val schemaVersion: Int,
    val bundleId: BundleId,
    val createdAt: UtcTimestamp,
    val artifact: AndroidArtifactIdentity,
    val gitCommit: GitCommit? = null,
    val scenario: ScenarioIdentity,
    val environment: EnvironmentContract,
    val droidProofVersion: DroidProofVersion,
    val evidenceFiles: List<EvidenceFileDescriptor> = emptyList(),
) {
    init {
        require(schemaVersion >= 1) { "Schema version must be at least 1." }
    }
}

/** A portable, normalized path below an evidence-bundle root. */
@Serializable
@JvmInline
value class BundleRelativePath(val value: String) : Comparable<BundleRelativePath> {
    init {
        require(value == Normalizer.normalize(value, Normalizer.Form.NFC)) { "Bundle path must use Unicode NFC normalization." }
        require(isSafeRelativePath(value)) { "Bundle path must be a safe, portable, non-empty relative path." }
        require(value.lowercase(Locale.ROOT) !in RESERVED_BUNDLE_PATHS) { "Bundle path is reserved: $value" }
    }

    override fun compareTo(other: BundleRelativePath): Int = value.compareTo(other.value)

    override fun toString(): String = value
}

@Serializable
enum class EvidenceFileRole {
    @SerialName("screenshot")
    SCREENSHOT,

    @SerialName("semantics")
    SEMANTICS,

    @SerialName("logcat")
    LOGCAT,

    @SerialName("network")
    NETWORK,

    @SerialName("test-result")
    TEST_RESULT,

    @SerialName("attachment")
    ATTACHMENT,
}

@Serializable
data class EvidenceFileDescriptor(
    val path: BundleRelativePath,
    val sha256: Sha256,
    val byteSize: Long,
    val mediaType: String,
    val role: EvidenceFileRole? = null,
) {
    init {
        require(byteSize >= 0) { "Evidence byte size must be non-negative." }
        require(isValidMediaType(mediaType)) { "Evidence media type must be a valid type/subtype." }
    }
}

@Serializable
enum class EventSource {
    HOST,
    ANDROID_SYSTEM,
    APPLICATION,
    TEST,
    MOCK_SERVER,
}

/** A safe bundle-relative path to evidence captured by a later collector. */
@Serializable
data class EvidenceReference(
    val path: BundleRelativePath,
    val mediaType: String? = null,
) {
    init {
        require(mediaType == null || isValidMediaType(mediaType)) {
            "Evidence media type must be a valid type/subtype."
        }
    }
}

/**
 * Events are sorted by timestamp, then lexicographically by stable event ID when timestamps are equal.
 */
@Serializable
data class TimelineEvent(
    val id: EventId,
    val timestamp: UtcTimestamp,
    val source: EventSource,
    val type: String,
    val attributes: Map<String, String> = emptyMap(),
    val evidence: List<EvidenceReference> = emptyList(),
) {
    init {
        require(type.isNotBlank()) { "Timeline event type must not be blank." }
        require(attributes.keys.all { it.isNotBlank() }) { "Timeline attribute keys must not be blank." }
    }
}

@Serializable
data class TimelineDocument(val events: List<TimelineEvent>)

fun isSafeRelativePath(path: String): Boolean {
    if (path.isBlank() || path.startsWith('/') || path.startsWith('\\') || path.contains('\\')) return false
    if (WINDOWS_DRIVE_PATH.matches(path)) return false
    return path.split('/').all(::isPortablePathSegment)
}

fun isValidMediaType(value: String): Boolean = MEDIA_TYPE.matches(value)

private fun isPortablePathSegment(segment: String): Boolean {
    if (segment.isBlank() || segment == "." || segment == "..") return false
    if (segment.last() == '.' || segment.last() == ' ') return false
    if (segment.any { it.code < 32 || it in WINDOWS_INVALID_PATH_CHARACTERS }) return false
    val baseName = segment.substringBefore('.').uppercase(Locale.ROOT)
    return baseName !in WINDOWS_RESERVED_NAMES
}

private val SLUG = Regex("[a-z0-9]+(?:-[a-z0-9]+)*")
private val EVENT_ID = Regex("[A-Za-z0-9][A-Za-z0-9._-]{0,127}")
private val GIT_COMMIT = Regex("[0-9a-f]{7,64}")
private val VERSION = Regex("[0-9]+\\.[0-9]+\\.[0-9]+(?:-[0-9A-Za-z.-]+)?(?:\\+[0-9A-Za-z.-]+)?")
private val LOCALE = Regex("[a-zA-Z]{2,3}(?:-[a-zA-Z]{2})?(?:-[a-zA-Z0-9]{2,8})*")
private val MEDIA_TYPE = Regex("[A-Za-z0-9!#$&^_.+-]+/[A-Za-z0-9!#$&^_.+-]+")
private val WINDOWS_DRIVE_PATH = Regex("^[A-Za-z]:.*")
private val WINDOWS_INVALID_PATH_CHARACTERS = setOf('<', '>', ':', '\"', '|', '?', '*')
private val WINDOWS_RESERVED_NAMES =
    setOf("CON", "PRN", "AUX", "NUL") +
        (1..9).flatMap { listOf("COM$it", "LPT$it") }
private val RESERVED_BUNDLE_PATHS = setOf("manifest.json", "timeline.json")
