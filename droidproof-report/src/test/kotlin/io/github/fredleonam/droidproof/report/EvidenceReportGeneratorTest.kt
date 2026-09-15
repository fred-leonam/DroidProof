package io.github.fredleonam.droidproof.report

import io.github.fredleonam.droidproof.evidence.EvidenceBundleRequest
import io.github.fredleonam.droidproof.evidence.EvidenceBundleRequestV3
import io.github.fredleonam.droidproof.evidence.EvidenceBundleVerifier
import io.github.fredleonam.droidproof.evidence.EvidenceBundleWriter
import io.github.fredleonam.droidproof.evidence.EvidenceFileInput
import io.github.fredleonam.droidproof.evidence.VerificationIssueCode
import io.github.fredleonam.droidproof.evidence.evidenceJson
import io.github.fredleonam.droidproof.model.AndroidArtifactIdentity
import io.github.fredleonam.droidproof.model.AndroidArtifactType
import io.github.fredleonam.droidproof.model.AnimationConfiguration
import io.github.fredleonam.droidproof.model.ArtifactBindingStatus
import io.github.fredleonam.droidproof.model.ArtifactBindingSummary
import io.github.fredleonam.droidproof.model.BundleId
import io.github.fredleonam.droidproof.model.BundleRelativePath
import io.github.fredleonam.droidproof.model.ControlledClock
import io.github.fredleonam.droidproof.model.DeviceInformation
import io.github.fredleonam.droidproof.model.DroidProofVersion
import io.github.fredleonam.droidproof.model.EnvironmentContract
import io.github.fredleonam.droidproof.model.EventId
import io.github.fredleonam.droidproof.model.EventSource
import io.github.fredleonam.droidproof.model.EvidenceBundleManifest
import io.github.fredleonam.droidproof.model.EvidenceBundleManifestV3
import io.github.fredleonam.droidproof.model.EvidenceCompleteness
import io.github.fredleonam.droidproof.model.EvidenceFileRole
import io.github.fredleonam.droidproof.model.EvidenceReference
import io.github.fredleonam.droidproof.model.ExecutionStatus
import io.github.fredleonam.droidproof.model.ExecutionSummary
import io.github.fredleonam.droidproof.model.ObservedExecutionEnvironment
import io.github.fredleonam.droidproof.model.ObservedValue
import io.github.fredleonam.droidproof.model.Orientation
import io.github.fredleonam.droidproof.model.RequestedExecutionConfiguration
import io.github.fredleonam.droidproof.model.ScenarioId
import io.github.fredleonam.droidproof.model.ScenarioIdentity
import io.github.fredleonam.droidproof.model.ScenarioVerdict
import io.github.fredleonam.droidproof.model.Sha256
import io.github.fredleonam.droidproof.model.TimelineEvent
import io.github.fredleonam.droidproof.model.UtcTimestamp
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.jupiter.api.io.TempDir
import java.net.URI
import java.nio.file.Files
import java.nio.file.Path
import java.util.Base64
import kotlin.io.path.readBytes
import kotlin.io.path.readText
import kotlin.io.path.writeBytes
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class EvidenceReportGeneratorTest {
    @TempDir
    lateinit var directory: Path

    private val generator = EvidenceReportGenerator()

    @Test
    fun `schema v3 report renders stored execution artifact environment timeline inventory and local links`() {
        val bundle = writeV3Bundle("valid")
        val report = directory.resolve("reports/report.html")

        val result = generator.generate(bundle, report)
        val html = report.readText()

        assertTrue(result.verification.isValid)
        assertEquals(report.toAbsolutePath(), result.output)
        assertContainsAll(
            html,
            "Bundle verification passed",
            "Schema version</dt><dd>3",
            "smoke-report-run",
            "smoke-report",
            "COMPLETED",
            "PASSED",
            "COMPLETE",
            "2026-09-13T10:15:30Z",
            "0.1.0-SNAPSHOT",
            "MATCHED_BEFORE_AND_AFTER",
            "emulator-5554",
            "io.example.report",
            "Primary user only</dt><dd>Yes",
            "Replace existing</dt><dd>No",
            "observed/fingerprint",
            "Unavailable: Locale &lt;not observed&gt;",
            "scenario.step.assert",
            "state &amp; result",
            "Network",
            "POST",
            "/orders",
            "Request contract",
            "MATCHED",
            "Request bytes",
            "27",
            "Request SHA-256",
            "0123456789abcdef",
            "network/exchanges/001.json",
            "screenshots/display.png",
            "image/png",
            "execution/result.json",
            "SHA-256 consistency does not authenticate the evidence producer",
        )
        assertTrue(html.contains("class=\"badge status-passed\">PASSED"))
        assertTrue(html.contains("<img"))
        assertTrue(html.contains("src=\""))
        assertFalse(html.contains(directory.toAbsolutePath().toString()))

        val localTargets = Regex("(?:href|src)=\"([^\"]+)\"").findAll(html).map { it.groupValues[1] }.toList()
        assertTrue(localTargets.isNotEmpty())
        localTargets.forEach { target ->
            assertFalse(target.startsWith("/"))
            assertFalse(target.startsWith("file:"))
            val decodedAttribute = target.replace("&amp;", "&").replace("&#39;", "'")
            assertTrue(
                Files.exists(report.parent.resolve(URI(decodedAttribute).path).normalize()),
                "Missing linked target: $target",
            )
        }
    }

    @Test
    fun `all bundle-derived HTML-sensitive values are escaped`() {
        val bundle = writeV3Bundle("escaping", hostile = true)
        val report = directory.resolve("escaped.html")

        generator.generate(bundle, report)
        val html = report.readText()

        assertContainsAll(
            html,
            "io.example.&lt;script&gt;&amp;&quot;&#39;",
            "event.&lt;script&gt;",
            "&lt;img src=x onerror=alert(1)&gt; &amp; &quot;quoted&quot; &#39;value&#39;",
            "Unavailable: Locale &lt;not observed&gt; &amp; &quot;unknown&quot; &#39;reason&#39;",
            "attachments/a&amp;b&#39;.txt",
            "POST&lt;script&gt;",
            "/orders?x=&lt;script&gt;&amp;y=&#39;value&#39;",
        )
        assertFalse(html.contains("<script>"))
        assertFalse(html.contains("<img src=x onerror"))
    }

    @Test
    fun `tampered evidence produces a diagnostic-only failure report with hash issue`() {
        val bundle = writeV3Bundle("tampered")
        bundle.resolve("screenshots/display.png").writeBytes(byteArrayOf(9, 8, 7, 6))
        val report = directory.resolve("tampered.html")

        val result = generator.generate(bundle, report)
        val html = report.readText()

        assertFalse(result.verification.isValid)
        assertTrue(result.verification.errors.any { it.code == VerificationIssueCode.SHA256_MISMATCH })
        assertContainsAll(html, "Bundle verification failed", "SHA256_MISMATCH", "screenshots/display.png")
        assertFalse(html.contains("<img"))
        assertFalse(html.contains("href=\""))
        assertFalse(html.contains("smoke-report-run"))
    }

    @Test
    fun `tampered network evidence produces only the limited diagnostic report`() {
        val bundle = writeV3Bundle("tampered-network")
        bundle.resolve("network/exchanges/001.json").writeText("tampered\n")
        val report = directory.resolve("tampered-network.html")

        val result = generator.generate(bundle, report)
        val html = report.readText()

        assertFalse(result.verification.isValid)
        assertTrue(result.verification.errors.any { it.code == VerificationIssueCode.SHA256_MISMATCH })
        assertContainsAll(html, "Bundle verification failed", "network/exchanges/001.json")
        assertFalse(html.contains("<h2>Network</h2>"))
        assertFalse(html.contains("/orders"))
    }

    @Test
    fun `verified request contract mismatch is explained without rendering body bytes`() {
        val bundle =
            writeV3Bundle(
                "request-mismatch",
                requestContractOutcome = "MISMATCHED",
                requestContractIssues = "BODY_SHA256_MISMATCH",
            )
        val report = directory.resolve("request-mismatch.html")

        generator.generate(bundle, report)
        val html = report.readText()

        assertContainsAll(html, "MISMATCHED", "BODY_SHA256_MISMATCH", "Request bytes", "Request SHA-256")
        assertFalse(html.contains("DroidProof42"))
        assertFalse(html.contains("customer"))
    }

    @Test
    fun `execution badges preserve failed error cancelled and not-evaluated states`() {
        val failed =
            reportFor(
                writeV3Bundle(
                    "failed-state",
                    status = ExecutionStatus.COMPLETED,
                    verdict = ScenarioVerdict.FAILED,
                    completeness = EvidenceCompleteness.COMPLETE,
                ),
                "failed-state.html",
            )
        val error =
            reportFor(
                writeV3Bundle(
                    "error-state",
                    status = ExecutionStatus.ERROR,
                    verdict = ScenarioVerdict.NOT_EVALUATED,
                    completeness = EvidenceCompleteness.PARTIAL,
                ),
                "error-state.html",
            )
        val cancelled =
            reportFor(
                writeV3Bundle(
                    "cancelled-state",
                    status = ExecutionStatus.CANCELLED,
                    verdict = ScenarioVerdict.NOT_EVALUATED,
                    completeness = EvidenceCompleteness.PARTIAL,
                ),
                "cancelled-state.html",
            )

        assertTrue(failed.contains("class=\"badge status-failed\">FAILED"))
        assertTrue(error.contains("class=\"badge status-error\">ERROR"))
        assertTrue(error.contains("class=\"badge status-not-evaluated\">NOT_EVALUATED"))
        assertTrue(cancelled.contains("class=\"badge status-cancelled\">CANCELLED"))
    }

    @Test
    fun `reporting is read-only deterministic and rejects output inside bundle`() {
        val bundle = writeV3Bundle("read-only")
        val before = snapshot(bundle)
        val report = directory.resolve("outside/report.html")

        generator.generate(bundle, report)
        val first = report.readBytes()
        generator.generate(bundle, report)

        assertTrue(first.contentEquals(report.readBytes()))
        assertEquals(before, snapshot(bundle))
        val inside = bundle.resolve("report.html")
        assertFailsWith<IllegalArgumentException> { generator.generate(bundle, inside) }
        assertFalse(Files.exists(inside))
        assertTrue(EvidenceBundleVerifier().verify(bundle).isValid)
    }

    @Test
    fun `schema v2 synthetic sample format produces a verified report`() {
        val evidence = source("network.json", "{\"status\":201}\n")
        val bundle = directory.resolve("schema-v2")
        EvidenceBundleWriter().write(
            EvidenceBundleRequest(
                manifest = v2Manifest(),
                events =
                    listOf(
                        TimelineEvent(
                            EventId("network-response"),
                            UtcTimestamp("2026-09-04T12:00:08Z"),
                            EventSource.MOCK_SERVER,
                            "http.response",
                            mapOf("status" to "201"),
                            listOf(EvidenceReference(BundleRelativePath("network/response.json"), "application/json")),
                        ),
                    ),
                evidenceFiles =
                    listOf(
                        EvidenceFileInput(
                            evidence,
                            BundleRelativePath("network/response.json"),
                            "application/json",
                            EvidenceFileRole.NETWORK,
                        ),
                    ),
            ),
            bundle,
        )
        val report = directory.resolve("schema-v2.html")

        val result = generator.generate(bundle, report)
        val html = report.readText()

        assertTrue(result.verification.isValid)
        assertContainsAll(
            html,
            "Schema version</dt><dd>2",
            "checkout-offline-retry",
            "Recorded environment contract",
            "http.response",
            "network/response.json",
        )
    }

    @Test
    fun `schema v1 is clearly legacy and does not claim evidence-file integrity`() {
        val bundle = directory.resolve("schema-v1")
        EvidenceBundleWriter().write(EvidenceBundleRequest(v2Manifest(), emptyList()), bundle)
        val parsed = evidenceJson.parseToJsonElement(bundle.resolve("manifest.json").readText()) as JsonObject
        bundle.resolve("manifest.json").writeText(
            evidenceJson.encodeToString(
                JsonObject.serializer(),
                JsonObject((parsed - "evidenceFiles") + ("schemaVersion" to JsonPrimitive(1))),
            ) + "\n",
        )
        val report = directory.resolve("schema-v1.html")

        val result = generator.generate(bundle, report)
        val html = report.readText()

        assertTrue(result.verification.isValid)
        assertContainsAll(html, "Legacy / limited report", "FILE_INTEGRITY_UNAVAILABLE", "file integrity was not verified")
        assertFalse(html.contains("Evidence inventory</h2>"))
    }

    @Test
    fun `command requires bundle property and fails after writing an invalid diagnostic report`() {
        val missing = assertFailsWith<IllegalArgumentException> { main(arrayOf("", directory.resolve("missing.html").toString())) }
        assertTrue(missing.message.orEmpty().contains("droidproof.bundlePath"))

        val bundle = writeV3Bundle("command-invalid")
        bundle.resolve("execution/result.json").writeText("tampered\n")
        val report = directory.resolve("command-invalid.html")
        assertFailsWith<IllegalStateException> { main(arrayOf(bundle.toString(), report.toString())) }
        assertTrue(report.readText().contains("Bundle verification failed"))
    }

    private fun writeV3Bundle(
        name: String,
        hostile: Boolean = false,
        status: ExecutionStatus = ExecutionStatus.COMPLETED,
        verdict: ScenarioVerdict = ScenarioVerdict.PASSED,
        completeness: EvidenceCompleteness = EvidenceCompleteness.COMPLETE,
        requestContractOutcome: String = "MATCHED",
        requestContractIssues: String = "",
    ): Path {
        val result = source("$name-result.json", "{}\n")
        val binding = source("$name-binding.json", "{}\n")
        val capture = source("$name-capture.json", "{}\n")
        val hierarchy = source("$name-hierarchy.xml", "<hierarchy/>\n")
        val screenshot = directory.resolve("$name-screenshot.png").also { it.writeBytes(ONE_PIXEL_PNG) }
        val attachment = source("$name-attachment.txt", "attachment\n")
        val network = source("$name-network.json", "{\"sequence\":1}\n")
        val digest = Sha256("a".repeat(64))
        val packageName = if (hostile) "io.example.<script>&\"'" else "io.example.report"
        val reason =
            if (hostile) {
                "Locale <not observed> & \"unknown\" 'reason'"
            } else {
                "Locale <not observed>"
            }
        val manifest =
            EvidenceBundleManifestV3(
                schemaVersion = 3,
                bundleId = BundleId("smoke-report-run"),
                createdAt = UtcTimestamp("2026-09-13T10:15:30Z"),
                artifact = AndroidArtifactIdentity(AndroidArtifactType.APK, digest),
                artifactBinding =
                    ArtifactBindingSummary(
                        packageName = packageName,
                        inputApkSha256 = digest,
                        installedApkSha256Before = Sha256("b".repeat(64)),
                        installedApkSha256After = Sha256("c".repeat(64)),
                        status = ArtifactBindingStatus.MATCHED_BEFORE_AND_AFTER,
                        detailPath = BundleRelativePath("execution/artifact-binding.json"),
                    ),
                scenario = ScenarioIdentity(ScenarioId("smoke-report"), Sha256("d".repeat(64))),
                requestedConfiguration =
                    RequestedExecutionConfiguration(
                        deviceSerial = "emulator-5554",
                        packageName = packageName,
                        primaryUserOnly = true,
                        replaceExisting = false,
                    ),
                observedEnvironment =
                    ObservedExecutionEnvironment(
                        buildFingerprint = ObservedValue(value = "observed/fingerprint"),
                        apiLevel = ObservedValue(value = "35"),
                        locale = ObservedValue(unavailableReason = reason),
                        orientation = ObservedValue(unavailableReason = "Orientation was not observed."),
                        animations = ObservedValue(unavailableReason = "Animation scales were not observed."),
                        randomSeed = ObservedValue(unavailableReason = "No random seed was observed."),
                        controlledClock = ObservedValue(unavailableReason = "No controlled clock was observed."),
                    ),
                execution =
                    ExecutionSummary(
                        status = status,
                        verdict = verdict,
                        evidenceCompleteness = completeness,
                        resultPath = BundleRelativePath("execution/result.json"),
                        assertionHierarchyPath = BundleRelativePath("ui/hierarchy.xml"),
                    ),
                droidProofVersion = DroidProofVersion("0.1.0-SNAPSHOT"),
            )
        val eventType = if (hostile) "event.<script>" else "scenario.step.assert"
        val attributeValue =
            if (hostile) {
                "<img src=x onerror=alert(1)> & \"quoted\" 'value'"
            } else {
                "state & result"
            }
        return directory.resolve("$name-bundle").also { bundle ->
            EvidenceBundleWriter().write(
                EvidenceBundleRequestV3(
                    manifest,
                    listOf(
                        TimelineEvent(
                            EventId("assertion-event"),
                            UtcTimestamp("2026-09-13T10:15:31Z"),
                            EventSource.HOST,
                            eventType,
                            mapOf("detail" to attributeValue),
                            listOf(
                                EvidenceReference(BundleRelativePath("ui/hierarchy.xml"), "application/xml"),
                                EvidenceReference(BundleRelativePath("screenshots/display.png"), "image/png"),
                            ),
                        ),
                        TimelineEvent(
                            EventId("network-001"),
                            UtcTimestamp("2026-09-13T10:15:31Z"),
                            EventSource.MOCK_SERVER,
                            "http.exchange",
                            mapOf(
                                "serverSequence" to "1",
                                "method" to if (hostile) "POST<script>" else "POST",
                                "path" to if (hostile) "/orders?x=<script>&y='value'" else "/orders",
                                "requestContractOutcome" to requestContractOutcome,
                                "requestContractIssues" to requestContractIssues,
                                "requestBytes" to "27",
                                "requestSha256" to "0123456789abcdef",
                                "responseStatus" to "201",
                            ),
                            listOf(EvidenceReference(BundleRelativePath("network/exchanges/001.json"), "application/json")),
                        ),
                    ),
                    listOf(
                        EvidenceFileInput(
                            result,
                            BundleRelativePath("execution/result.json"),
                            "application/json",
                            EvidenceFileRole.TEST_RESULT,
                        ),
                        EvidenceFileInput(
                            binding,
                            BundleRelativePath("execution/artifact-binding.json"),
                            "application/json",
                            EvidenceFileRole.ATTACHMENT,
                        ),
                        EvidenceFileInput(
                            capture,
                            BundleRelativePath("capture/capture.json"),
                            "application/json",
                            EvidenceFileRole.ATTACHMENT,
                        ),
                        EvidenceFileInput(hierarchy, BundleRelativePath("ui/hierarchy.xml"), "application/xml", EvidenceFileRole.SEMANTICS),
                        EvidenceFileInput(
                            screenshot,
                            BundleRelativePath("screenshots/display.png"),
                            "image/png",
                            EvidenceFileRole.SCREENSHOT,
                        ),
                        EvidenceFileInput(
                            attachment,
                            BundleRelativePath("attachments/a&b'.txt"),
                            "text/plain",
                            EvidenceFileRole.ATTACHMENT,
                        ),
                        EvidenceFileInput(
                            network,
                            BundleRelativePath("network/exchanges/001.json"),
                            "application/json",
                            EvidenceFileRole.NETWORK,
                        ),
                    ),
                ),
                bundle,
            )
        }
    }

    private fun reportFor(
        bundle: Path,
        name: String,
    ): String {
        val report = directory.resolve(name)
        generator.generate(bundle, report)
        return report.readText()
    }

    private fun v2Manifest() =
        EvidenceBundleManifest(
            schemaVersion = 2,
            bundleId = BundleId("proof-checkout-offline-retry"),
            createdAt = UtcTimestamp("2026-09-04T12:00:00Z"),
            artifact = AndroidArtifactIdentity(AndroidArtifactType.APK, Sha256("e".repeat(64))),
            scenario = ScenarioIdentity(ScenarioId("checkout-offline-retry"), Sha256("f".repeat(64))),
            environment =
                EnvironmentContract(
                    DeviceInformation("synthetic/fingerprint", 35),
                    "en-US",
                    Orientation.PORTRAIT,
                    AnimationConfiguration(0.0, 0.0, 0.0),
                    42,
                    ControlledClock(UtcTimestamp("2026-09-04T12:00:00Z"), true),
                ),
            droidProofVersion = DroidProofVersion("0.2.0"),
        )

    private fun source(
        name: String,
        content: String,
    ): Path = directory.resolve(name).also { it.writeText(content) }

    private fun snapshot(root: Path): Map<String, List<Byte>> =
        Files.walk(root).use { paths ->
            paths
                .iterator()
                .asSequence()
                .filter(Files::isRegularFile)
                .associate { root.relativize(it).toString() to it.readBytes().toList() }
        }

    private fun assertContainsAll(
        text: String,
        vararg expected: String,
    ) {
        expected.forEach { assertTrue(text.contains(it), "Expected report to contain: $it") }
    }

    private companion object {
        val ONE_PIXEL_PNG: ByteArray =
            Base64.getDecoder().decode(
                "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mNk+A8AAQUBAScY42YAAAAASUVORK5CYII=",
            )
    }
}
