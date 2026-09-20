package io.github.fredleonam.droidproof.host

import io.github.fredleonam.droidproof.device.CommandRequest
import io.github.fredleonam.droidproof.device.CommandRunner
import io.github.fredleonam.droidproof.device.ProcessCommandRunner
import java.nio.file.Files

/** Backend selection is explicit: there is deliberately no cross-backend fallback. */
object EmulatorBackendFactory {
    fun provisioner(backend: EmulatorBackend): EmulatorProvisioner =
        when (backend) {
            EmulatorBackend.LEGACY -> LegacySdkEmulatorProvisioner()
            EmulatorBackend.ANDROID_CLI -> AndroidCliEmulatorProvisioner()
        }
}

data class AndroidCliCommand(val arguments: List<String>, val environment: Map<String, String>)

/** Builds commands without a shell. The local CLI must prove this syntax before mutation is enabled. */
object AndroidCliCommandBuilder {
    fun discovery(
        executable: String,
        sdkRoot: String,
        noMetricsSupported: Boolean,
    ) = AndroidCliCommand(
        buildList {
            add(executable)
            add("--sdk")
            add(sdkRoot)
            if (noMetricsSupported) add("--no-metrics")
            add("emulator")
            add("list")
        },
        emptyMap(),
    )
}

class AndroidCliCompatibilityException(message: String) : EmulatorProvisioningException(message)

/** Fail-closed: no Android CLI mutation occurs before the installed surface proves the required guarantees. */
class AndroidCliEmulatorProvisioner(
    private val runner: CommandRunner = ProcessCommandRunner(),
) : EmulatorProvisioner {
    override fun provision(configuration: EmulatorProvisioningConfiguration): ProvisionedEmulator {
        val executable =
            configuration.androidCliPath
                ?: throw AndroidCliCompatibilityException("android-cli backend requires droidproof.androidCliPath.")
        if (!Files.isRegularFile(executable) || !Files.isExecutable(executable)) {
            throw AndroidCliCompatibilityException(
                "Configured Android CLI executable is unavailable: $executable",
            )
        }
        val result =
            runner.execute(CommandRequest(listOf(executable.toString(), "--version"), configuration.timeoutMillis))
        if (result.failure != null || result.exitCode != 0 || result.stdout.isBlank()) {
            throw AndroidCliCompatibilityException(
                "Android CLI version discovery failed within the configured timeout.",
            )
        }
        throw AndroidCliCompatibilityException(
            "Android CLI compatibility is fail-closed: this installation has not demonstrated isolated owned storage, " +
                "exact image revision selection, clean-state creation, and unambiguous serial association. " +
                "No SDK, AVD, or emulator was modified.",
        )
    }
}
