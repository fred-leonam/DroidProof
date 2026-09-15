package io.github.fredleonam.droidproof.host

import io.github.fredleonam.droidproof.device.AdbClient
import io.github.fredleonam.droidproof.device.AdbPathResolver
import io.github.fredleonam.droidproof.device.DeviceCollector
import io.github.fredleonam.droidproof.evidence.BundleSigningConfiguration
import io.github.fredleonam.droidproof.evidence.Ed25519KeyLoader
import io.github.fredleonam.droidproof.evidence.EvidenceBundleWriter
import io.github.fredleonam.droidproof.model.DroidProofVersion
import java.nio.file.Path

fun main(args: Array<String>) {
    require(args.size == 9) { "Expected smoke-scenario task configuration arguments." }
    require(args[1].isNotBlank()) { "Set -Pdroidproof.apkPath to one local APK." }
    require(args[2].isNotBlank()) { "Set -Pdroidproof.scenarioPath to one local scenario JSON document." }
    require(args[3].isNotBlank()) { "Set -Pdroidproof.deviceSerial to an authorized test emulator serial." }
    val replaceExisting =
        requireNotNull(args[5].toBooleanStrictOrNull()) {
            "droidproof.replaceExisting must be true or false."
        }
    val adbPath = AdbPathResolver.resolve(args[4].takeIf(String::isNotBlank))
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
    val result =
        SmokeCoordinator(
            device = SmokeAdbClient(adbPath),
            capture = DeviceEvidenceCapture(DeviceCollector(AdbClient(adbPath))::capture),
            publisher =
                BundlePublisher { request, destination ->
                    EvidenceBundleWriter().write(request, destination, signing = signing)
                },
        ).run(
            SmokeRunRequest(
                apkPath = Path.of(args[1]),
                scenarioPath = Path.of(args[2]),
                deviceSerial = args[3],
                outputRoot = Path.of(args[0]),
                replaceExisting = replaceExisting,
                droidProofVersion = DroidProofVersion(args[8]),
            ),
        )
    val location = result.output ?: result.diagnostic
    println("DroidProof smoke result: ${result.document?.status ?: "ERROR"} at $location")
    check(result.isSuccessful) {
        "Smoke scenario did not meet successful task criteria; inspect the preserved local output."
    }
}
