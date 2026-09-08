package io.github.fredleonam.droidproof.device

import io.github.fredleonam.droidproof.model.BundleRelativePath
import io.github.fredleonam.droidproof.model.EvidenceFileRole
import kotlinx.serialization.Serializable
import java.nio.file.Path

data class CaptureLimits(
    val commandTimeoutMillis: Long = 15000,
    val textLimitBytes: Long = 65536,
    val screenshotLimitBytes: Long = 33554432,
    val logcatLimitBytes: Long = 1048576,
) {
    init {
        require(commandTimeoutMillis in 1..3600000)
        require(listOf(textLimitBytes, screenshotLimitBytes, logcatLimitBytes).all { it in 1..Int.MAX_VALUE.toLong() })
    }
}

data class CaptureRequest(
    val outputRoot: Path,
    val serial: String? = null,
    val includeLogcat: Boolean = false,
    val pid: Int? = null,
    val limits: CaptureLimits = CaptureLimits(),
) {
    init {
        require(serial == null || validSerial(serial)) { "Invalid device serial." }
        require(if (includeLogcat) pid != null && pid > 0 else pid == null) {
            "Logcat requires explicit opt-in and a positive PID; omit PID when disabled."
        }
    }
}

@Serializable
enum class CollectionIssueCode {
    NO_DEVICES,
    AMBIGUOUS_DEVICE,
    UNAUTHORIZED,
    OFFLINE,
    UNAVAILABLE,
    DISCONNECTED,
    LAUNCH,
    NONZERO_EXIT,
    TIMEOUT,
    INTERRUPTED,
    OUTPUT_LIMIT,
    IO,
    METADATA_UNAVAILABLE,
    INVALID_PNG,
    EMPTY,
    UNSUPPORTED,
}

@Serializable
data class CollectionIssue(val code: CollectionIssueCode, val component: String, val message: String)

@Serializable
enum class CollectionOutcome { NOT_REQUESTED, NOT_COLLECTED, SUCCESS, FAILED, EMPTY, TRUNCATED, UNSUPPORTED }

@Serializable
enum class CaptureStatus { SUCCESS, PARTIAL, FAILED }

@Serializable
data class ObservedField(val value: String? = null, val reason: String? = null)

data class CollectedFile(val source: Path, val destination: BundleRelativePath, val mediaType: String, val role: EvidenceFileRole)

@Serializable
data class CaptureFile(val path: BundleRelativePath, val mediaType: String, val role: EvidenceFileRole)

@Serializable
data class CaptureDocument(
    val captureSchemaVersion: Int = 1,
    val captureId: String,
    val hostStartedAt: String,
    val hostEndedAt: String,
    val device: DeviceIdentity?,
    val metadata: Map<String, ObservedField>,
    val status: CaptureStatus,
    val screenshot: CollectionOutcome,
    val logcat: CollectionOutcome,
    val requestedPid: Int?,
    val files: List<CaptureFile>,
    val issues: List<CollectionIssue>,
    val limitations: List<String> =
        listOf(
            "Read-only display collection; no application execution or scenario verdict is established.",
            "Host timestamps are collection observations, not application event times or a cross-process causal clock.",
            "The display may be blank or protected and may belong to another application; secure windows are not bypassed.",
            "PID reuse, process restarts and historical log retention limit log attribution; logs may contain sensitive data.",
            "Observed metadata does not establish controlled environment guarantees.",
        ),
)

data class CaptureResult(val directory: Path, val document: CaptureDocument, val files: List<CollectedFile>)
