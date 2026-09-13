package io.github.fredleonam.droidproof.host

import io.github.fredleonam.droidproof.device.CaptureDocument
import io.github.fredleonam.droidproof.device.CaptureFile
import io.github.fredleonam.droidproof.device.CaptureResult
import io.github.fredleonam.droidproof.device.CaptureStatus
import io.github.fredleonam.droidproof.device.CollectedFile
import io.github.fredleonam.droidproof.device.CollectionIssue
import io.github.fredleonam.droidproof.device.CollectionIssueCode
import io.github.fredleonam.droidproof.device.CollectionOutcome
import io.github.fredleonam.droidproof.device.DeviceIdentity
import io.github.fredleonam.droidproof.device.ObservedField
import io.github.fredleonam.droidproof.evidence.EvidenceBundleVerifier
import io.github.fredleonam.droidproof.evidence.Sha256Calculator
import io.github.fredleonam.droidproof.evidence.evidenceJson
import io.github.fredleonam.droidproof.model.BundleRelativePath
import io.github.fredleonam.droidproof.model.DroidProofVersion
import io.github.fredleonam.droidproof.model.EvidenceBundleManifestV3
import io.github.fredleonam.droidproof.model.EvidenceCompleteness
import io.github.fredleonam.droidproof.model.EvidenceFileRole
import io.github.fredleonam.droidproof.model.ExecutionStatus
import io.github.fredleonam.droidproof.model.ScenarioVerdict
import io.github.fredleonam.droidproof.model.TimelineDocument
import kotlinx.serialization.decodeFromString
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SmokeCoordinatorTest {
    @TempDir
    lateinit var directory: Path
    private val wallClock = Clock.fixed(Instant.parse("2026-09-12T12:00:00Z"), ZoneOffset.UTC)

    @Test
    fun `passing assertion writes a complete integrity-valid artifact-bound bundle`() {
        val device = FakeSmokeDevice().apply { dumps += DumpResponse(FakeSmokeDevice.MATCHING_XML) }

        val result = coordinator(device, completeCapture()).run(request("passing"))
        val bundle = requireNotNull(result.output)

        assertTrue(result.isSuccessful)
        assertEquals(ExecutionStatus.COMPLETED, result.document?.status)
        assertEquals(ScenarioVerdict.PASSED, result.document?.verdict)
        assertEquals(EvidenceCompleteness.COMPLETE, result.document?.evidenceCompleteness)
        assertTrue(EvidenceBundleVerifier().verify(bundle).isValid)
        assertContentEquals(Files.readAllBytes(scenario()), Files.readAllBytes(bundle.resolve("scenario/scenario.json")))
        assertTrue(Files.readString(bundle.resolve("manifest.json")).contains("\"schemaVersion\": 3"))
        val manifest = evidenceJson.decodeFromString<EvidenceBundleManifestV3>(Files.readString(bundle.resolve("manifest.json")))
        assertEquals(Sha256Calculator.calculate(bundle.resolve("scenario/scenario.json")), manifest.scenario.dataSha256)
        assertFalse(Files.exists(bundle.parent.resolve("work")))
        assertFalse(Files.exists(bundle.resolve("input.apk")))
    }

    @Test
    fun `failed assertion remains completed and integrity-valid while task result fails`() {
        val device = FakeSmokeDevice()
        val clock = FakeMonotonicClock()
        val assertion = assertionRunner(device, clock)

        val result = coordinator(device, completeCapture(), clock, assertion).run(request("failing", deadline = 250))

        assertFalse(result.isSuccessful)
        assertTrue(result.bundleIntegrityValid)
        assertEquals(ExecutionStatus.COMPLETED, result.document?.status)
        assertEquals(ScenarioVerdict.FAILED, result.document?.verdict)
        assertEquals(EvidenceCompleteness.COMPLETE, result.document?.evidenceCompleteness)
    }

    @Test
    fun `successful assertion with missing screenshot is passed partial and nonzero`() {
        val device = FakeSmokeDevice().apply { dumps += DumpResponse(FakeSmokeDevice.MATCHING_XML) }

        val result = coordinator(device, partialCapture()).run(request("partial"))

        assertFalse(result.isSuccessful)
        assertEquals(ExecutionStatus.COMPLETED, result.document?.status)
        assertEquals(ScenarioVerdict.PASSED, result.document?.verdict)
        assertEquals(EvidenceCompleteness.PARTIAL, result.document?.evidenceCompleteness)
        assertTrue(result.bundleIntegrityValid)
    }

    @Test
    fun `cancellation stops later device mutations and preserves a partial bundle`() {
        val device =
            FakeSmokeDevice().apply {
                preflightResult = DeviceCall(failure = DeviceFailureKind.CANCELLED, detail = "cancelled")
            }

        val result = coordinator(device, partialCapture()).run(request("cancelled"))

        assertEquals(listOf("preflight:emulator-5554"), device.operations)
        assertEquals(ExecutionStatus.CANCELLED, result.document?.status)
        assertEquals(ScenarioVerdict.NOT_EVALUATED, result.document?.verdict)
        assertTrue(result.bundleIntegrityValid)
    }

    @Test
    fun `overall timeout prevents subsequent device mutations`() {
        val clock = FakeMonotonicClock()
        val device =
            object : FakeSmokeDevice() {
                override fun packagePaths(
                    serial: String,
                    packageName: String,
                    timeoutMillis: Long,
                ): DeviceCall<InstalledPackagePaths> {
                    val result = super.packagePaths(serial, packageName, timeoutMillis)
                    clock.advanceMillis(30_000)
                    return result
                }
            }

        val result = coordinator(device, partialCapture(), clock).run(request("timeout", deadline = 100))

        assertEquals(ExecutionStatus.ERROR, result.document?.status)
        assertTrue(device.operations.none { it.startsWith("install:") || it.startsWith("launch:") })
        assertTrue(result.bundleIntegrityValid)
    }

    @Test
    fun `failed post-run identity makes the observation not evaluated`() {
        val device =
            object : FakeSmokeDevice() {
                private var pathQueries = 0

                override fun packagePaths(
                    serial: String,
                    packageName: String,
                    timeoutMillis: Long,
                ): DeviceCall<InstalledPackagePaths> {
                    pathQueries++
                    if (pathQueries == 3) installedBytes = listOf("changed after capture".toByteArray())
                    return super.packagePaths(serial, packageName, timeoutMillis)
                }
            }.apply { dumps += DumpResponse(FakeSmokeDevice.MATCHING_XML) }

        val result = coordinator(device, completeCapture()).run(request("identity-change"))

        assertEquals(ExecutionStatus.ERROR, result.document?.status)
        assertEquals(ScenarioVerdict.NOT_EVALUATED, result.document?.verdict)
        assertTrue(result.bundleIntegrityValid)
    }

    @Test
    fun `finalization failure retains the original execution error`() {
        val device =
            FakeSmokeDevice().apply {
                launchResult = DeviceCall(failure = DeviceFailureKind.COMMAND, detail = "original launch failure")
            }
        val failingPublisher = BundlePublisher { _, _ -> error("publication failed") }

        val result =
            SmokeCoordinator(
                device,
                partialCapture(),
                publisher = failingPublisher,
                wallClock = wallClock,
                monotonicClock = FakeMonotonicClock(),
                idSource = { "run-finalization" },
            ).run(request("finalization"))

        assertEquals("original launch failure", result.document?.primaryError)
        assertEquals("publication failed", result.document?.finalizationError)
        assertTrue(Files.isRegularFile(requireNotNull(result.diagnostic)))
    }

    @Test
    fun `injected clocks and identifiers produce identical documents`() {
        fun execute(root: String): Path {
            val device = FakeSmokeDevice().apply { dumps += DumpResponse(FakeSmokeDevice.MATCHING_XML) }
            return requireNotNull(coordinator(device, completeCapture()).run(request(root)).output)
        }
        val first = execute("deterministic-one")
        val second = execute("deterministic-two")

        for (relative in listOf("manifest.json", "timeline.json", "execution/result.json", "execution/artifact-binding.json")) {
            assertContentEquals(Files.readAllBytes(first.resolve(relative)), Files.readAllBytes(second.resolve(relative)), relative)
        }
    }

    @Test
    fun `interactive execution orders tap observation capture and final identity with bound step evidence`() {
        val device = interactiveDevice()
        val capture =
            DeviceEvidenceCapture { request ->
                device.operations += "capture"
                completeCapture().capture(request)
            }
        val request = interactiveRequest("interactive")
        val result = coordinator(device, capture).run(request)
        val bundle = requireNotNull(result.output)
        assertTrue(result.isSuccessful)
        val operations = device.operations.map { it.substringBefore(':') }
        assertEquals(
            listOf("preflight", "paths", "install", "paths", "pull", "launch", "dump", "tap", "dump", "capture", "paths", "pull"),
            operations,
        )
        assertTrue("tap:emulator-5554:20:40" in device.operations)
        assertEquals(listOf(StepStatus.SUCCEEDED, StepStatus.SUCCEEDED), result.document?.steps?.map { it.status })
        val manifest = evidenceJson.decodeFromString<EvidenceBundleManifestV3>(Files.readString(bundle.resolve("manifest.json")))
        for (path in listOf("ui/steps/001-tap-before.xml", "ui/steps/002-assert.xml")) {
            val descriptor = manifest.evidenceFiles.single { it.path.value == path }
            assertEquals(Sha256Calculator.calculate(bundle.resolve(path)), descriptor.sha256)
            assertEquals(Files.size(bundle.resolve(path)), descriptor.byteSize)
            assertTrue(Files.readString(bundle.resolve("timeline.json")).contains(path))
        }
        assertContentEquals(Files.readAllBytes(request.scenarioPath), Files.readAllBytes(bundle.resolve("scenario/scenario.json")))
        assertFalse(Files.readString(bundle.resolve("execution/result.json")).contains(directory.toString()))
        assertTrue(EvidenceBundleVerifier().verify(bundle).isValid)
    }

    @Test
    fun `interactive nonmatch is failed complete and integrity valid`() {
        val device = interactiveDevice().apply { dumps.removeLast() }
        val result = coordinator(device, completeCapture()).run(interactiveRequest("interactive-failed"))
        assertEquals(ExecutionStatus.COMPLETED, result.document?.status)
        assertEquals(ScenarioVerdict.FAILED, result.document?.verdict)
        assertEquals(StepStatus.ASSERTION_FAILED, result.document?.steps?.last()?.status)
        assertEquals(EvidenceCompleteness.COMPLETE, result.document?.evidenceCompleteness)
        assertTrue(result.bundleIntegrityValid)
    }

    @Test
    fun `tap command failure retains hierarchy skips subsequent steps and is not evaluated`() {
        val device = interactiveDevice().apply { tapResult = DeviceCall(failure = DeviceFailureKind.COMMAND, detail = "private stderr") }
        val result = coordinator(device, completeCapture()).run(interactiveRequest("tap-failed", repeatTap = true))
        assertEquals(ExecutionStatus.ERROR, result.document?.status)
        assertEquals(ScenarioVerdict.NOT_EVALUATED, result.document?.verdict)
        assertEquals(listOf(StepStatus.ERROR, StepStatus.SKIPPED, StepStatus.SKIPPED), result.document?.steps?.map { it.status })
        assertEquals(1, device.operations.count { it.startsWith("tap:") })
        assertEquals(1, device.operations.count { it.startsWith("dump:") })
        val bundle = requireNotNull(result.output)
        assertTrue(Files.exists(bundle.resolve("ui/steps/001-tap-before.xml")))
        assertFalse(Files.readString(bundle.resolve("execution/result.json")).contains("private stderr"))
        assertTrue(result.bundleIntegrityValid)
    }

    @Test
    fun `cancellation or overall deadline after hierarchy prevents tap and later steps`() {
        for (cancel in listOf(true, false)) {
            var cancelled = false
            val clock = FakeMonotonicClock()
            val device =
                object : FakeSmokeDevice() {
                    override fun dumpHierarchy(
                        serial: String,
                        remotePath: String,
                        destination: Path,
                        timeoutMillis: Long,
                        outputLimitBytes: Long,
                    ): DeviceCall<Unit> {
                        val result = super.dumpHierarchy(serial, remotePath, destination, timeoutMillis, outputLimitBytes)
                        if (cancel) cancelled = true else clock.advanceMillis(4_000_000)
                        return result
                    }
                }.apply { dumps += DumpResponse(TAP_XML) }
            val result =
                SmokeCoordinator(
                    device,
                    completeCapture(),
                    wallClock = wallClock,
                    monotonicClock = clock,
                    cancellation = CancellationSignal { cancelled },
                    idSource = { "run-boundary" },
                ).run(interactiveRequest("boundary-$cancel", repeatTap = true))
            assertEquals(if (cancel) ExecutionStatus.CANCELLED else ExecutionStatus.ERROR, result.document?.status)
            assertEquals(ScenarioVerdict.NOT_EVALUATED, result.document?.verdict)
            assertTrue(device.operations.none { it.startsWith("tap:") })
            assertEquals(StepStatus.SKIPPED, result.document?.steps?.last()?.status)
            assertTrue(result.bundleIntegrityValid)
        }
    }

    @Test
    fun `tap collection and resolution failures never issue input and publish truthful partial evidence`() {
        for ((index, response) in listOf(
            DumpResponse(failure = DeviceFailureKind.DISCONNECTED),
            DumpResponse("<hierarchy/>".toByteArray()),
            DumpResponse("<hierarchy>".toByteArray()),
            DumpResponse(TAP_XML.toString(Charsets.UTF_8).replace("[10,20][30,60]", "invalid").toByteArray()),
        ).withIndex()) {
            val device = FakeSmokeDevice().apply { dumps += response }
            val result = coordinator(device, completeCapture()).run(interactiveRequest("resolution-$index"))
            assertEquals(ExecutionStatus.ERROR, result.document?.status)
            assertEquals(ScenarioVerdict.NOT_EVALUATED, result.document?.verdict)
            assertEquals(EvidenceCompleteness.PARTIAL, result.document?.evidenceCompleteness)
            assertTrue(device.operations.none { it.startsWith("tap:") })
            assertTrue(result.bundleIntegrityValid)
        }
    }

    @Test
    fun `cancellation after a dispatched tap prevents the next tap`() {
        var cancelled = false
        val device =
            object : FakeSmokeDevice() {
                override fun tap(
                    serial: String,
                    coordinates: TapCoordinates,
                    timeoutMillis: Long,
                ): DeviceCall<Unit> {
                    val result = super.tap(serial, coordinates, timeoutMillis)
                    cancelled = true
                    return result
                }
            }.apply { dumps += DumpResponse(TAP_XML) }
        val result =
            SmokeCoordinator(
                device,
                completeCapture(),
                wallClock = wallClock,
                cancellation = CancellationSignal { cancelled },
                idSource = { "cancel-after-tap" },
            ).run(interactiveRequest("after-tap", repeatTap = true))
        assertEquals(ExecutionStatus.CANCELLED, result.document?.status)
        assertEquals(ScenarioVerdict.NOT_EVALUATED, result.document?.verdict)
        assertEquals(1, device.operations.count { it.startsWith("tap:") })
        assertEquals(listOf(StepStatus.CANCELLED, StepStatus.SKIPPED, StepStatus.SKIPPED), result.document?.steps?.map { it.status })
        assertTrue(result.bundleIntegrityValid)
    }

    @Test
    fun `early assertion nonmatch stops later mutations and is never overwritten by a pass`() {
        val request = interactiveRequest("early-failure")
        val text = INTERACTIVE_SCENARIO
        val assertion = text.substringAfter("},{").removeSuffix("]}")
        Files.writeString(request.scenarioPath, text.replace("\"steps\":[", "\"steps\":[{" + assertion + ","))
        val device = FakeSmokeDevice()
        val result = coordinator(device, completeCapture()).run(request)
        assertEquals(ScenarioVerdict.FAILED, result.document?.verdict)
        assertEquals(listOf(StepStatus.ASSERTION_FAILED, StepStatus.SKIPPED, StepStatus.SKIPPED), result.document?.steps?.map { it.status })
        assertTrue(device.operations.none { it.startsWith("tap:") })
        assertTrue(result.bundleIntegrityValid)
    }

    @Test
    fun `interactive timeline exposes ordered steps and documents remain deterministic`() {
        fun execute(name: String): Path =
            requireNotNull(
                coordinator(interactiveDevice(), completeCapture()).run(interactiveRequest(name)).output,
            )
        val first = execute("interactive-one")
        val second = execute("interactive-two")
        val timeline = evidenceJson.decodeFromString<TimelineDocument>(Files.readString(first.resolve("timeline.json")))
        assertEquals(
            listOf(
                "execution.preflight",
                "execution.artifact_binding",
                "execution.launch",
                "scenario.step.tap",
                "scenario.step.assert",
                "execution.assertion",
                "execution.capture",
                "execution.finalization",
            ),
            timeline.events.map { it.type },
        )
        for (path in listOf("manifest.json", "timeline.json", "execution/result.json")) {
            assertContentEquals(Files.readAllBytes(first.resolve(path)), Files.readAllBytes(second.resolve(path)))
        }
    }

    private fun interactiveDevice() =
        FakeSmokeDevice().apply {
            dumps += DumpResponse(TAP_XML)
            val completed =
                FakeSmokeDevice.MATCHING_XML.toString(Charsets.UTF_8)
                    .replace("DroidProof ready", "DroidProof action completed").toByteArray()
            dumps += DumpResponse(completed)
        }

    private fun interactiveRequest(
        name: String,
        repeatTap: Boolean = false,
    ): SmokeRunRequest {
        val scenario = directory.resolve("$name.json")
        val tap = """{"type":"tapUiNode","resourceId":"io.droidproof.smoke:id/action"},"""
        Files.writeString(scenario, if (repeatTap) INTERACTIVE_SCENARIO.replace(tap, tap + tap) else INTERACTIVE_SCENARIO)
        return request(name).copy(scenarioPath = scenario)
    }

    private fun coordinator(
        device: FakeSmokeDevice,
        capture: DeviceEvidenceCapture,
        monotonicClock: FakeMonotonicClock = FakeMonotonicClock(),
        assertion: UiAssertionRunner = assertionRunner(device, monotonicClock),
    ) = SmokeCoordinator(
        device,
        capture,
        wallClock = wallClock,
        monotonicClock = monotonicClock,
        cancellation = CancellationSignal { false },
        assertionRunner = assertion,
        idSource = { "run-001" },
    )

    private fun assertionRunner(
        device: FakeSmokeDevice,
        clock: FakeMonotonicClock,
    ): UiAssertionRunner {
        var id = 0
        return UiAssertionRunner(
            device,
            monotonicClock = clock,
            waiter = ScenarioWaiter(clock::advanceMillis),
            cancellation = CancellationSignal { false },
            idSource = { "hierarchy-${++id}" },
        )
    }

    private fun completeCapture(): DeviceEvidenceCapture =
        DeviceEvidenceCapture { request ->
            val captureDirectory = Files.createDirectories(request.outputRoot.resolve("capture-fixed"))
            Files.writeString(captureDirectory.resolve("capture.json"), "{\"captureSchemaVersion\":1}\n")
            val screenshot = captureDirectory.resolve("display.png").also { Files.write(it, byteArrayOf(1, 2, 3)) }
            val collected =
                CollectedFile(
                    screenshot,
                    BundleRelativePath("screenshots/display.png"),
                    "image/png",
                    EvidenceFileRole.SCREENSHOT,
                )
            CaptureResult(captureDirectory, captureDocument(CollectionOutcome.SUCCESS, listOf(collected)), listOf(collected))
        }

    private fun partialCapture(): DeviceEvidenceCapture =
        DeviceEvidenceCapture { request ->
            val captureDirectory = Files.createDirectories(request.outputRoot.resolve("capture-fixed"))
            Files.writeString(captureDirectory.resolve("capture.json"), "{\"captureSchemaVersion\":1}\n")
            CaptureResult(captureDirectory, captureDocument(CollectionOutcome.FAILED, emptyList()), emptyList())
        }

    private fun captureDocument(
        screenshot: CollectionOutcome,
        files: List<CollectedFile>,
    ) = CaptureDocument(
        captureId = "capture-fixed",
        hostStartedAt = wallClock.instant().toString(),
        hostEndedAt = wallClock.instant().toString(),
        device = DeviceIdentity("emulator-5554", "device"),
        metadata =
            mapOf(
                "buildFingerprint" to ObservedField("synthetic/fingerprint"),
                "apiLevel" to ObservedField("35"),
            ),
        status = if (screenshot == CollectionOutcome.SUCCESS) CaptureStatus.SUCCESS else CaptureStatus.FAILED,
        screenshot = screenshot,
        logcat = CollectionOutcome.NOT_REQUESTED,
        requestedPid = null,
        files = files.map { CaptureFile(it.destination, it.mediaType, it.role) },
        issues =
            if (screenshot == CollectionOutcome.SUCCESS) {
                emptyList()
            } else {
                listOf(CollectionIssue(CollectionIssueCode.NONZERO_EXIT, "screenshot", "screenshot failed"))
            },
    )

    private fun request(
        name: String,
        deadline: Long = 1000,
    ) = SmokeRunRequest(
        apkPath = apk(name),
        scenarioPath = scenario(deadline),
        deviceSerial = "emulator-5554",
        outputRoot = directory.resolve(name),
        droidProofVersion = DroidProofVersion("0.1.0-SNAPSHOT"),
        commandTimeoutMillis = 1000,
    )

    private fun apk(name: String): Path = directory.resolve("$name.apk").also { Files.writeString(it, "apk bytes") }

    private fun scenario(deadline: Long = 1000): Path =
        directory.resolve("scenario-$deadline.json").also {
            Files.writeString(
                it,
                """{"schemaVersion":1,"scenarioId":"smoke-ready","expectedPackage":"io.droidproof.smoke",""" +
                    """"launchComponent":"io.droidproof.smoke/io.droidproof.smoke.MainActivity",""" +
                    """"expectedUi":{"resourceId":"io.droidproof.smoke:id/status","text":"DroidProof ready"},""" +
                    """"assertionDeadlineMillis":$deadline,"pollIntervalMillis":100}""",
            )
        }
}

private val TAP_XML =
    (
        """<hierarchy><node package="io.droidproof.smoke" resource-id="io.droidproof.smoke:id/action" """ +
            """bounds="[10,20][30,60]"/></hierarchy>"""
    ).toByteArray()
