package io.github.fredleonam.droidproof.model

import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
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
