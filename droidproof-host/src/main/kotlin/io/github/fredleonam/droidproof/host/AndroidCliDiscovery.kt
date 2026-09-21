package io.github.fredleonam.droidproof.host

import io.github.fredleonam.droidproof.device.CommandFailure
import io.github.fredleonam.droidproof.device.CommandRequest
import io.github.fredleonam.droidproof.device.CommandResult
import io.github.fredleonam.droidproof.device.CommandRunner
import java.nio.file.Path

internal data class AndroidCliCommand(val arguments: List<String>)

internal enum class AndroidCliSdkOptionStyle { EQUALS, SEPARATE }

internal object AndroidCliCommandBuilder {
    fun globalHelp(executable: Path) = AndroidCliCommand(listOf(executable.toString(), "--help"))

    fun version(
        executable: Path,
        sdkRoot: Path,
        noMetricsSupported: Boolean,
        sdkOptionStyle: AndroidCliSdkOptionStyle = AndroidCliSdkOptionStyle.EQUALS,
    ) = command(executable, sdkRoot, noMetricsSupported, sdkOptionStyle, "--version")

    fun emulatorHelp(
        executable: Path,
        sdkRoot: Path,
        noMetricsSupported: Boolean,
        sdkOptionStyle: AndroidCliSdkOptionStyle = AndroidCliSdkOptionStyle.EQUALS,
    ) = command(executable, sdkRoot, noMetricsSupported, sdkOptionStyle, "emulator", "--help")

    fun emulatorCommandHelp(
        executable: Path,
        sdkRoot: Path,
        noMetricsSupported: Boolean,
        command: String,
        sdkOptionStyle: AndroidCliSdkOptionStyle = AndroidCliSdkOptionStyle.EQUALS,
    ) = command(executable, sdkRoot, noMetricsSupported, sdkOptionStyle, "emulator", command, "--help")

    fun emulatorList(
        executable: Path,
        sdkRoot: Path,
        noMetricsSupported: Boolean,
        sdkOptionStyle: AndroidCliSdkOptionStyle = AndroidCliSdkOptionStyle.EQUALS,
    ) = command(executable, sdkRoot, noMetricsSupported, sdkOptionStyle, "emulator", "list")

    private fun command(
        executable: Path,
        sdkRoot: Path,
        noMetricsSupported: Boolean,
        sdkOptionStyle: AndroidCliSdkOptionStyle,
        vararg command: String,
    ) = AndroidCliCommand(
        buildList {
            add(executable.toString())
            val normalizedSdk = sdkRoot.toAbsolutePath().normalize().toString()
            if (sdkOptionStyle == AndroidCliSdkOptionStyle.EQUALS) {
                add("--sdk=$normalizedSdk")
            } else {
                add("--sdk")
                add(normalizedSdk)
            }
            if (noMetricsSupported) add("--no-metrics")
            addAll(command)
        },
    )
}

internal enum class AndroidCliObservationReason(val label: String) {
    SUCCESS("success"),
    NONZERO_EXIT("non-zero exit"),
    TIMEOUT("timeout"),
    PROCESS_FAILURE("process failure"),
    OUTPUT_LIMIT("output limit"),
    BLANK_OUTPUT("blank output"),
}

internal data class AndroidCliCommandObservation(
    val status: AndroidCliCapabilityStatus,
    val reason: AndroidCliObservationReason,
    val output: String,
) {
    val isSupported get() = status == AndroidCliCapabilityStatus.SUPPORTED
}

internal data class AndroidCliDiscoveredSurface(
    val globalHelp: AndroidCliCommandObservation,
    val version: AndroidCliCommandObservation,
    val emulatorHelp: AndroidCliCommandObservation,
    val commandHelp: Map<String, AndroidCliCommandObservation>,
    val listExecution: AndroidCliCommandObservation,
)

internal class AndroidCliSurfaceDiscovery(private val runner: CommandRunner) {
    fun discover(
        executable: Path,
        sdkRoot: Path,
        timeoutMillis: Long,
    ): AndroidCliDiscoveredSurface {
        val globalHelp = execute(AndroidCliCommandBuilder.globalHelp(executable), timeoutMillis, true)
        val noMetrics = globalHelp.isSupported && globalHelp.output.hasExactOption("--no-metrics")
        val sdkOptionStyle = globalHelp.output.sdkOptionStyle()
        val commandHelp =
            COMMANDS.associateWith {
                execute(
                    AndroidCliCommandBuilder.emulatorCommandHelp(executable, sdkRoot, noMetrics, it, sdkOptionStyle),
                    timeoutMillis,
                    true,
                )
            }
        return AndroidCliDiscoveredSurface(
            globalHelp,
            execute(AndroidCliCommandBuilder.version(executable, sdkRoot, noMetrics, sdkOptionStyle), timeoutMillis, true),
            execute(AndroidCliCommandBuilder.emulatorHelp(executable, sdkRoot, noMetrics, sdkOptionStyle), timeoutMillis, true),
            commandHelp,
            execute(AndroidCliCommandBuilder.emulatorList(executable, sdkRoot, noMetrics, sdkOptionStyle), timeoutMillis, false),
        )
    }

    private fun execute(
        command: AndroidCliCommand,
        timeoutMillis: Long,
        requireOutput: Boolean,
    ): AndroidCliCommandObservation {
        val result =
            try {
                runner.execute(
                    CommandRequest(
                        command.arguments,
                        timeoutMillis,
                        stdoutLimitBytes = OUTPUT_LIMIT_BYTES,
                        stderrLimitBytes = OUTPUT_LIMIT_BYTES,
                    ),
                )
            } catch (_: Exception) {
                return unverified(AndroidCliObservationReason.PROCESS_FAILURE)
            }
        return result.toObservation(requireOutput)
    }

    private fun CommandResult.toObservation(requireOutput: Boolean): AndroidCliCommandObservation =
        when {
            failure == CommandFailure.NONZERO_EXIT || exitCode != null && exitCode != 0 ->
                AndroidCliCommandObservation(
                    AndroidCliCapabilityStatus.INCOMPATIBLE,
                    AndroidCliObservationReason.NONZERO_EXIT,
                    "",
                )
            failure == CommandFailure.TIMEOUT -> unverified(AndroidCliObservationReason.TIMEOUT)
            failure == CommandFailure.OUTPUT_LIMIT -> unverified(AndroidCliObservationReason.OUTPUT_LIMIT)
            failure != null || exitCode == null -> unverified(AndroidCliObservationReason.PROCESS_FAILURE)
            requireOutput && stdout.isBlank() -> unverified(AndroidCliObservationReason.BLANK_OUTPUT)
            else ->
                AndroidCliCommandObservation(
                    AndroidCliCapabilityStatus.SUPPORTED,
                    AndroidCliObservationReason.SUCCESS,
                    stdout,
                )
        }

    private fun unverified(reason: AndroidCliObservationReason) =
        AndroidCliCommandObservation(AndroidCliCapabilityStatus.UNVERIFIED, reason, "")

    private fun String.hasExactOption(option: String) =
        Regex("(^|\\s)${Regex.escape(option)}(?=\\s|$|[,\\]])", RegexOption.MULTILINE).containsMatchIn(this)

    private fun String.sdkOptionStyle() =
        if (!Regex("(^|\\s)--sdk=\\S+", RegexOption.MULTILINE).containsMatchIn(this) &&
            Regex("(^|\\s)--sdk\\s+\\S+", RegexOption.MULTILINE).containsMatchIn(this)
        ) {
            AndroidCliSdkOptionStyle.SEPARATE
        } else {
            AndroidCliSdkOptionStyle.EQUALS
        }

    private companion object {
        const val OUTPUT_LIMIT_BYTES = 16_384L
        val COMMANDS = listOf("create", "list", "start", "stop", "remove")
    }
}
