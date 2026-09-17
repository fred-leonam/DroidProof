package io.github.fredleonam.droidproof.host

import io.github.fredleonam.droidproof.device.CaptureDocument
import io.github.fredleonam.droidproof.device.CaptureFile
import io.github.fredleonam.droidproof.device.CaptureResult
import io.github.fredleonam.droidproof.device.CaptureStatus
import io.github.fredleonam.droidproof.device.CollectedFile
import io.github.fredleonam.droidproof.device.CollectionOutcome
import io.github.fredleonam.droidproof.device.DeviceIdentity
import io.github.fredleonam.droidproof.device.ObservedField
import io.github.fredleonam.droidproof.evidence.EvidenceBundleVerifier
import io.github.fredleonam.droidproof.evidence.Sha256Calculator
import io.github.fredleonam.droidproof.evidence.evidenceJson
import io.github.fredleonam.droidproof.mockserver.HttpBodyObservation
import io.github.fredleonam.droidproof.mockserver.MockServerLimits
import io.github.fredleonam.droidproof.mockserver.MockServerPlan
import io.github.fredleonam.droidproof.mockserver.MockServerStarter
import io.github.fredleonam.droidproof.mockserver.ObservedHttpExchange
import io.github.fredleonam.droidproof.mockserver.RequestContractEvaluation
import io.github.fredleonam.droidproof.mockserver.RequestContractIssue
import io.github.fredleonam.droidproof.mockserver.RequestContractOutcome
import io.github.fredleonam.droidproof.mockserver.RunningMockServer
import io.github.fredleonam.droidproof.model.BundleRelativePath
import io.github.fredleonam.droidproof.model.DroidProofVersion
import io.github.fredleonam.droidproof.model.EmulatorCapabilityObservationV1
import io.github.fredleonam.droidproof.model.EnvironmentExecutionMode
import io.github.fredleonam.droidproof.model.EventSource
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
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class NetworkExecutionTest {
    @TempDir
    lateinit var directory: Path

    private val wallClock = Clock.fixed(Instant.parse("2026-09-14T12:00:00Z"), ZoneOffset.UTC)

    @Test
    fun `matching network and UI observations produce bound evidence and timeline events`() {
        val handle = FakeRunningServer(successfulExchanges())
        val device = networkDevice(matchingUi = true)

        val result = coordinator(device, handle).run(request("network-success"))
        val bundle = requireNotNull(result.output)

        assertTrue(result.isSuccessful)
        assertEquals(NetworkEvaluationOutcome.MATCHED, result.document?.network?.outcome)
        assertEquals(listOf(503, 201), result.document?.network?.exchanges?.map { it.responseStatus })
        assertTrue(handle.stopped)
        assertTrue(device.operations.any { it == "reverse:emulator-5554:38637:43210" })
        assertTrue(device.operations.any { it == "reverse-remove:emulator-5554:38637" })
        val manifest = evidenceJson.decodeFromString<EvidenceBundleManifestV3>(Files.readString(bundle.resolve("manifest.json")))
        val network = manifest.evidenceFiles.filter { it.role == EvidenceFileRole.NETWORK }
        assertEquals(listOf("network/exchanges/001.json", "network/exchanges/002.json"), network.map { it.path.value })
        network.forEach {
            assertEquals(Files.size(bundle.resolve(it.path.value)), it.byteSize)
            assertEquals(Sha256Calculator.calculate(bundle.resolve(it.path.value)), it.sha256)
        }
        val timeline = evidenceJson.decodeFromString<TimelineDocument>(Files.readString(bundle.resolve("timeline.json")))
        val events = timeline.events.filter { it.source == EventSource.MOCK_SERVER }
        assertEquals(listOf("1", "2"), events.map { it.attributes.getValue("serverSequence") })
        assertEquals(network.map { it.path }, events.map { it.evidence.single().path })
        assertTrue(EvidenceBundleVerifier().verify(bundle).isValid)
    }

    @Test
    fun `UI pass with a missing expected request is a completed behavioral failure`() {
        val result =
            coordinator(networkDevice(true), FakeRunningServer(successfulRequestContractExchanges().take(1)))
                .run(request("missing-request", REQUEST_CONTRACT_SCENARIO))

        assertEquals(ExecutionStatus.COMPLETED, result.document?.status)
        assertEquals(ScenarioVerdict.FAILED, result.document?.verdict)
        assertEquals(EvidenceCompleteness.COMPLETE, result.document?.evidenceCompleteness)
        assertEquals(NetworkEvaluationOutcome.MISMATCHED, result.document?.network?.outcome)
        assertTrue(result.bundleIntegrityValid)
    }

    @Test
    fun `network success with UI assertion failure remains a completed behavioral failure`() {
        val result = coordinator(networkDevice(false), FakeRunningServer(successfulExchanges())).run(request("ui-failure"))

        assertEquals(ExecutionStatus.COMPLETED, result.document?.status)
        assertEquals(ScenarioVerdict.FAILED, result.document?.verdict)
        assertEquals(NetworkEvaluationOutcome.MATCHED, result.document?.network?.outcome)
        assertEquals(EvidenceCompleteness.COMPLETE, result.document?.evidenceCompleteness)
    }

    @Test
    fun `reverse setup failure stops the server and prevents uncontrolled launch`() {
        val handle = FakeRunningServer(emptyList())
        val device =
            networkDevice(true).apply {
                reverseResult = DeviceCall(failure = DeviceFailureKind.COMMAND, detail = "reverse unavailable")
            }

        val result = coordinator(device, handle).run(request("reverse-failure"))

        assertTrue(handle.stopped)
        assertEquals(ExecutionStatus.ERROR, result.document?.status)
        assertEquals(NetworkEvaluationOutcome.NOT_EVALUATED, result.document?.network?.outcome)
        assertTrue(device.operations.none { it.startsWith("launch:") })
        assertTrue(device.operations.none { it.startsWith("reverse-remove:") })
        assertTrue(result.bundleIntegrityValid)
    }

    @Test
    fun `host cancellation after reverse setup still removes mapping and stops server`() {
        var cancelled = false
        val handle = FakeRunningServer(emptyList())
        val device = networkDevice(true).apply { afterOperation = { if (it == "reverse") cancelled = true } }

        val result = coordinator(device, handle, CancellationSignal { cancelled }).run(request("cancelled"))

        assertEquals(ExecutionStatus.CANCELLED, result.document?.status)
        assertTrue(handle.stopped)
        assertTrue(device.operations.any { it.startsWith("reverse-remove:") })
        assertTrue(device.operations.none { it.startsWith("launch:") })
    }

    @Test
    fun `server inspection and reverse cleanup failures are explicit without hiding an earlier failure`() {
        val handle = FakeRunningServer(emptyList(), failInspection = true)
        val device =
            networkDevice(true).apply {
                launchResult = DeviceCall(failure = DeviceFailureKind.COMMAND, detail = "original launch failure")
                removeReverseResult = DeviceCall(failure = DeviceFailureKind.COMMAND, detail = "reverse cleanup failure")
            }

        val result = coordinator(device, handle).run(request("server-failure"))

        assertEquals("original launch failure", result.document?.primaryError)
        assertEquals(NetworkEvaluationOutcome.NOT_EVALUATED, result.document?.network?.outcome)
        assertTrue(handle.stopped)
        assertTrue(device.operations.any { it.startsWith("reverse-remove:") })
        assertTrue(result.bundleIntegrityValid)
    }

    @Test
    fun `drift after launch still cleans owned network resources and restores environment`() {
        var probes = 0
        val handle = FakeRunningServer(emptyList())
        val store = FileEmulatorRecoveryJournalStore(directory.resolve("drift-recovery"))
        val device =
            object : FakeSmokeDevice() {
                override fun probeCapabilities(
                    serial: String,
                    timeoutMillis: Long,
                ): DeviceCall<EmulatorCapabilityObservationV1> {
                    probes++
                    if (probes == 3) {
                        capabilityResult =
                            DeviceCall(
                                requireNotNull(capabilityResult.value).copy(
                                    bootIdentifier = "223e4567-e89b-12d3-a456-426614174000",
                                ),
                            )
                    }
                    return super.probeCapabilities(serial, timeoutMillis)
                }
            }
        val request =
            request("drift-cleanup").copy(
                environmentPath =
                    directory.resolve("drift-cleanup-environment.json").also {
                        Files.writeString(
                            it,
                            """{"schemaVersion":1,"locale":"en-US","orientation":"PORTRAIT","animations":""" +
                                """{"windowScale":0.0,"transitionScale":0.0,"animatorScale":0.0}}""",
                        )
                    },
                environmentMode = EnvironmentExecutionMode.APPLY_AND_RESTORE,
            )

        val result = coordinator(device, handle, recoveryJournalStore = store).run(request)

        assertEquals(ExecutionStatus.ERROR, result.document?.status)
        assertTrue(handle.stopped)
        assertTrue(device.operations.any { it.startsWith("reverse-remove:") })
        assertTrue(device.operations.any { it.startsWith("restore:") })
        assertEquals(RecoveryJournalPhase.RESTORED_VERIFIED, requireNotNull(store.load("emulator-5554")).phase)
        assertTrue(result.bundleIntegrityValid)
    }

    @Test
    fun `v4 requires every retry request contract to match`() {
        val matched =
            coordinator(networkDevice(true), FakeRunningServer(successfulRequestContractExchanges()))
                .run(request("request-contract-success", REQUEST_CONTRACT_SCENARIO))

        assertTrue(matched.isSuccessful)
        assertEquals(NetworkEvaluationOutcome.MATCHED, matched.document?.network?.outcome)
        assertEquals(
            listOf(RequestContractOutcome.MATCHED, RequestContractOutcome.MATCHED),
            matched.document?.network?.exchanges?.map { it.requestContractOutcome },
        )
        val matchedBundle = requireNotNull(matched.output)
        val networkFiles =
            evidenceJson.decodeFromString<EvidenceBundleManifestV3>(Files.readString(matchedBundle.resolve("manifest.json")))
                .evidenceFiles.filter { it.role == EvidenceFileRole.NETWORK }
        networkFiles.forEach { descriptor ->
            val exchangeJson = Files.readString(matchedBundle.resolve(descriptor.path.value))
            assertTrue(exchangeJson.contains("\"outcome\": \"MATCHED\""))
            assertTrue(!exchangeJson.contains("DroidProof42"))
        }
    }

    @Test
    fun `v4 UI success with second request mismatch is completed behavioral failure and valid evidence`() {
        val exchanges = successfulRequestContractExchanges().toMutableList()
        exchanges[1] =
            exchanges[1].copy(
                requestContract =
                    RequestContractEvaluation(
                        RequestContractOutcome.MISMATCHED,
                        listOf(RequestContractIssue.BODY_SHA256_MISMATCH),
                    ),
            )

        val result =
            coordinator(networkDevice(true), FakeRunningServer(exchanges))
                .run(request("request-contract-failure", REQUEST_CONTRACT_SCENARIO))

        assertEquals(AssertionOutcome.MATCHED, result.document?.assertion?.outcome)
        assertEquals(ExecutionStatus.COMPLETED, result.document?.status)
        assertEquals(ScenarioVerdict.FAILED, result.document?.verdict)
        assertEquals(NetworkEvaluationOutcome.MISMATCHED, result.document?.network?.outcome)
        assertEquals(EvidenceCompleteness.COMPLETE, result.document?.evidenceCompleteness)
        assertTrue(result.bundleIntegrityValid)
        assertTrue(EvidenceBundleVerifier().verify(requireNotNull(result.output)).isValid)
    }

    @Test
    fun `v4 unavailable request collection remains not evaluated and is not a behavioral mismatch`() {
        val exchanges = successfulRequestContractExchanges().toMutableList()
        exchanges[0] =
            exchanges[0].copy(
                requestBody = exchanges[0].requestBody.copy(complete = false),
                requestContract =
                    RequestContractEvaluation(
                        RequestContractOutcome.NOT_EVALUATED,
                        listOf(RequestContractIssue.BODY_INCOMPLETE),
                    ),
            )

        val result =
            coordinator(networkDevice(true), FakeRunningServer(exchanges))
                .run(request("request-contract-unavailable", REQUEST_CONTRACT_SCENARIO))

        assertEquals(NetworkEvaluationOutcome.NOT_EVALUATED, result.document?.network?.outcome)
        assertEquals(ExecutionStatus.ERROR, result.document?.status)
        assertEquals(ScenarioVerdict.NOT_EVALUATED, result.document?.verdict)
        assertTrue(result.bundleIntegrityValid)
    }

    @Test
    fun `v4 extra request remains a completed behavioral mismatch`() {
        val exchanges = successfulRequestContractExchanges() + exchange(3, 409, RequestContractOutcome.MATCHED, matchedPlan = false)
        val result =
            coordinator(networkDevice(true), FakeRunningServer(exchanges))
                .run(request("request-contract-extra", REQUEST_CONTRACT_SCENARIO))

        assertEquals(ExecutionStatus.COMPLETED, result.document?.status)
        assertEquals(ScenarioVerdict.FAILED, result.document?.verdict)
        assertEquals(NetworkEvaluationOutcome.MISMATCHED, result.document?.network?.outcome)
    }

    private fun coordinator(
        device: FakeSmokeDevice,
        handle: FakeRunningServer,
        cancellation: CancellationSignal = CancellationSignal { false },
        recoveryJournalStore: EmulatorRecoveryJournalStore =
            FileEmulatorRecoveryJournalStore(directory.resolve("recovery")),
    ): SmokeCoordinator {
        val monotonic = FakeMonotonicClock()
        var hierarchyId = 0
        val assertion =
            UiAssertionRunner(
                device,
                monotonicClock = monotonic,
                waiter = ScenarioWaiter(monotonic::advanceMillis),
                cancellation = cancellation,
                idSource = { "network-hierarchy-${++hierarchyId}" },
            )
        return SmokeCoordinator(
            device,
            completeCapture(),
            wallClock = wallClock,
            monotonicClock = monotonic,
            cancellation = cancellation,
            assertionRunner = assertion,
            mockServerStarter = MockServerStarter { _: MockServerPlan, _: MockServerLimits -> handle },
            idSource = { "run-network" },
            recoveryJournalStore = recoveryJournalStore,
        )
    }

    private fun request(
        name: String,
        scenarioContents: String = NETWORK_SCENARIO,
    ): SmokeRunRequest {
        val apk = directory.resolve("$name.apk").also { Files.writeString(it, "apk bytes") }
        val scenario = directory.resolve("$name.json").also { Files.writeString(it, scenarioContents) }
        return SmokeRunRequest(
            apk,
            scenario,
            "emulator-5554",
            directory.resolve(name),
            droidProofVersion = DroidProofVersion("0.1.0-SNAPSHOT"),
            commandTimeoutMillis = 1000,
        )
    }

    private fun networkDevice(matchingUi: Boolean): FakeSmokeDevice =
        FakeSmokeDevice().apply {
            dumps += DumpResponse(INPUT_NODE)
            dumps += DumpResponse(ACTION_NODE)
            dumps += DumpResponse(if (matchingUi) MATCHING_NETWORK_NODE else FakeSmokeDevice.NON_MATCHING_XML)
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
            val document =
                CaptureDocument(
                    captureId = "capture-fixed",
                    hostStartedAt = wallClock.instant().toString(),
                    hostEndedAt = wallClock.instant().toString(),
                    device = DeviceIdentity("emulator-5554", "device"),
                    metadata =
                        mapOf(
                            "buildFingerprint" to ObservedField("synthetic/fingerprint"),
                            "apiLevel" to ObservedField("35"),
                        ),
                    status = CaptureStatus.SUCCESS,
                    screenshot = CollectionOutcome.SUCCESS,
                    logcat = CollectionOutcome.NOT_REQUESTED,
                    requestedPid = null,
                    files = listOf(CaptureFile(collected.destination, collected.mediaType, collected.role)),
                    issues = emptyList(),
                )
            CaptureResult(captureDirectory, document, listOf(collected))
        }

    private fun successfulExchanges(): List<ObservedHttpExchange> = listOf(exchange(1, 503), exchange(2, 201))

    private fun successfulRequestContractExchanges(): List<ObservedHttpExchange> =
        listOf(
            exchange(1, 503, RequestContractOutcome.MATCHED),
            exchange(2, 201, RequestContractOutcome.MATCHED),
        )

    private fun exchange(
        sequence: Int,
        status: Int,
        requestContractOutcome: RequestContractOutcome = RequestContractOutcome.NOT_EVALUATED,
        matchedPlan: Boolean = true,
    ) = ObservedHttpExchange(
        sequence,
        wallClock.instant().toString(),
        "POST",
        "/orders",
        BODY,
        status,
        responseBody(status),
        matchedResponsePlan = matchedPlan,
        responsePlanIndex = sequence.takeIf { matchedPlan },
        requestContract = RequestContractEvaluation(requestContractOutcome),
    )

    private fun responseBody(status: Int): HttpBodyObservation {
        val bytes =
            if (status == 503) {
                "{\"error\":\"retry\"}".toByteArray()
            } else {
                "{\"orderId\":\"order-42\"}".toByteArray()
            }
        return HttpBodyObservation(
            bytes.size.toLong(),
            Sha256Calculator.calculate(java.io.ByteArrayInputStream(bytes)).value,
            complete = true,
        )
    }

    private companion object {
        private val EXPECTED_REQUEST_BYTES = "{\"customer\":\"DroidProof42\"}".toByteArray()
        val BODY =
            HttpBodyObservation(
                EXPECTED_REQUEST_BYTES.size.toLong(),
                Sha256Calculator.calculate(java.io.ByteArrayInputStream(EXPECTED_REQUEST_BYTES)).value,
                complete = true,
            )
        val INPUT_NODE =
            """<hierarchy><node package="io.droidproof.smoke" resource-id="io.droidproof.smoke:id/name" bounds="[1,1][3,3]"/></hierarchy>"""
                .toByteArray()
        val ACTION_NODE =
            (
                """<hierarchy><node package="io.droidproof.smoke" """ +
                    """resource-id="io.droidproof.smoke:id/action" bounds="[1,1][3,3]"/></hierarchy>"""
            )
                .toByteArray()
        val MATCHING_NETWORK_NODE =
            (
                """<hierarchy><node package="io.droidproof.smoke" """ +
                    """resource-id="io.droidproof.smoke:id/status" text="Order order-42 created"/></hierarchy>"""
            )
                .toByteArray()
    }
}

private class FakeRunningServer(
    private val exchanges: List<ObservedHttpExchange>,
    private val failInspection: Boolean = false,
) : RunningMockServer {
    override val hostAddress: String = "127.0.0.1"
    override val hostPort: Int = 43210
    var stopped: Boolean = false

    override fun inspectExchanges(): List<ObservedHttpExchange> {
        if (failInspection) error("inspection failed")
        return exchanges
    }

    override fun stop() {
        stopped = true
    }
}
