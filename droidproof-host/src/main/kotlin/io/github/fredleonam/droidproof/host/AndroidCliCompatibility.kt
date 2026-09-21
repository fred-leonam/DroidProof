package io.github.fredleonam.droidproof.host

import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption

@Serializable
internal enum class AndroidCliCapabilityStatus { SUPPORTED, INCOMPATIBLE, UNVERIFIED }

@Serializable
internal enum class AndroidCliCompatibilityOutcome { COMPATIBLE, INCOMPATIBLE, UNVERIFIED }

@Serializable
internal enum class AndroidCliHostOs { MACOS, LINUX, WINDOWS, OTHER }

@Serializable
internal enum class AndroidCliIssueCode {
    EXECUTABLE_MISSING,
    EXECUTABLE_NOT_REGULAR,
    EXECUTABLE_NOT_EXECUTABLE,
    SDK_ROOT_MISSING,
    SDK_ROOT_NOT_DIRECTORY,
    WINDOWS_EMULATOR_MANAGEMENT_DISABLED,
    GLOBAL_HELP_UNAVAILABLE,
    SDK_SELECTION_NOT_PROVEN,
    VERSION_DISCOVERY_UNAVAILABLE,
    VERSION_OUTPUT_AMBIGUOUS,
    EMULATOR_HELP_UNAVAILABLE,
    EMULATOR_CREATE_UNAVAILABLE,
    EMULATOR_LIST_UNAVAILABLE,
    EMULATOR_START_UNAVAILABLE,
    EMULATOR_STOP_UNAVAILABLE,
    EMULATOR_REMOVE_UNAVAILABLE,
    EXACT_IMAGE_PACKAGE_REVISION_NOT_PROVEN,
    ISOLATED_OWNED_STATE_NOT_PROVEN,
    CLEAN_STATE_START_NOT_PROVEN,
    DETERMINISTIC_SERIAL_PORT_NOT_PROVEN,
    BOUNDED_STOP_NOT_PROVEN,
    SAFE_OWNED_REMOVAL_NOT_PROVEN,
}

@Serializable
internal data class AndroidCliCompatibilityIssue(
    val code: AndroidCliIssueCode,
    val status: AndroidCliCapabilityStatus,
    val explanation: String,
) {
    init {
        require(explanation.isNotBlank() && explanation.length <= 256 && explanation.all { it.code in 0x20..0x7e })
    }
}

@Serializable
internal data class AndroidCliCommandAvailability(
    val create: AndroidCliCapabilityStatus,
    val list: AndroidCliCapabilityStatus,
    val start: AndroidCliCapabilityStatus,
    val stop: AndroidCliCapabilityStatus,
    val remove: AndroidCliCapabilityStatus,
)

@Serializable
internal data class AndroidCliRequiredGuarantees(
    val exactImagePackageRevision: AndroidCliCapabilityStatus,
    val isolatedOwnedState: AndroidCliCapabilityStatus,
    val cleanStateStart: AndroidCliCapabilityStatus,
    val deterministicSerialPortAssociation: AndroidCliCapabilityStatus,
    val boundedStop: AndroidCliCapabilityStatus,
    val safeOwnedRemoval: AndroidCliCapabilityStatus,
)

@Serializable
internal data class AndroidCliCompatibilityReport(
    val schemaVersion: Int,
    val versionText: String?,
    val normalizedVersion: String?,
    val hostOs: AndroidCliHostOs,
    val hostOsSupport: AndroidCliCapabilityStatus,
    val sdkSelection: AndroidCliCapabilityStatus,
    val commands: AndroidCliCommandAvailability,
    val guarantees: AndroidCliRequiredGuarantees,
    val outcome: AndroidCliCompatibilityOutcome,
    val issues: List<AndroidCliCompatibilityIssue>,
) {
    init {
        require(schemaVersion == 1)
        require(
            versionText == null ||
                versionText.isNotBlank() &&
                versionText.length <= 256 &&
                versionText.all { it == '\n' || it == '\r' || it == '\t' || it.code in 0x20..0x7e },
        )
        require(normalizedVersion == null || Regex("[0-9]+(\\.[0-9]+){1,5}").matches(normalizedVersion))
        require(issues.map { it.code }.distinct().size == issues.size)
        require(issues == issues.sortedBy { it.code.name })
    }
}

internal object AndroidCliCompatibilityReportCodec {
    private val json =
        Json {
            prettyPrint = true
            encodeDefaults = true
            ignoreUnknownKeys = false
            isLenient = false
            allowSpecialFloatingPointValues = false
        }

    fun encode(report: AndroidCliCompatibilityReport): String = json.encodeToString(report) + "\n"

    fun decode(value: String): AndroidCliCompatibilityReport = json.decodeFromString(value)

    fun write(
        report: AndroidCliCompatibilityReport,
        destination: Path,
    ) {
        destination.parent?.let { Files.createDirectories(it) }
        Files.writeString(
            destination,
            encode(report),
            StandardOpenOption.CREATE,
            StandardOpenOption.TRUNCATE_EXISTING,
            StandardOpenOption.WRITE,
        )
    }
}
