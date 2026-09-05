package io.github.fredleonam.droidproof.evidence

import io.github.fredleonam.droidproof.model.AndroidArtifactIdentity
import io.github.fredleonam.droidproof.model.AndroidArtifactType
import io.github.fredleonam.droidproof.model.AnimationConfiguration
import io.github.fredleonam.droidproof.model.BundleId
import io.github.fredleonam.droidproof.model.BundleRelativePath
import io.github.fredleonam.droidproof.model.ControlledClock
import io.github.fredleonam.droidproof.model.DeviceInformation
import io.github.fredleonam.droidproof.model.DroidProofVersion
import io.github.fredleonam.droidproof.model.EnvironmentContract
import io.github.fredleonam.droidproof.model.EventId
import io.github.fredleonam.droidproof.model.EventSource
import io.github.fredleonam.droidproof.model.EvidenceBundleManifest
import io.github.fredleonam.droidproof.model.EvidenceFileRole
import io.github.fredleonam.droidproof.model.EvidenceReference
import io.github.fredleonam.droidproof.model.GitCommit
import io.github.fredleonam.droidproof.model.Orientation
import io.github.fredleonam.droidproof.model.ScenarioId
import io.github.fredleonam.droidproof.model.ScenarioIdentity
import io.github.fredleonam.droidproof.model.Sha256
import io.github.fredleonam.droidproof.model.TimelineEvent
import io.github.fredleonam.droidproof.model.UtcTimestamp
import java.nio.file.Files
import java.nio.file.Path

fun main(args: Array<String>) {
    require(args.size == 1) { "Expected output directory argument." }
    val destination = Path.of(args.single()).toAbsolutePath()
    Files.createDirectories(requireNotNull(destination.parent))
    val networkEvidence = Files.createTempFile(destination.parent, ".droidproof-scenario-data-", ".json")
    try {
        Files.writeString(networkEvidence, "{\"method\":\"POST\",\"path\":\"/orders\",\"status\":201}\n")
        EvidenceBundleWriter().write(
            EvidenceBundleRequest(
                manifest = sampleManifest(),
                events = sampleTimeline(),
                evidenceFiles =
                    listOf(
                        EvidenceFileInput(
                            source = networkEvidence,
                            destination = BundleRelativePath("network/orders-attempt-2.json"),
                            mediaType = "application/json",
                            role = EvidenceFileRole.NETWORK,
                        ),
                    ),
            ),
            destination,
            overwrite = true,
        )
        val verification = EvidenceBundleVerifier().verify(destination)
        check(verification.isValid) { "Generated sample failed verification: ${verification.issues}" }
    } finally {
        Files.deleteIfExists(networkEvidence)
    }
}

private fun sampleManifest() =
    EvidenceBundleManifest(
        schemaVersion = 2,
        bundleId = BundleId("proof-checkout-offline-retry"),
        createdAt = UtcTimestamp("2026-09-04T12:00:00Z"),
        artifact =
            AndroidArtifactIdentity(
                type = AndroidArtifactType.APK,
                sha256 = Sha256("97f1f8a6059ba406d10ebc2a1f822825d58bdcec44728d6be159971d38ca770d"),
                signingCertificateSha256 = Sha256("c1a93d4bf7b50ef6401ca2b575740f7b95e80f9527e6590109d939416f9fc20a"),
            ),
        gitCommit = GitCommit("6e45f98"),
        scenario =
            ScenarioIdentity(
                id = ScenarioId("checkout-offline-retry"),
                dataSha256 = Sha256("98eafc8b5cc5ac0a8cca387635267108af8e0fd7d1b97136867a0fd5bb1a9ec2"),
            ),
        environment =
            EnvironmentContract(
                device = DeviceInformation("google/sdk_gphone64_arm64/emu64a:15/AP3A.240905.015/1234567:userdebug/dev-keys", 35),
                locale = "en-US",
                orientation = Orientation.PORTRAIT,
                animations = AnimationConfiguration(0.0, 0.0, 0.0),
                randomSeed = 20260904,
                controlledClock = ControlledClock(UtcTimestamp("2026-09-04T12:00:00Z"), true),
            ),
        droidProofVersion = DroidProofVersion("0.2.0"),
    )

private fun sampleTimeline() =
    listOf(
        TimelineEvent(EventId("001-launch"), UtcTimestamp("2026-09-04T12:00:00Z"), EventSource.TEST, "app.launch"),
        TimelineEvent(EventId("002-submit"), UtcTimestamp("2026-09-04T12:00:03Z"), EventSource.APPLICATION, "checkout.submit"),
        TimelineEvent(
            EventId("003-first-order"),
            UtcTimestamp("2026-09-04T12:00:03Z"),
            EventSource.MOCK_SERVER,
            "http.request",
            attributes = mapOf("method" to "POST", "path" to "/orders", "responseStatus" to "503"),
        ),
        TimelineEvent(EventId("004-retry"), UtcTimestamp("2026-09-04T12:00:08Z"), EventSource.APPLICATION, "checkout.retry"),
        TimelineEvent(
            EventId("005-order-created"),
            UtcTimestamp("2026-09-04T12:00:08Z"),
            EventSource.MOCK_SERVER,
            "http.request",
            attributes = mapOf("method" to "POST", "path" to "/orders", "responseStatus" to "201"),
            evidence = listOf(EvidenceReference(BundleRelativePath("network/orders-attempt-2.json"), "application/json")),
        ),
    )
