package io.github.fredleonam.droidproof.host

import io.github.fredleonam.droidproof.device.AdbClient
import io.github.fredleonam.droidproof.device.AdbPathResolver
import io.github.fredleonam.droidproof.device.DeviceCollector
import io.github.fredleonam.droidproof.evidence.BundleSigningConfiguration
import io.github.fredleonam.droidproof.evidence.Ed25519KeyLoader
import io.github.fredleonam.droidproof.evidence.EvidenceBundleWriter
import io.github.fredleonam.droidproof.model.DroidProofVersion
import java.nio.file.Path

fun main(args: Array<String>) = runSmokeScenario(RunSmokeScenarioConfiguration.parse(args))

internal fun runSmokeScenario(configuration: RunSmokeScenarioConfiguration) {
    val adbPath = AdbPathResolver.resolve(configuration.adbPath)
    val signing =
        configuration.signingPrivateKeyPath?.let {
            BundleSigningConfiguration(
                Ed25519KeyLoader.loadPrivateKey(it),
                Ed25519KeyLoader.loadPublicKey(requireNotNull(configuration.signingPublicKeyPath)),
            )
        }
    val coordinator =
        SmokeCoordinator(
            device = SmokeAdbClient(adbPath),
            capture = DeviceEvidenceCapture(DeviceCollector(AdbClient(adbPath))::capture),
            publisher =
                BundlePublisher { request, destination ->
                    EvidenceBundleWriter().write(request, destination, signing = signing)
                },
            recoveryJournalStore = FileEmulatorRecoveryJournalStore(configuration.recoveryStateRoot),
        )
    var session: ManagedEmulatorSession? = null
    var primary: Throwable? = null
    val result =
        try {
            session = createSession(configuration, adbPath)
            coordinator.run(
                SmokeRunRequest(
                    configuration.apkPath,
                    configuration.scenarioPath,
                    requireNotNull(session).serial,
                    configuration.outputRoot,
                    configuration.environmentPath,
                    configuration.environmentMode,
                    configuration.replaceExisting,
                    DroidProofVersion(configuration.version),
                ),
            )
        } catch (error: Throwable) {
            primary = error
            throw error
        } finally {
            try {
                session?.close()
            } catch (cleanup: Throwable) {
                if (primary != null) primary.addSuppressed(cleanup) else throw cleanup
            }
        }
    val location = result.output ?: result.diagnostic
    println("DroidProof smoke result: ${result.document?.status ?: "ERROR"} at $location")
    check(result.isSuccessful) { "Smoke scenario did not meet successful task criteria; inspect the preserved local output." }
}

internal fun createSession(
    configuration: RunSmokeScenarioConfiguration,
    adbPath: Path,
): ManagedEmulatorSession =
    when {
        configuration.deviceSerial != null ->
            object : ManagedEmulatorSession {
                override val serial = requireNotNull(configuration.deviceSerial)

                override fun close() = Unit
            }
        configuration.provisioningPath != null ->
            EmulatorBackendFactory.provisioner(
                configuration.emulatorBackend,
            ).provision(
                EmulatorProvisioningConfiguration(
                    EmulatorProvisioningContractLoader.load(
                        configuration.provisioningPath,
                    ),
                    requireNotNull(
                        configuration.sdkRoot,
                    ).toAbsolutePath(),
                    configuration.provisioningStateRoot.toAbsolutePath(),
                    configuration.avdManagerPath.toAbsolutePath(),
                    configuration.emulatorPath,
                    adbPath,
                    configuration.emulatorPort,
                    configuration.lifecycleStartupTimeoutMillis,
                    configuration.androidCliPath,
                ),
            )
        else ->
            LegacyEmulatorLifecycleManager().start(
                EmulatorLifecycleConfiguration(
                    null,
                    requireNotNull(configuration.avdName),
                    configuration.emulatorPath,
                    adbPath,
                    configuration.emulatorPort,
                    configuration.lifecycleStartupTimeoutMillis,
                    configuration.lifecycleShutdownTimeoutMillis,
                ),
            )
    }
