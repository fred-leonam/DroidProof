package io.github.fredleonam.droidproof.host

import io.github.fredleonam.droidproof.device.CommandRunner
import io.github.fredleonam.droidproof.device.ProcessCommandRunner
import java.nio.file.Files
import java.nio.file.Path

internal data class AndroidCliProbeConfiguration(
    val executable: Path,
    val sdkRoot: Path,
    val timeoutMillis: Long = 15_000,
) {
    init {
        require(timeoutMillis in 1..3_600_000)
    }
}

internal class AndroidCliCompatibilityProbe(
    private val runner: CommandRunner = ProcessCommandRunner(),
    private val osName: () -> String = { System.getProperty("os.name").orEmpty() },
) {
    fun inspect(configuration: AndroidCliProbeConfiguration): AndroidCliCompatibilityReport {
        val executable = configuration.executable.toAbsolutePath().normalize()
        val sdkRoot = configuration.sdkRoot.toAbsolutePath().normalize()
        val hostOs = hostOs()
        val issues = linkedMapOf<AndroidCliIssueCode, AndroidCliCompatibilityIssue>()
        val hostStatus =
            if (hostOs == AndroidCliHostOs.WINDOWS) {
                issues.add(
                    AndroidCliIssueCode.WINDOWS_EMULATOR_MANAGEMENT_DISABLED,
                    AndroidCliCapabilityStatus.INCOMPATIBLE,
                    "Android CLI emulator management is documented as disabled on Windows.",
                )
                AndroidCliCapabilityStatus.INCOMPATIBLE
            } else {
                AndroidCliCapabilityStatus.UNVERIFIED
            }
        executableIssue(executable)?.let {
            issues[it.code] = it
            return incompleteReport(hostOs, hostStatus, issues)
        }
        sdkIssue(sdkRoot)?.let {
            issues[it.code] = it
            return incompleteReport(hostOs, hostStatus, issues)
        }

        val surface = AndroidCliSurfaceDiscovery(runner).discover(executable, sdkRoot, configuration.timeoutMillis)
        addSurfaceIssues(surface, issues)
        val versionText =
            surface.version.output.trim().takeIf {
                surface.version.isSupported &&
                    it.length <= MAX_VERSION_TEXT_LENGTH &&
                    it.all { character ->
                        character == '\n' || character == '\r' || character == '\t' || character.code in 0x20..0x7e
                    }
            }
        if (surface.version.isSupported && versionText == null) {
            issues.add(
                AndroidCliIssueCode.VERSION_OUTPUT_AMBIGUOUS,
                AndroidCliCapabilityStatus.UNVERIFIED,
                "Android CLI version output did not meet the accepted text format and bound.",
            )
        }
        val commands = commandAvailability(surface, issues)
        val sdkSelection =
            if (surface.version.isSupported && surface.listExecution.isSupported) {
                AndroidCliCapabilityStatus.SUPPORTED
            } else {
                val status = aggregate(surface.version.status, surface.listExecution.status)
                issues.add(
                    AndroidCliIssueCode.SDK_SELECTION_NOT_PROVEN,
                    status,
                    "The configured SDK was not accepted by both version and emulator-list discovery.",
                )
                status
            }
        val resolvedHostStatus =
            when {
                hostStatus == AndroidCliCapabilityStatus.INCOMPATIBLE -> hostStatus
                surface.listExecution.isSupported -> AndroidCliCapabilityStatus.SUPPORTED
                else -> AndroidCliCapabilityStatus.UNVERIFIED
            }
        val guarantees = requiredGuarantees(commands, issues)
        val sortedIssues = issues.values.sortedBy { it.code.name }
        return AndroidCliCompatibilityReport(
            schemaVersion = 1,
            versionText = versionText,
            normalizedVersion = versionText?.takeIf(VERSION::matches),
            hostOs = hostOs,
            hostOsSupport = resolvedHostStatus,
            sdkSelection = sdkSelection,
            commands = commands,
            guarantees = guarantees,
            outcome = outcome(resolvedHostStatus, sdkSelection, commands, guarantees, sortedIssues),
            issues = sortedIssues,
        )
    }

    private fun executableIssue(path: Path): AndroidCliCompatibilityIssue? =
        when {
            !Files.exists(path) ->
                issue(AndroidCliIssueCode.EXECUTABLE_MISSING, "The configured Android CLI executable does not exist.")
            !Files.isRegularFile(path) ->
                issue(AndroidCliIssueCode.EXECUTABLE_NOT_REGULAR, "The configured Android CLI executable is not a regular file.")
            !Files.isExecutable(path) ->
                issue(AndroidCliIssueCode.EXECUTABLE_NOT_EXECUTABLE, "The configured Android CLI file is not executable.")
            else -> null
        }

    private fun sdkIssue(path: Path): AndroidCliCompatibilityIssue? =
        when {
            !Files.exists(path) ->
                issue(AndroidCliIssueCode.SDK_ROOT_MISSING, "The configured Android SDK root does not exist.")
            !Files.isDirectory(path) ->
                issue(AndroidCliIssueCode.SDK_ROOT_NOT_DIRECTORY, "The configured Android SDK root is not a directory.")
            else -> null
        }

    private fun addSurfaceIssues(
        surface: AndroidCliDiscoveredSurface,
        issues: MutableMap<AndroidCliIssueCode, AndroidCliCompatibilityIssue>,
    ) {
        if (!surface.globalHelp.isSupported) {
            issues.add(
                AndroidCliIssueCode.GLOBAL_HELP_UNAVAILABLE,
                surface.globalHelp.status,
                "Global Android CLI help was unavailable (${surface.globalHelp.reason.label}).",
            )
        }
        if (!surface.version.isSupported) {
            issues.add(
                AndroidCliIssueCode.VERSION_DISCOVERY_UNAVAILABLE,
                surface.version.status,
                "Android CLI version discovery was unavailable (${surface.version.reason.label}).",
            )
        }
        if (!surface.emulatorHelp.isSupported) {
            issues.add(
                AndroidCliIssueCode.EMULATOR_HELP_UNAVAILABLE,
                surface.emulatorHelp.status,
                "Android CLI emulator help was unavailable (${surface.emulatorHelp.reason.label}).",
            )
        }
    }

    private fun commandAvailability(
        surface: AndroidCliDiscoveredSurface,
        issues: MutableMap<AndroidCliIssueCode, AndroidCliCompatibilityIssue>,
    ): AndroidCliCommandAvailability {
        fun help(command: String): AndroidCliCapabilityStatus {
            val observation = requireNotNull(surface.commandHelp[command])
            if (observation.isSupported && observation.output.hasCommandPath(command)) {
                return AndroidCliCapabilityStatus.SUPPORTED
            }
            val status =
                observation.status.takeUnless { observation.isSupported }
                    ?: AndroidCliCapabilityStatus.UNVERIFIED
            issues.add(COMMAND_ISSUES.getValue(command), status, "The android emulator $command surface was not demonstrated.")
            return status
        }
        val listHelp = help("list")
        val listStatus =
            if (listHelp == AndroidCliCapabilityStatus.SUPPORTED && surface.listExecution.isSupported) {
                AndroidCliCapabilityStatus.SUPPORTED
            } else {
                aggregate(listHelp, surface.listExecution.status).also {
                    issues.add(
                        AndroidCliIssueCode.EMULATOR_LIST_UNAVAILABLE,
                        it,
                        "The android emulator list surface was not demonstrated by help and read-only invocation.",
                    )
                }
            }
        return AndroidCliCommandAvailability(help("create"), listStatus, help("start"), help("stop"), help("remove"))
    }

    private fun requiredGuarantees(
        commands: AndroidCliCommandAvailability,
        issues: MutableMap<AndroidCliIssueCode, AndroidCliCompatibilityIssue>,
    ): AndroidCliRequiredGuarantees {
        fun unresolved(
            code: AndroidCliIssueCode,
            explanation: String,
            vararg dependencies: AndroidCliCapabilityStatus,
        ): AndroidCliCapabilityStatus = aggregate(*dependencies).also { issues.add(code, it, explanation) }
        return AndroidCliRequiredGuarantees(
            unresolved(
                AndroidCliIssueCode.EXACT_IMAGE_PACKAGE_REVISION_NOT_PROVEN,
                "Exact system-image package and revision selection was not demonstrated.",
                commands.create,
            ),
            unresolved(
                AndroidCliIssueCode.ISOLATED_OWNED_STATE_NOT_PROVEN,
                "An isolated DroidProof-owned AVD and state directory was not demonstrated.",
                commands.create,
            ),
            unresolved(
                AndroidCliIssueCode.CLEAN_STATE_START_NOT_PROVEN,
                "An explicit clean-state start was not demonstrated.",
                commands.create,
                commands.start,
            ),
            unresolved(
                AndroidCliIssueCode.DETERMINISTIC_SERIAL_PORT_NOT_PROVEN,
                "An explicit port and unambiguous created-device serial association were not demonstrated.",
                commands.list,
                commands.start,
            ),
            unresolved(
                AndroidCliIssueCode.BOUNDED_STOP_NOT_PROVEN,
                "Successful bounded shutdown of the exact owned emulator was not demonstrated.",
                commands.stop,
            ),
            unresolved(
                AndroidCliIssueCode.SAFE_OWNED_REMOVAL_NOT_PROVEN,
                "Ownership-checked removal limited to DroidProof state was not demonstrated.",
                commands.remove,
            ),
        )
    }

    private fun incompleteReport(
        hostOs: AndroidCliHostOs,
        hostStatus: AndroidCliCapabilityStatus,
        issues: MutableMap<AndroidCliIssueCode, AndroidCliCompatibilityIssue>,
    ): AndroidCliCompatibilityReport {
        val unknown = AndroidCliCapabilityStatus.UNVERIFIED
        val commands = AndroidCliCommandAvailability(unknown, unknown, unknown, unknown, unknown)
        val guarantees = requiredGuarantees(commands, issues)
        val sortedIssues = issues.values.sortedBy { it.code.name }
        return AndroidCliCompatibilityReport(
            1,
            null,
            null,
            hostOs,
            hostStatus,
            unknown,
            commands,
            guarantees,
            outcome(hostStatus, unknown, commands, guarantees, sortedIssues),
            sortedIssues,
        )
    }

    private fun outcome(
        hostStatus: AndroidCliCapabilityStatus,
        sdkSelection: AndroidCliCapabilityStatus,
        commands: AndroidCliCommandAvailability,
        guarantees: AndroidCliRequiredGuarantees,
        issues: List<AndroidCliCompatibilityIssue>,
    ): AndroidCliCompatibilityOutcome {
        val statuses =
            listOf(
                hostStatus,
                sdkSelection,
                commands.create,
                commands.list,
                commands.start,
                commands.stop,
                commands.remove,
                guarantees.exactImagePackageRevision,
                guarantees.isolatedOwnedState,
                guarantees.cleanStateStart,
                guarantees.deterministicSerialPortAssociation,
                guarantees.boundedStop,
                guarantees.safeOwnedRemoval,
            ) + issues.map { it.status }
        return when {
            statuses.any { it == AndroidCliCapabilityStatus.INCOMPATIBLE } -> AndroidCliCompatibilityOutcome.INCOMPATIBLE
            statuses.any { it == AndroidCliCapabilityStatus.UNVERIFIED } -> AndroidCliCompatibilityOutcome.UNVERIFIED
            else -> AndroidCliCompatibilityOutcome.COMPATIBLE
        }
    }

    private fun aggregate(vararg statuses: AndroidCliCapabilityStatus) =
        if (statuses.any { it == AndroidCliCapabilityStatus.INCOMPATIBLE }) {
            AndroidCliCapabilityStatus.INCOMPATIBLE
        } else {
            AndroidCliCapabilityStatus.UNVERIFIED
        }

    private fun hostOs(): AndroidCliHostOs {
        val name = osName().lowercase()
        return when {
            "win" in name -> AndroidCliHostOs.WINDOWS
            "mac" in name || "darwin" in name -> AndroidCliHostOs.MACOS
            "linux" in name -> AndroidCliHostOs.LINUX
            else -> AndroidCliHostOs.OTHER
        }
    }

    private fun String.hasCommandPath(command: String) =
        Regex("(?m)^.*\\bandroid\\s+emulator\\s+${Regex.escape(command)}(?:\\s|$).*$").containsMatchIn(this)

    private fun issue(
        code: AndroidCliIssueCode,
        explanation: String,
    ) = AndroidCliCompatibilityIssue(code, AndroidCliCapabilityStatus.INCOMPATIBLE, explanation)

    private fun MutableMap<AndroidCliIssueCode, AndroidCliCompatibilityIssue>.add(
        code: AndroidCliIssueCode,
        status: AndroidCliCapabilityStatus,
        explanation: String,
    ) {
        this[code] = AndroidCliCompatibilityIssue(code, status, explanation)
    }

    private companion object {
        const val MAX_VERSION_TEXT_LENGTH = 256
        val VERSION = Regex("[0-9]+(\\.[0-9]+){1,5}")
        val COMMAND_ISSUES =
            mapOf(
                "create" to AndroidCliIssueCode.EMULATOR_CREATE_UNAVAILABLE,
                "list" to AndroidCliIssueCode.EMULATOR_LIST_UNAVAILABLE,
                "start" to AndroidCliIssueCode.EMULATOR_START_UNAVAILABLE,
                "stop" to AndroidCliIssueCode.EMULATOR_STOP_UNAVAILABLE,
                "remove" to AndroidCliIssueCode.EMULATOR_REMOVE_UNAVAILABLE,
            )
    }
}
