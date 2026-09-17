package io.github.fredleonam.droidproof.host

import io.github.fredleonam.droidproof.device.AdbClient
import io.github.fredleonam.droidproof.device.AdbPathResolver
import io.github.fredleonam.droidproof.device.DeviceCollector
import io.github.fredleonam.droidproof.evidence.BundleSigningConfiguration
import io.github.fredleonam.droidproof.evidence.Ed25519KeyLoader
import io.github.fredleonam.droidproof.evidence.EvidenceBundleWriter
import io.github.fredleonam.droidproof.model.DroidProofVersion
import io.github.fredleonam.droidproof.model.EnvironmentExecutionMode
import java.nio.file.Path

fun main(args: Array<String>) {
    require(args.size == 17) { "Expected smoke-scenario task configuration arguments." }
    require(args[1].isNotBlank()) { "Set -Pdroidproof.apkPath to one local APK." }
    require(args[2].isNotBlank()) { "Set -Pdroidproof.scenarioPath to one local scenario JSON document." }
    val replaceExisting =
        requireNotNull(args[5].toBooleanStrictOrNull()) {
            "droidproof.replaceExisting must be true or false."
        }
    val adbPath = AdbPathResolver.resolve(args[4].takeIf(String::isNotBlank))
    val lifecycle =
        EmulatorLifecycleConfiguration(
            deviceSerial = args[3].takeIf(String::isNotBlank),
            avdName = args[11].takeIf(String::isNotBlank),
            emulatorPath = Path.of(args[12]),
            adbPath = adbPath,
            port = args[13].toInt(),
            startupTimeoutMillis = args[14].toLong(),
            shutdownTimeoutMillis = args[15].toLong(),
        )
    val privateKeyPath = args[6].takeIf(String::isNotBlank)
    val publicKeyPath = args[7].takeIf(String::isNotBlank)
    require((privateKeyPath == null) == (publicKeyPath == null)) {
        "droidproof.signingPrivateKeyPath and droidproof.signingPublicKeyPath must be supplied together."
    }
    val signing =
        if (privateKeyPath == null) {
            null
        } else {
            BundleSigningConfiguration(
                Ed25519KeyLoader.loadPrivateKey(Path.of(privateKeyPath)),
                Ed25519KeyLoader.loadPublicKey(Path.of(requireNotNull(publicKeyPath))),
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
            recoveryJournalStore = FileEmulatorRecoveryJournalStore(Path.of(args[10])),
        )
    var session: ManagedEmulatorSession? = null
    var primary: Throwable? = null
    val result =
        try {
            session =
                if (lifecycle.avdName != null) {
                    LegacyEmulatorLifecycleManager().start(lifecycle)
                } else {
                    object : ManagedEmulatorSession {
                        override val serial = requireNotNull(lifecycle.deviceSerial)

                        override fun close() = Unit
                    }
                }
            coordinator.run(
                SmokeRunRequest(
                    apkPath = Path.of(args[1]),
                    scenarioPath = Path.of(args[2]),
                    deviceSerial = requireNotNull(session).serial,
                    outputRoot = Path.of(args[0]),
                    environmentPath = args[8].takeIf(String::isNotBlank)?.let(Path::of),
                    environmentMode =
                        requireNotNull(
                            args[9].let {
                                runCatching {
                                    EnvironmentExecutionMode.valueOf(it)
                                }.getOrNull()
                            },
                        ) { "droidproof.environmentMode must be VERIFY_ONLY or APPLY_AND_RESTORE." },
                    replaceExisting = replaceExisting,
                    droidProofVersion = DroidProofVersion(args[16]),
                ),
            )
        } catch (e: Throwable) {
            primary = e
            throw e
        } finally {
            try {
                session?.close()
            } catch (cleanup: Throwable) {
                if (primary != null) primary.addSuppressed(cleanup) else throw cleanup
            }
        }
    val location = result.output ?: result.diagnostic
    println("DroidProof smoke result: ${result.document?.status ?: "ERROR"} at $location")
    check(result.isSuccessful) {
        "Smoke scenario did not meet successful task criteria; inspect the preserved local output."
    }
}
