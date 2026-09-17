package io.github.fredleonam.droidproof.model

import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class EvidenceModelTest {
    @Test
    fun `serializes a complete manifest`() {
        val manifest = sampleManifest()

        val json = Json.encodeToString(EvidenceBundleManifest.serializer(), manifest)

        assertTrue(json.contains("checkout-offline-retry"))
        assertTrue(json.contains("signingCertificateSha256"))
        assertTrue(json.contains("2026-09-04T12:00:00Z"))
    }

    @Test
    fun `rejects invalid hashes and manifest schema versions`() {
        assertFailsWith<IllegalArgumentException> { Sha256("ABC") }
        assertFailsWith<IllegalArgumentException> { sampleManifest().copy(schemaVersion = 0) }
    }

    @Test
    fun `rejects unsafe evidence paths`() {
        assertFailsWith<IllegalArgumentException> { EvidenceReference(BundleRelativePath("../logcat.txt")) }
        assertFailsWith<IllegalArgumentException> { EvidenceReference(BundleRelativePath("/logcat.txt")) }
        assertFailsWith<IllegalArgumentException> { BundleRelativePath("logs\\logcat.txt") }
        assertFailsWith<IllegalArgumentException> { BundleRelativePath("network//request.json") }
        assertFailsWith<IllegalArgumentException> { BundleRelativePath("C:/evidence.txt") }
        assertFailsWith<IllegalArgumentException> { BundleRelativePath("manifest.json") }
        assertFailsWith<IllegalArgumentException> { BundleRelativePath("authenticity.json") }
    }

    @Test
    fun `accepts deterministic non-integer animation scales`() {
        val animations = AnimationConfiguration(0.0, 0.5, 1.0)

        assertEquals(
            "{\"windowScale\":0.0,\"transitionScale\":0.5,\"animatorScale\":1.0}",
            Json.encodeToString(AnimationConfiguration.serializer(), animations),
        )
        assertFailsWith<IllegalArgumentException> { animations.copy(windowScale = -0.1) }
        assertFailsWith<IllegalArgumentException> { animations.copy(transitionScale = Double.NaN) }
        assertFailsWith<IllegalArgumentException> { animations.copy(animatorScale = Double.POSITIVE_INFINITY) }
    }

    @Test
    fun `accepts future positive Android API levels`() {
        assertEquals(101, DeviceInformation("future/device", 101).apiLevel)
    }

    @Test
    fun `schema v3 records unavailable environment fields without invented values`() {
        val unavailable = ObservedValue(unavailableReason = "Not collected.")
        val manifest =
            EvidenceBundleManifestV3(
                schemaVersion = 3,
                bundleId = BundleId("smoke-run"),
                createdAt = UtcTimestamp("2026-09-04T12:00:00Z"),
                artifact = AndroidArtifactIdentity(AndroidArtifactType.APK, Sha256("a".repeat(64))),
                artifactBinding =
                    ArtifactBindingSummary(
                        "io.droidproof.smoke",
                        Sha256("a".repeat(64)),
                        status = ArtifactBindingStatus.NOT_ESTABLISHED,
                        detailPath = BundleRelativePath("execution/artifact-binding.json"),
                    ),
                scenario = ScenarioIdentity(ScenarioId("smoke-ready"), Sha256("b".repeat(64))),
                requestedConfiguration =
                    RequestedExecutionConfiguration("emulator-5554", "io.droidproof.smoke", true, false),
                observedEnvironment =
                    ObservedExecutionEnvironment(
                        unavailable,
                        unavailable,
                        unavailable,
                        unavailable,
                        unavailable,
                        unavailable,
                        unavailable,
                    ),
                execution =
                    ExecutionSummary(
                        ExecutionStatus.ERROR,
                        ScenarioVerdict.NOT_EVALUATED,
                        EvidenceCompleteness.PARTIAL,
                        BundleRelativePath("execution/result.json"),
                    ),
                droidProofVersion = DroidProofVersion("0.1.0-SNAPSHOT"),
            )

        val encoded = Json.encodeToString(EvidenceBundleManifestV3.serializer(), manifest)

        assertTrue(encoded.contains("\"unavailableReason\""))
        assertFalse(encoded.contains("\"locale\":\"en-US\""))
        assertFailsWith<IllegalArgumentException> { ObservedValue() }
        assertFailsWith<IllegalArgumentException> { ObservedValue("value", "reason") }
    }

    @Test
    fun `transaction mutation model has deterministic outcomes and checkpoint ordering`() {
        val identity =
            EmulatorCapabilityObservationV1(
                apiLevel = 35,
                buildFingerprint = "generic/sdk",
                bootIdentifier = "123e4567-e89b-12d3-a456-426614174000",
                commandSurfaces = listOf("getprop"),
            )
        val checkpoint =
            TransactionMutationCheckpointObservationV1(
                sequence = 1,
                checkpoint = TransactionMutationCheckpoint.AFTER_ARTIFACT_BINDING,
                observedAt = UtcTimestamp("2026-09-17T12:00:00Z"),
                identity = IdentityMutationObservationV1(MutationObservationOutcome.MATCHED, identity, "Identity matched."),
                environment =
                    EnvironmentMutationObservationV1(
                        MutationObservationOutcome.NOT_EVALUATED,
                        detail = "No environment contract was present.",
                    ),
                artifact =
                    ArtifactMutationObservationV1(
                        MutationObservationOutcome.DRIFT_DETECTED,
                        Sha256("b".repeat(64)),
                        "Artifact drifted.",
                    ),
                outcome = MutationObservationOutcome.DRIFT_DETECTED,
                detail = "Drift was detected.",
            )
        val document =
            TransactionMutationDocumentV1(
                baseline =
                    TransactionMutationBaselineV1(
                        identity,
                        artifact =
                            TransactionArtifactBaselineV1(
                                "io.droidproof.smoke",
                                Sha256("a".repeat(64)),
                                Sha256("a".repeat(64)),
                            ),
                    ),
                checkpoints = listOf(checkpoint),
                outcome = MutationObservationOutcome.DRIFT_DETECTED,
                explanation = "Sequential bounded observations detected drift.",
            )

        assertTrue(Json.encodeToString(TransactionMutationDocumentV1.serializer(), document).contains("DRIFT_DETECTED"))
        assertFailsWith<IllegalArgumentException> { document.copy(outcome = MutationObservationOutcome.MATCHED) }
        assertFailsWith<IllegalArgumentException> {
            document.copy(checkpoints = listOf(checkpoint.copy(sequence = 2)))
        }
    }
}

internal fun sampleManifest() =
    EvidenceBundleManifest(
        schemaVersion = 2,
        bundleId = BundleId("proof-checkout-offline-retry"),
        createdAt = UtcTimestamp("2026-09-04T12:00:00Z"),
        artifact =
            AndroidArtifactIdentity(
                type = AndroidArtifactType.APK,
                sha256 = Sha256("a".repeat(64)),
                signingCertificateSha256 = Sha256("b".repeat(64)),
            ),
        gitCommit = GitCommit("abc1234"),
        scenario = ScenarioIdentity(ScenarioId("checkout-offline-retry"), Sha256("c".repeat(64))),
        environment =
            EnvironmentContract(
                device = DeviceInformation("google/sdk_gphone64_arm64/emu64a:15/AP3A.240905.015/1234567:userdebug/dev-keys", 35),
                locale = "en-US",
                orientation = Orientation.PORTRAIT,
                animations = AnimationConfiguration(0.0, 0.5, 1.0),
                randomSeed = 42,
                controlledClock = ControlledClock(UtcTimestamp("2026-09-04T12:00:00Z"), true),
            ),
        droidProofVersion = DroidProofVersion("0.1.0"),
    )
