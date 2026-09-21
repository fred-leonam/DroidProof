package io.github.fredleonam.droidproof.host

import io.github.fredleonam.droidproof.device.CommandRunner
import io.github.fredleonam.droidproof.device.ProcessCommandRunner

/** Backend selection is explicit: there is deliberately no cross-backend fallback. */
object EmulatorBackendFactory {
    fun provisioner(backend: EmulatorBackend): EmulatorProvisioner =
        when (backend) {
            EmulatorBackend.LEGACY -> LegacySdkEmulatorProvisioner()
            EmulatorBackend.ANDROID_CLI -> AndroidCliEmulatorProvisioner()
        }
}

class AndroidCliCompatibilityException internal constructor(
    internal val report: AndroidCliCompatibilityReport?,
    message: String,
) : EmulatorProvisioningException(message)

/** Fail-closed: no Android CLI mutation occurs before the installed surface proves the required guarantees. */
class AndroidCliEmulatorProvisioner(
    private val runner: CommandRunner = ProcessCommandRunner(),
    private val osName: () -> String = { System.getProperty("os.name").orEmpty() },
) : EmulatorProvisioner {
    override fun provision(configuration: EmulatorProvisioningConfiguration): ProvisionedEmulator {
        val executable =
            configuration.androidCliPath
                ?: throw AndroidCliCompatibilityException(
                    null,
                    "android-cli backend requires droidproof.androidCliPath. No SDK, AVD, or emulator was modified.",
                )
        val report =
            AndroidCliCompatibilityProbe(runner, osName).inspect(
                AndroidCliProbeConfiguration(
                    executable,
                    configuration.sdkRoot,
                    configuration.timeoutMillis,
                ),
            )
        throw AndroidCliCompatibilityException(
            report,
            "Android CLI provisioning refused (outcome=${report.outcome}; " +
                "issues=${report.issues.joinToString(",") { it.code.name }}). " +
                "Deterministic provisioning parity is not enabled; no SDK, AVD, or emulator was modified.",
        )
    }
}
