package io.github.fredleonam.droidproof.host

import io.github.fredleonam.droidproof.evidence.EvidenceFileInput
import io.github.fredleonam.droidproof.evidence.Sha256Calculator
import io.github.fredleonam.droidproof.mockserver.MockServerStarter
import io.github.fredleonam.droidproof.mockserver.ObservedHttpExchange
import io.github.fredleonam.droidproof.mockserver.RequestContractOutcome
import io.github.fredleonam.droidproof.mockserver.ResponseFaultKind
import io.github.fredleonam.droidproof.mockserver.RunningMockServer
import io.github.fredleonam.droidproof.model.BundleRelativePath
import io.github.fredleonam.droidproof.model.EventId
import io.github.fredleonam.droidproof.model.EventSource
import io.github.fredleonam.droidproof.model.EvidenceFileRole
import io.github.fredleonam.droidproof.model.EvidenceReference
import io.github.fredleonam.droidproof.model.TimelineEvent
import io.github.fredleonam.droidproof.model.UtcTimestamp
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.ByteArrayInputStream
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path

internal data class NetworkStartResult(
    val session: ActiveNetworkSession? = null,
    val failureDetail: String? = null,
    val cancelled: Boolean = false,
    val cleanupDetail: String? = null,
)

internal data class NetworkFinishResult(
    val evaluation: NetworkEvaluationDocument,
    val evidenceFiles: List<EvidenceFileInput>,
    val timelineEvents: List<TimelineEvent>,
    val cleanupDetail: String? = null,
)

internal class NetworkSessionManager(
    private val device: SmokeDeviceOperations,
    private val serverStarter: MockServerStarter,
) {
    fun start(
        plan: BackendPlanDefinition,
        serial: String,
        timeoutMillis: Long,
    ): NetworkStartResult {
        val server =
            try {
                serverStarter.start(plan.serverPlan(), plan.serverLimits())
            } catch (_: Exception) {
                return NetworkStartResult(failureDetail = "Controlled mock server could not be started.")
            }
        if (server.hostAddress != "127.0.0.1") {
            val cleanup = runCatching { server.stop() }.exceptionOrNull()
            return NetworkStartResult(
                failureDetail = "Controlled mock server did not bind to the required IPv4 loopback address.",
                cleanupDetail = cleanup?.let { "Controlled mock server cleanup failed after unsafe binding." },
            )
        }
        val reverse =
            try {
                device.reverseTcp(serial, plan.devicePort, server.hostPort, timeoutMillis)
            } catch (_: Exception) {
                val cleanup = runCatching { server.stop() }.exceptionOrNull()
                return NetworkStartResult(
                    failureDetail = "ADB reverse setup failed.",
                    cleanupDetail = cleanup?.let { "Controlled mock server cleanup failed after reverse setup failure." },
                )
            }
        if (reverse.isSuccessful) return NetworkStartResult(ActiveNetworkSession(device, server, plan, serial))

        val cleanup = runCatching { server.stop() }.exceptionOrNull()
        return NetworkStartResult(
            failureDetail = reverse.detail ?: "ADB reverse setup failed.",
            cancelled = reverse.failure == DeviceFailureKind.CANCELLED,
            cleanupDetail = cleanup?.let { "Controlled mock server cleanup failed after reverse setup failure." },
        )
    }
}

internal class ActiveNetworkSession(
    private val device: SmokeDeviceOperations,
    private val server: RunningMockServer,
    private val plan: BackendPlanDefinition,
    private val serial: String,
) {
    fun finish(
        workDirectory: Path,
        timeoutMillis: Long,
    ): NetworkFinishResult {
        val cleanupDetails = mutableListOf<String>()
        val reverseCleanup = runCatching { device.removeReverseTcp(serial, plan.devicePort, timeoutMillis) }
        reverseCleanup.exceptionOrNull()?.let { cleanupDetails += "ADB reverse cleanup failed." }
        reverseCleanup.getOrNull()?.takeUnless { it.isSuccessful }?.let {
            cleanupDetails += it.detail ?: "ADB reverse cleanup failed."
        }
        runCatching { server.stop() }.exceptionOrNull()?.let {
            cleanupDetails += "Controlled mock server shutdown failed."
        }
        val exchanges =
            try {
                server.inspectExchanges()
            } catch (_: Exception) {
                return NetworkFinishResult(
                    evaluation =
                        NetworkEvaluationDocument(
                            NetworkEvaluationOutcome.NOT_EVALUATED,
                            plan.responsePlan.size,
                            0,
                            detail = "Controlled mock-server observations could not be inspected.",
                        ),
                    evidenceFiles = emptyList(),
                    timelineEvents = emptyList(),
                    cleanupDetail = cleanupDetails.joinToString(" ").ifEmpty { null },
                )
            }
        val evidenceDirectory = Files.createDirectories(workDirectory.resolve("network-exchanges"))
        val files = mutableListOf<EvidenceFileInput>()
        val summaries = mutableListOf<NetworkExchangeSummary>()
        val events = mutableListOf<TimelineEvent>()
        exchanges.forEach { exchange ->
            val number = exchange.sequence.toString().padStart(3, '0')
            val destination = BundleRelativePath("network/exchanges/$number.json")
            val source = evidenceDirectory.resolve("$number.json")
            Files.writeString(source, networkJson.encodeToString(exchange) + "\n")
            files += EvidenceFileInput(source, destination, "application/json", EvidenceFileRole.NETWORK)
            summaries +=
                NetworkExchangeSummary(
                    exchange.sequence,
                    exchange.method,
                    exchange.path,
                    exchange.responseStatus,
                    destination,
                    exchange.requestContract.outcome,
                )
            events += exchange.timelineEvent(destination)
        }
        val requestCollectionUnavailable =
            plan.mockServerExpectedRequest != null &&
                exchanges.any { it.requestContract.outcome == RequestContractOutcome.NOT_EVALUATED }
        val matched = !requestCollectionUnavailable && matchesPlan(exchanges)
        val outcome =
            when {
                requestCollectionUnavailable -> NetworkEvaluationOutcome.NOT_EVALUATED
                matched -> NetworkEvaluationOutcome.MATCHED
                else -> NetworkEvaluationOutcome.MISMATCHED
            }
        return NetworkFinishResult(
            evaluation =
                NetworkEvaluationDocument(
                    outcome,
                    plan.responsePlan.size,
                    exchanges.size,
                    summaries,
                    if (requestCollectionUnavailable) {
                        "At least one request contract could not be evaluated from a complete bounded request observation."
                    } else if (matched) {
                        if (plan.mockServerExpectedRequest == null) {
                            "The controlled server observed the complete ordered backend response plan."
                        } else {
                            "The controlled server observed matching request contracts and the complete ordered backend response plan."
                        }
                    } else {
                        "The controlled server observation did not match the expected request/response sequence."
                    },
                ),
            evidenceFiles = files,
            timelineEvents = events,
            cleanupDetail = cleanupDetails.joinToString(" ").ifEmpty { null },
        )
    }

    private fun matchesPlan(exchanges: List<ObservedHttpExchange>): Boolean =
        exchanges.size == plan.responsePlan.size &&
            exchanges.zip(plan.responsePlan).withIndex().all { (zeroBased, pair) ->
                val (observed, expected) = pair
                val expectedResponse = expected.body.toByteArray(StandardCharsets.UTF_8)
                val expectedRequest = plan.mockServerExpectedRequest?.bodyBytesForHost()
                val expectedDrop = expected.fault?.kind == ResponseFaultKind.DROP_CONNECTION
                observed.sequence == zeroBased + 1 &&
                    observed.matchedResponsePlan &&
                    observed.responsePlanIndex == zeroBased + 1 &&
                    observed.methodComplete &&
                    observed.pathComplete &&
                    observed.method == plan.method &&
                    observed.path == plan.path &&
                    observed.responseStatus == expected.status &&
                    observed.injectedFault == expected.fault &&
                    observed.responseDelivered != expectedDrop &&
                    (
                        if (expectedDrop) {
                            observed.responseBody.capturedByteSize == 0L && !observed.responseBody.complete
                        } else {
                            observed.responseBody.capturedByteSize == expectedResponse.size.toLong() &&
                                observed.responseBody.sha256 ==
                                Sha256Calculator.calculate(ByteArrayInputStream(expectedResponse)).value &&
                                observed.responseBody.complete
                        }
                    ) &&
                    observed.requestBody.complete &&
                    (
                        expectedRequest == null ||
                            (
                                observed.requestContract.outcome == RequestContractOutcome.MATCHED &&
                                    observed.requestBody.capturedByteSize == expectedRequest.size.toLong() &&
                                    observed.requestBody.sha256 ==
                                    Sha256Calculator.calculate(ByteArrayInputStream(expectedRequest)).value
                            )
                    )
            }
}

private fun io.github.fredleonam.droidproof.mockserver.ExpectedHttpRequest.bodyBytesForHost(): ByteArray =
    body.toByteArray(StandardCharsets.UTF_8)

private fun ObservedHttpExchange.timelineEvent(path: BundleRelativePath): TimelineEvent =
    TimelineEvent(
        id = EventId("network-${sequence.toString().padStart(3, '0')}"),
        timestamp = UtcTimestamp(hostObservedAt),
        source = EventSource.MOCK_SERVER,
        type = "http.exchange",
        attributes =
            mapOf(
                "method" to method,
                "path" to this.path,
                "responseStatus" to responseStatus.toString(),
                "responseDelivered" to responseDelivered.toString(),
                "injectedFault" to (injectedFault?.kind?.name ?: ""),
                "serverSequence" to sequence.toString(),
                "requestBytes" to requestBody.capturedByteSize.toString(),
                "requestSha256" to requestBody.sha256,
                "requestComplete" to requestBody.complete.toString(),
                "requestContractOutcome" to requestContract.outcome.name,
                "requestContractIssues" to requestContract.issues.joinToString(",") { it.name },
            ),
        evidence = listOf(EvidenceReference(path, "application/json")),
    )

@OptIn(ExperimentalSerializationApi::class)
private val networkJson =
    Json {
        prettyPrint = true
        prettyPrintIndent = "  "
        encodeDefaults = true
        explicitNulls = false
        ignoreUnknownKeys = false
    }
