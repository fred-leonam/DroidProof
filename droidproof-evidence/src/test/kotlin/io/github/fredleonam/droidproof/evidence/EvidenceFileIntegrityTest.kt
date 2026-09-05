package io.github.fredleonam.droidproof.evidence

import io.github.fredleonam.droidproof.model.AndroidArtifactIdentity
import io.github.fredleonam.droidproof.model.AndroidArtifactType
import io.github.fredleonam.droidproof.model.AnimationConfiguration
import io.github.fredleonam.droidproof.model.BundleId
import io.github.fredleonam.droidproof.model.BundleRelativePath
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
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import org.junit.jupiter.api.io.TempDir
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class EvidenceFileIntegrityTest {
    @TempDir
    lateinit var temporaryDirectory: Path

    private val writer = EvidenceBundleWriter()
    private val verifier = EvidenceBundleVerifier()

    @Test
    fun `writes and verifies nested files with deterministic inventory and streamed metadata`() {
        val log = source("logcat.txt", "line one\nline two\n")
        val response = source("response.json", "{\"orderId\":42}\n")
        val destination = temporaryDirectory.resolve("bundle")

        writer.write(
            EvidenceBundleRequest(
                manifest(),
                events(EvidenceReference(BundleRelativePath("network/orders/attempt-2.json"), "application/json")),
                listOf(
                    EvidenceFileInput(log, BundleRelativePath("logs/logcat.txt"), "text/plain", EvidenceFileRole.LOGCAT),
                    EvidenceFileInput(
                        response,
                        BundleRelativePath("network/orders/attempt-2.json"),
                        "application/json",
                        EvidenceFileRole.NETWORK,
                    ),
                ).reversed(),
            ),
            destination,
        )

        val manifestJson = destination.resolve("manifest.json").readText()
        assertTrue(manifestJson.indexOf("logs/logcat.txt") < manifestJson.indexOf("network/orders/attempt-2.json"))
        assertTrue(manifestJson.contains(Sha256Calculator.calculate(response).value))
        assertTrue(manifestJson.contains("\"byteSize\": 15"))
        assertTrue(verifier.verify(destination).isValid)
    }

    @Test
    fun `allows an empty inventory and supplied evidence without a timeline reference`() {
        val empty = temporaryDirectory.resolve("empty")
        writer.write(EvidenceBundleRequest(manifest(), emptyList(), emptyList()), empty)
        assertTrue(verifier.verify(empty).isValid)

        val attachment = source("notes.txt", "notes")
        val populated = temporaryDirectory.resolve("populated")
        writer.write(
            EvidenceBundleRequest(
                manifest(),
                emptyList(),
                listOf(EvidenceFileInput(attachment, BundleRelativePath("attachments/notes.txt"), "text/plain")),
            ),
            populated,
        )
        assertTrue(verifier.verify(populated).isValid)
    }

    @Test
    fun `rejects missing references duplicates collisions reserved paths and unsafe paths`() {
        val source = source("evidence.txt", "evidence")
        assertFailsWith<EvidenceBundleValidationException> {
            writer.write(
                EvidenceBundleRequest(
                    manifest(),
                    events(EvidenceReference(BundleRelativePath("missing.txt"), "text/plain")),
                    emptyList(),
                ),
                temporaryDirectory.resolve("missing-reference"),
            )
        }
        assertFailsWith<EvidenceBundleValidationException> {
            writeInputs(
                "duplicate",
                EvidenceFileInput(source, BundleRelativePath("logs/output.txt"), "text/plain"),
                EvidenceFileInput(source, BundleRelativePath("logs/output.txt"), "text/plain"),
            )
        }
        assertFailsWith<EvidenceBundleValidationException> {
            writeInputs(
                "media-conflict",
                EvidenceFileInput(source, BundleRelativePath("logs/output.txt"), "text/plain"),
                EvidenceFileInput(source, BundleRelativePath("logs/output.txt"), "application/json"),
            )
        }
        assertFailsWith<EvidenceBundleValidationException> {
            writeInputs(
                "collision",
                EvidenceFileInput(source, BundleRelativePath("Logs/output.txt"), "text/plain"),
                EvidenceFileInput(source, BundleRelativePath("logs/output.txt"), "text/plain"),
            )
        }
        assertFailsWith<IllegalArgumentException> { BundleRelativePath("manifest.json") }
        assertFailsWith<IllegalArgumentException> { BundleRelativePath("/absolute.txt") }
        assertFailsWith<IllegalArgumentException> { BundleRelativePath("a/../escape.txt") }
    }

    @Test
    fun `rejects symbolic link sources`() {
        val source = source("actual.txt", "evidence")
        val link = temporaryDirectory.resolve("source-link")
        Files.createSymbolicLink(link, source)

        assertFailsWith<EvidenceBundleValidationException> {
            writeInputs("linked", EvidenceFileInput(link, BundleRelativePath("linked.txt"), "text/plain"))
        }

        val directory = temporaryDirectory.resolve("directory-source")
        Files.createDirectory(directory)
        assertFailsWith<EvidenceBundleValidationException> {
            writeInputs("directory", EvidenceFileInput(directory, BundleRelativePath("directory.txt"), "text/plain"))
        }
    }

    @Test
    fun `detects corruption size changes symlinks and unexpected files`() {
        val source = source("actual.txt", "original")
        val bundle = temporaryDirectory.resolve("bundle")
        writeInputsTo(bundle, EvidenceFileInput(source, BundleRelativePath("files/actual.txt"), "text/plain"))

        bundle.resolve("files/actual.txt").writeText("corrupt!")
        assertIssue(bundle, VerificationIssueCode.SHA256_MISMATCH)

        bundle.resolve("files/actual.txt").writeText("longer than original")
        assertIssue(bundle, VerificationIssueCode.FILE_SIZE_MISMATCH)

        bundle.resolve("files/actual.txt").writeText("original")
        bundle.resolve("unexpected.txt").writeText("unexpected")
        assertIssue(bundle, VerificationIssueCode.UNEXPECTED_EVIDENCE_FILE)

        Files.delete(bundle.resolve("files/actual.txt"))
        Files.createSymbolicLink(bundle.resolve("files/actual.txt"), source)
        assertIssue(bundle, VerificationIssueCode.SYMBOLIC_LINK)
    }

    @Test
    fun `detects missing duplicate and unsafe inventory entries`() {
        val source = source("actual.txt", "original")
        val missing = temporaryDirectory.resolve("missing-file")
        writeInputsTo(missing, EvidenceFileInput(source, BundleRelativePath("files/actual.txt"), "text/plain"))
        Files.delete(missing.resolve("files/actual.txt"))
        assertIssue(missing, VerificationIssueCode.MISSING_REFERENCED_EVIDENCE_FILE)

        val duplicate = temporaryDirectory.resolve("duplicate-inventory")
        writeInputsTo(duplicate, EvidenceFileInput(source, BundleRelativePath("files/actual.txt"), "text/plain"))
        val duplicateManifest =
            evidenceJson.decodeFromString<EvidenceBundleManifest>(duplicate.resolve("manifest.json").readText())
        duplicate.resolve("manifest.json").writeText(
            evidenceJson.encodeToString(
                duplicateManifest.copy(evidenceFiles = duplicateManifest.evidenceFiles + duplicateManifest.evidenceFiles),
            ) + "\n",
        )
        assertIssue(duplicate, VerificationIssueCode.DUPLICATE_INVENTORY_PATH)

        val unsafe = temporaryDirectory.resolve("unsafe-inventory")
        writeInputsTo(unsafe, EvidenceFileInput(source, BundleRelativePath("files/actual.txt"), "text/plain"))
        val unsafeManifest = unsafe.resolve("manifest.json")
        unsafeManifest.writeText(unsafeManifest.readText().replace("files/actual.txt", "../escape.txt"))
        assertIssue(unsafe, VerificationIssueCode.UNSAFE_INVENTORY_PATH)
    }

    @Test
    fun `reports malformed manifest unsupported schema and missing core files`() {
        val malformed = temporaryDirectory.resolve("malformed")
        Files.createDirectories(malformed)
        malformed.resolve("manifest.json").writeText("{")
        malformed.resolve("timeline.json").writeText("{\"events\":[]}")
        assertIssue(malformed, VerificationIssueCode.MALFORMED_JSON)

        val unsupported = temporaryDirectory.resolve("unsupported")
        writer.write(EvidenceBundleRequest(manifest(), emptyList(), emptyList()), unsupported)
        val parsed = evidenceJson.parseToJsonElement(unsupported.resolve("manifest.json").readText()).jsonObject
        unsupported.resolve("manifest.json").writeText(
            evidenceJson.encodeToString(JsonObject.serializer(), JsonObject(parsed + ("schemaVersion" to JsonPrimitive(99)))) + "\n",
        )
        assertIssue(unsupported, VerificationIssueCode.UNSUPPORTED_SCHEMA)

        val missing = temporaryDirectory.resolve("missing")
        Files.createDirectories(missing)
        assertIssue(missing, VerificationIssueCode.MISSING_MANIFEST)
        assertIssue(missing, VerificationIssueCode.MISSING_TIMELINE)
    }

    @Test
    fun `reports unregistered timeline references and media type conflicts`() {
        val source = source("actual.json", "{}")
        val bundle = temporaryDirectory.resolve("bundle")
        writeInputsTo(bundle, EvidenceFileInput(source, BundleRelativePath("network/actual.json"), "application/json"))
        val timeline = bundle.resolve("timeline.json")
        timeline.writeText(
            evidenceJson.encodeToString(
                io.github.fredleonam.droidproof.model.TimelineDocument(
                    events(EvidenceReference(BundleRelativePath("network/missing.json"), "text/plain")),
                ),
            ) + "\n",
        )
        assertIssue(bundle, VerificationIssueCode.UNREGISTERED_TIMELINE_REFERENCE)

        timeline.writeText(
            evidenceJson.encodeToString(
                io.github.fredleonam.droidproof.model.TimelineDocument(
                    events(EvidenceReference(BundleRelativePath("network/actual.json"), "text/plain")),
                ),
            ) + "\n",
        )
        assertIssue(bundle, VerificationIssueCode.MEDIA_TYPE_CONFLICT)
    }

    @Test
    fun `replaces transactionally removes stale files and preserves old bundle on failed replacement`() {
        val first = source("first.txt", "first")
        val second = source("second.txt", "second")
        val destination = temporaryDirectory.resolve("bundle")
        writeInputsTo(destination, EvidenceFileInput(first, BundleRelativePath("old.txt"), "text/plain"))

        writer.write(
            EvidenceBundleRequest(
                manifest(),
                emptyList(),
                listOf(EvidenceFileInput(second, BundleRelativePath("new.txt"), "text/plain")),
            ),
            destination,
            overwrite = true,
        )
        assertFalse(Files.exists(destination.resolve("old.txt")))
        assertTrue(Files.exists(destination.resolve("new.txt")))

        val originalManifest = destination.resolve("manifest.json").readText()
        val failingWriter = EvidenceBundleWriter(FailingReplacementFileOperations())
        assertFailsWith<Exception> {
            failingWriter.write(
                EvidenceBundleRequest(
                    manifest(),
                    emptyList(),
                    listOf(EvidenceFileInput(first, BundleRelativePath("third.txt"), "text/plain")),
                ),
                destination,
                overwrite = true,
            )
        }
        assertEquals(originalManifest, destination.resolve("manifest.json").readText())
        assertTrue(Files.exists(destination.resolve("new.txt")))
        assertTrue(verifier.verify(destination).isValid)
        Files.list(temporaryDirectory).use { paths ->
            assertFalse(paths.anyMatch { it.fileName.toString().startsWith(".droidproof-") })
        }
    }

    @Test
    fun `schema v1 remains readable and reports integrity unavailable`() {
        val bundle = temporaryDirectory.resolve("v1")
        Files.createDirectories(bundle)
        val manifestObject =
            evidenceJson.parseToJsonElement(evidenceJson.encodeToString(manifest().copy(schemaVersion = 1))).jsonObject
        bundle.resolve("manifest.json").writeText(
            evidenceJson.encodeToString(JsonObject.serializer(), JsonObject(manifestObject - "evidenceFiles"))
                .replace(": 0.0", ": 0")
                .replace(": 0.5", ": 0")
                .replace(": 1.0", ": 1") + "\n",
        )
        bundle.resolve(
            "timeline.json",
        ).writeText(evidenceJson.encodeToString(io.github.fredleonam.droidproof.model.TimelineDocument(emptyList())) + "\n")

        val result = verifier.verify(bundle)

        assertTrue(result.isValid)
        assertTrue(result.warnings.any { it.code == VerificationIssueCode.FILE_INTEGRITY_UNAVAILABLE })
    }

    private fun writeInputs(
        name: String,
        vararg inputs: EvidenceFileInput,
    ) = writeInputsTo(temporaryDirectory.resolve(name), *inputs)

    private fun writeInputsTo(
        destination: Path,
        vararg inputs: EvidenceFileInput,
    ) = writer.write(EvidenceBundleRequest(manifest(), emptyList(), inputs.toList()), destination)

    private fun source(
        name: String,
        content: String,
    ): Path = temporaryDirectory.resolve(name).also { it.writeText(content) }

    private fun assertIssue(
        bundle: Path,
        code: VerificationIssueCode,
    ) {
        assertTrue(verifier.verify(bundle).issues.any { it.code == code }, "Expected verification issue $code")
    }

    private fun manifest() =
        EvidenceBundleManifest(
            schemaVersion = 2,
            bundleId = BundleId("proof-checkout-offline-retry"),
            createdAt = UtcTimestamp("2026-09-04T12:00:00Z"),
            artifact = AndroidArtifactIdentity(AndroidArtifactType.APK, Sha256("a".repeat(64))),
            gitCommit = GitCommit("abc1234"),
            scenario = ScenarioIdentity(ScenarioId("checkout-offline-retry"), Sha256("b".repeat(64))),
            environment =
                EnvironmentContract(
                    DeviceInformation("google/sdk_gphone64_arm64/emu64a:15/test", 35),
                    "en-US",
                    Orientation.PORTRAIT,
                    AnimationConfiguration(0.0, 0.5, 1.0),
                    42,
                ),
            droidProofVersion = DroidProofVersion("0.2.0"),
        )

    private fun events(reference: EvidenceReference) =
        listOf(
            TimelineEvent(
                EventId("event-a"),
                UtcTimestamp("2026-09-04T12:00:01Z"),
                EventSource.TEST,
                "evidence.recorded",
                evidence = listOf(reference),
            ),
        )
}

private class FailingReplacementFileOperations : BundleFileOperations by NioBundleFileOperations {
    private var moves = 0

    override fun moveDirectory(
        source: Path,
        target: Path,
    ) {
        moves++
        if (moves == 2) throw IOException("simulated replacement failure")
        NioBundleFileOperations.moveDirectory(source, target)
    }
}
