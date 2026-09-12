package io.github.fredleonam.droidproof.evidence

import io.github.fredleonam.droidproof.model.AndroidArtifactIdentity
import io.github.fredleonam.droidproof.model.AndroidArtifactType
import io.github.fredleonam.droidproof.model.ArtifactBindingStatus
import io.github.fredleonam.droidproof.model.ArtifactBindingSummary
import io.github.fredleonam.droidproof.model.BundleId
import io.github.fredleonam.droidproof.model.BundleRelativePath
import io.github.fredleonam.droidproof.model.DroidProofVersion
import io.github.fredleonam.droidproof.model.EvidenceBundleManifestV3
import io.github.fredleonam.droidproof.model.EvidenceCompleteness
import io.github.fredleonam.droidproof.model.ExecutionStatus
import io.github.fredleonam.droidproof.model.ExecutionSummary
import io.github.fredleonam.droidproof.model.ObservedExecutionEnvironment
import io.github.fredleonam.droidproof.model.ObservedValue
import io.github.fredleonam.droidproof.model.RequestedExecutionConfiguration
import io.github.fredleonam.droidproof.model.ScenarioId
import io.github.fredleonam.droidproof.model.ScenarioIdentity
import io.github.fredleonam.droidproof.model.ScenarioVerdict
import io.github.fredleonam.droidproof.model.Sha256
import io.github.fredleonam.droidproof.model.UtcTimestamp
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class EvidenceSchemaV3Test {
    @TempDir
    lateinit var directory: Path

    @Test
    fun `writes and verifies schema v3 inventory and references`() {
        val result = source("result.json", "{}\n")
        val binding = source("binding.json", "{}\n")
        val bundle = directory.resolve("v3")

        EvidenceBundleWriter().write(
            EvidenceBundleRequestV3(
                manifest(),
                emptyList(),
                listOf(
                    EvidenceFileInput(result, RESULT_PATH, "application/json"),
                    EvidenceFileInput(binding, BINDING_PATH, "application/json"),
                ),
            ),
            bundle,
        )

        val verification = EvidenceBundleVerifier().verify(bundle)
        assertTrue(verification.isValid)
        assertEquals(3, verification.schemaVersion)
        assertTrue(Files.readString(bundle.resolve("manifest.json")).contains("\"evidenceFiles\""))
    }

    @Test
    fun `rejects unresolved schema v3 manifest references`() {
        val binding = source("binding.json", "{}")

        assertFailsWith<EvidenceBundleValidationException> {
            EvidenceBundleWriter().write(
                EvidenceBundleRequestV3(
                    manifest(),
                    emptyList(),
                    listOf(EvidenceFileInput(binding, BINDING_PATH, "application/json")),
                ),
                directory.resolve("invalid"),
            )
        }
    }

    private fun manifest(): EvidenceBundleManifestV3 {
        val unavailable = ObservedValue(unavailableReason = "Not collected.")
        val digest = Sha256("a".repeat(64))
        return EvidenceBundleManifestV3(
            3,
            BundleId("smoke-run"),
            UtcTimestamp("2026-09-12T12:00:00Z"),
            AndroidArtifactIdentity(AndroidArtifactType.APK, digest),
            ArtifactBindingSummary(
                "io.droidproof.smoke",
                digest,
                status = ArtifactBindingStatus.NOT_ESTABLISHED,
                detailPath = BINDING_PATH,
            ),
            ScenarioIdentity(ScenarioId("smoke-ready"), Sha256("b".repeat(64))),
            RequestedExecutionConfiguration("emulator-5554", "io.droidproof.smoke", true, false),
            ObservedExecutionEnvironment(
                unavailable,
                unavailable,
                unavailable,
                unavailable,
                unavailable,
                unavailable,
                unavailable,
            ),
            ExecutionSummary(
                ExecutionStatus.ERROR,
                ScenarioVerdict.NOT_EVALUATED,
                EvidenceCompleteness.PARTIAL,
                RESULT_PATH,
            ),
            DroidProofVersion("0.1.0-SNAPSHOT"),
        )
    }

    private fun source(
        name: String,
        content: String,
    ): Path = directory.resolve(name).also { Files.writeString(it, content) }

    private companion object {
        val RESULT_PATH = BundleRelativePath("execution/result.json")
        val BINDING_PATH = BundleRelativePath("execution/artifact-binding.json")
    }
}
