package io.github.fredleonam.droidproof.mockserver

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import com.sun.net.httpserver.HttpsConfigurator
import com.sun.net.httpserver.HttpsServer
import kotlinx.serialization.Serializable
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.nio.charset.StandardCharsets
import java.security.KeyFactory
import java.security.KeyStore
import java.security.MessageDigest
import java.security.cert.CertificateFactory
import java.security.spec.PKCS8EncodedKeySpec
import java.time.Clock
import java.util.Base64
import java.util.Locale
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext

@Serializable
data class PlannedHttpResponse(
    val status: Int,
    val body: String,
    val mediaType: String = "application/json",
) {
    init {
        require(status in 200..599) { "Planned HTTP status must be between 200 and 599." }
        require(MEDIA_TYPE.matches(mediaType)) { "Planned response media type is invalid." }
    }
}

data class MockServerPlan(
    val method: String,
    val path: String,
    val responses: List<PlannedHttpResponse>,
    val expectedRequest: ExpectedHttpRequest? = null,
    val transport: NetworkTransport = NetworkTransport.HTTP,
) {
    init {
        require(method in SUPPORTED_METHODS) { "Only the POST method is supported by this milestone." }
        require(path == "/orders") { "Only the /orders endpoint is supported by this milestone." }
        require(responses.size in 1..MAX_RESPONSES) { "A response plan must contain 1 to $MAX_RESPONSES responses." }
    }
}

/** Transport is declared by the scenario; TLS is only available on DroidProof's loopback server. */
enum class NetworkTransport { HTTP, HTTPS }

data class ExpectedHttpRequest(
    val mediaType: String,
    val body: String,
) {
    init {
        require(body.isNotEmpty()) { "Expected request body must not be empty." }
        require(parseSupportedJsonMediaType(mediaType) != null) {
            "Expected request media type must be application/json with at most charset=utf-8."
        }
    }

    internal val bodyBytes: ByteArray = body.toByteArray(StandardCharsets.UTF_8)
    internal val parsedMediaType: ParsedMediaType = requireNotNull(parseSupportedJsonMediaType(mediaType))
}

data class MockServerLimits(
    val requestBodyLimitBytes: Long = 16_384,
    val responseBodyLimitBytes: Long = 16_384,
    val maxExchangeCount: Int = 32,
) {
    init {
        require(requestBodyLimitBytes in 1..MAX_BODY_BYTES) { "Request-body limit is outside supported bounds." }
        require(responseBodyLimitBytes in 1..MAX_BODY_BYTES) { "Response-body limit is outside supported bounds." }
        require(maxExchangeCount in 1..MAX_EXCHANGES) { "Exchange-count limit is outside supported bounds." }
    }
}

@Serializable
data class HttpBodyObservation(
    val capturedByteSize: Long,
    val sha256: String,
    val complete: Boolean,
)

@Serializable
enum class RequestContractOutcome {
    MATCHED,
    MISMATCHED,
    NOT_EVALUATED,
}

@Serializable
enum class RequestContractIssue {
    METHOD_MISMATCH,
    PATH_MISMATCH,
    MEDIA_TYPE_MISSING,
    MEDIA_TYPE_MALFORMED,
    MEDIA_TYPE_MISMATCH,
    BODY_SIZE_MISMATCH,
    BODY_SHA256_MISMATCH,
    BODY_INCOMPLETE,
    BODY_LIMIT_EXCEEDED,
}

@Serializable
data class RequestContractEvaluation(
    val outcome: RequestContractOutcome,
    val issues: List<RequestContractIssue> = emptyList(),
)

@Serializable
data class ObservedHttpExchange(
    val sequence: Int,
    val hostObservedAt: String,
    val method: String,
    val path: String,
    val requestBody: HttpBodyObservation,
    val responseStatus: Int,
    val responseBody: HttpBodyObservation,
    val matchedResponsePlan: Boolean,
    val responsePlanIndex: Int? = null,
    val methodComplete: Boolean = true,
    val pathComplete: Boolean = true,
    val requestContract: RequestContractEvaluation =
        RequestContractEvaluation(RequestContractOutcome.NOT_EVALUATED),
)

fun interface MockServerStarter {
    fun start(
        plan: MockServerPlan,
        limits: MockServerLimits,
    ): RunningMockServer
}

interface RunningMockServer : AutoCloseable {
    val hostAddress: String
    val hostPort: Int

    fun inspectExchanges(): List<ObservedHttpExchange>

    fun stop()

    override fun close() = stop()
}

class DeterministicMockServer(private val clock: Clock = Clock.systemUTC()) : MockServerStarter {
    override fun start(
        plan: MockServerPlan,
        limits: MockServerLimits,
    ): RunningMockServer {
        plan.responses.forEach { response ->
            require(response.body.toByteArray(StandardCharsets.UTF_8).size.toLong() <= limits.responseBodyLimitBytes) {
                "Planned response body exceeds the configured response-body limit."
            }
        }
        plan.expectedRequest?.let { expected ->
            require(expected.bodyBytes.size.toLong() <= limits.requestBodyLimitBytes) {
                "Expected request body exceeds the configured request-body limit."
            }
        }
        require(limits.maxExchangeCount >= plan.responses.size) {
            "Exchange-count limit must be at least the response-plan size."
        }
        val executor =
            Executors.newSingleThreadExecutor { task ->
                Thread(task, "droidproof-mock-server").apply { isDaemon = true }
            }
        val server =
            when (plan.transport) {
                NetworkTransport.HTTP -> HttpServer.create(InetSocketAddress(IPV4_LOOPBACK, 0), 0)
                NetworkTransport.HTTPS -> tlsServer(InetSocketAddress(IPV4_LOOPBACK, 0))
            }
        return try {
            val state =
                ServerState(plan, limits, clock) {
                    Thread(
                        {
                            try {
                                server.stop(0)
                            } finally {
                                executor.shutdown()
                            }
                        },
                        "droidproof-mock-server-limit-stop",
                    ).apply { isDaemon = true }.start()
                }
            server.createContext("/") { exchange -> state.handle(exchange) }
            server.executor = executor
            server.start()
            RunningServer(server, executor, state)
        } catch (error: Exception) {
            executor.shutdownNow()
            server.stop(0)
            throw error
        }
    }
}

private fun tlsServer(address: InetSocketAddress): HttpsServer =
    HttpsServer.create(address, 0).also { server ->
        server.httpsConfigurator = HttpsConfigurator(loopbackSslContext())
    }

/**
 * The sample-only loopback identity is deliberately public and must never be used beyond the
 * local ADB-reversed test endpoint. Its matching certificate is bundled by the smoke app as a
 * narrow app trust anchor; it is not installed on the Android system or user trust store.
 */
private fun loopbackSslContext(): SSLContext {
    val certificate =
        CertificateFactory.getInstance("X.509").generateCertificate(
            ByteArrayInputStream(Base64.getDecoder().decode(LOOPBACK_CERTIFICATE_PEM)),
        )
    val privateKey =
        KeyFactory.getInstance("EC").generatePrivate(
            PKCS8EncodedKeySpec(Base64.getDecoder().decode(LOOPBACK_PRIVATE_KEY_PEM)),
        )
    val keyStore = KeyStore.getInstance(KeyStore.getDefaultType()).apply {
        load(null, null)
        setKeyEntry("droidproof-loopback", privateKey, CharArray(0), arrayOf(certificate))
    }
    val keyManagers = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm()).apply {
        init(keyStore, CharArray(0))
    }
    return SSLContext.getInstance("TLS").apply { init(keyManagers.keyManagers, null, null) }
}

private const val LOOPBACK_PRIVATE_KEY_PEM =
    "LS0tLS1CRUdJTiBFQyBQUklWQVRFIEtFWS0tLS0tCk1IY0NBUUVFSUlRM0VGVTl5SERGczI5MUJCY2ZCbUNwbmxZNk1INEF3M2ZvVmZkdzFXQzVvQW9HQ0NxR1NNNDkKQXdFSG9VUURRZ0FFSUhTYkFlbUd3OU9tdlh3UUNwMFZJWDNnc2xVRDEwRndLU3NmZDF0eWVaT0VMR0NKR1gvZQpPcFBJRFp2dWQ0b2V6cC8vSXB4eG91a3Y2c1pReWpNR2pBPT0KLS0tLS1FTkQgRUMgUFJJVkFURSBLRVktLS0tLQo="

private const val LOOPBACK_CERTIFICATE_PEM =
    "LS0tLS1CRUdJTiBDRVJUSUZJQ0FURS0tLS0tCk1JSUJqakNDQVRTZ0F3SUJBZ0lVRUxuM3JCUDQ2YjVMN1ZQUVc4ZWxtQUJ1SUxvd0NnWUlLb1pJemowRUF3SXcKRkRFU01CQUdBMVVFQXd3Sk1USTNMakF1TUM0eE1CNFhEVEkyTURreU1qSXhNak0wT0ZvWERUTTJNRGt4T1RJeApNak0wT0Zvd0ZERVNNQkFHQTFVRUF3d0pNVEkzTGpBdU1DNHhNRmt3RXdZSEtvWkl6ajBDQVFZSUtvWkl6ajBECkFRY0RRZ0FFSUhTYkFlbUd3OU9tdlh3UUNwMFZJWDNnc2xVRDEwRndLU3NmZDF0eWVaT0VMR0NKR1gvZU9wUEkKRFp2dWQ0b2V6cC8vSXB4eG91a3Y2c1pReWpNR2pLTmtNR0l3SFFZRFZSME9CQllFRkJMTFRjdlJUQVQ2ZVdDUQo0UUtXUHdlU0NBWCtNQjhHQTFVZEl3UVlNQmFBRkJMTFRjdlJUQVQ2ZVdDUTRRS1dQd2VTQ0FYK01BOEdBMVVkCkV3RUIvd1FGTUFNQkFmOHdEd1lEVlIwUkJBZ3dCb2NFZndBQUFUQUtCZ2dxaGtqT1BRUURBZ05JQURCRkFpRUEKN24vN29QNTNvVkJPQXdjMUJ3K3dqMUpaRGJrdzV4dFFySG5NZUI0UUxkd0NJSFdTTlZDRHI5SUNLcFU5YzF3awphYnZxY0plamRTSllFNU03MGJTU2FYQSsKLS0tLS1FTkQgQ0VSVElGSUNBVEUtLS0tLQo="

private class RunningServer(
    private val server: HttpServer,
    private val executor: ExecutorService,
    private val state: ServerState,
) : RunningMockServer {
    private val stopped = AtomicBoolean(false)

    override val hostAddress: String = server.address.address.hostAddress
    override val hostPort: Int = server.address.port

    override fun inspectExchanges(): List<ObservedHttpExchange> = state.snapshot()

    override fun stop() {
        if (!stopped.compareAndSet(false, true)) return
        try {
            server.stop(0)
        } finally {
            executor.shutdown()
        }
        var interrupted = false
        try {
            if (!executor.awaitTermination(STOP_TIMEOUT_SECONDS, TimeUnit.SECONDS)) executor.shutdownNow()
        } catch (_: InterruptedException) {
            interrupted = true
            executor.shutdownNow()
        } finally {
            if (interrupted) Thread.currentThread().interrupt()
        }
    }
}

private class ServerState(
    private val plan: MockServerPlan,
    private val limits: MockServerLimits,
    private val clock: Clock,
    private val stopAtExchangeLimit: () -> Unit,
) {
    private val lock = Any()
    private val exchanges = mutableListOf<ObservedHttpExchange>()
    private var nextResponse = 0

    fun snapshot(): List<ObservedHttpExchange> = synchronized(lock) { exchanges.toList() }

    fun handle(exchange: HttpExchange) {
        exchange.use {
            val request = readBounded(exchange.requestBody, limits.requestBodyLimitBytes)
            val target =
                exchange.requestURI.rawPath +
                    exchange.requestURI.rawQuery?.let { query -> "?$query" }.orEmpty()
            val observedMethod = exchange.requestMethod.take(MAX_METHOD_CHARACTERS)
            val observedTarget = target.take(MAX_TARGET_CHARACTERS)
            val decision =
                synchronized(lock) {
                    val selected = decide(exchange.requestMethod, target, request.observation.complete)
                    exchanges +=
                        ObservedHttpExchange(
                            sequence = selected.sequence,
                            hostObservedAt = clock.instant().toString(),
                            method = observedMethod,
                            path = observedTarget,
                            requestBody = request.observation,
                            responseStatus = selected.status,
                            responseBody = bodyObservation(selected.body, complete = true),
                            matchedResponsePlan = selected.planIndex != null,
                            responsePlanIndex = selected.planIndex,
                            methodComplete = observedMethod.length == exchange.requestMethod.length,
                            pathComplete = observedTarget.length == target.length,
                            requestContract =
                                evaluateRequestContract(
                                    plan,
                                    limits.requestBodyLimitBytes,
                                    exchange.requestMethod,
                                    target,
                                    exchange.requestHeaders["Content-Type"].orEmpty(),
                                    request,
                                ),
                        )
                    selected
                }
            try {
                exchange.responseHeaders.set("Content-Type", decision.mediaType)
                exchange.responseHeaders.set("Cache-Control", "no-store")
                exchange.sendResponseHeaders(decision.status, decision.body.size.toLong())
                exchange.responseBody.use { output -> output.write(decision.body) }
            } finally {
                if (decision.sequence == limits.maxExchangeCount) stopAtExchangeLimit()
            }
        }
    }

    private fun decide(
        method: String,
        path: String,
        completeRequest: Boolean,
    ): ResponseDecision {
        val sequence = exchanges.size + 1
        if (sequence > limits.maxExchangeCount) return ResponseDecision(sequence, 429, EMPTY_BODY, JSON_MEDIA_TYPE)
        if (!completeRequest) return ResponseDecision(sequence, 413, EMPTY_BODY, JSON_MEDIA_TYPE)
        if (path != plan.path) return ResponseDecision(sequence, 404, EMPTY_BODY, JSON_MEDIA_TYPE)
        if (method != plan.method) return ResponseDecision(sequence, 405, EMPTY_BODY, JSON_MEDIA_TYPE)
        if (nextResponse >= plan.responses.size) return ResponseDecision(sequence, 409, EMPTY_BODY, JSON_MEDIA_TYPE)
        val index = nextResponse++
        val planned = plan.responses[index]
        return ResponseDecision(
            sequence,
            planned.status,
            planned.body.toByteArray(StandardCharsets.UTF_8),
            planned.mediaType,
            index + 1,
        )
    }
}

private data class ResponseDecision(
    val sequence: Int,
    val status: Int,
    val body: ByteArray,
    val mediaType: String,
    val planIndex: Int? = null,
)

internal data class BoundedBodyRead(
    internal val bytes: ByteArray,
    val observation: HttpBodyObservation,
)

internal fun readBounded(
    input: InputStream,
    limit: Long,
): BoundedBodyRead {
    val output = ByteArrayOutputStream()
    val buffer = ByteArray(8192)
    var complete = true
    try {
        input.use {
            while (true) {
                val count = it.read(buffer)
                if (count < 0) break
                val remaining = limit + 1 - output.size().toLong()
                if (remaining <= 0) {
                    complete = false
                    break
                }
                val copied = minOf(count.toLong(), remaining).toInt()
                output.write(buffer, 0, copied)
                if (copied < count || output.size().toLong() > limit) {
                    complete = false
                    break
                }
            }
        }
    } catch (_: IOException) {
        complete = false
    }
    val bytes = output.toByteArray()
    return BoundedBodyRead(bytes, bodyObservation(bytes, complete))
}

internal fun evaluateRequestContract(
    plan: MockServerPlan,
    requestBodyLimitBytes: Long,
    method: String,
    target: String,
    contentTypes: List<String>,
    request: BoundedBodyRead,
): RequestContractEvaluation {
    val expected = plan.expectedRequest ?: return RequestContractEvaluation(RequestContractOutcome.NOT_EVALUATED)
    val issues = mutableListOf<RequestContractIssue>()
    if (method != plan.method) issues += RequestContractIssue.METHOD_MISMATCH
    if (target != plan.path) issues += RequestContractIssue.PATH_MISMATCH

    when {
        contentTypes.isEmpty() -> issues += RequestContractIssue.MEDIA_TYPE_MISSING
        contentTypes.size != 1 -> issues += RequestContractIssue.MEDIA_TYPE_MALFORMED
        else -> {
            val observed = parseSafeMediaType(contentTypes.single())
            when {
                observed == null -> issues += RequestContractIssue.MEDIA_TYPE_MALFORMED
                observed != expected.parsedMediaType -> issues += RequestContractIssue.MEDIA_TYPE_MISMATCH
            }
        }
    }

    if (!request.observation.complete) {
        issues +=
            if (request.observation.capturedByteSize > requestBodyLimitBytes) {
                RequestContractIssue.BODY_LIMIT_EXCEEDED
            } else {
                RequestContractIssue.BODY_INCOMPLETE
            }
        return RequestContractEvaluation(RequestContractOutcome.NOT_EVALUATED, issues)
    }
    if (request.bytes.size != expected.bodyBytes.size) {
        issues += RequestContractIssue.BODY_SIZE_MISMATCH
    } else if (!MessageDigest.isEqual(sha256(request.bytes), sha256(expected.bodyBytes))) {
        issues += RequestContractIssue.BODY_SHA256_MISMATCH
    }
    return RequestContractEvaluation(
        if (issues.isEmpty()) RequestContractOutcome.MATCHED else RequestContractOutcome.MISMATCHED,
        issues,
    )
}

private fun bodyObservation(
    bytes: ByteArray,
    complete: Boolean,
): HttpBodyObservation =
    HttpBodyObservation(
        capturedByteSize = bytes.size.toLong(),
        sha256 = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) },
        complete = complete,
    )

internal data class ParsedMediaType(
    val type: String,
    val subtype: String,
    val parameters: Map<String, String>,
)

internal fun parseSupportedJsonMediaType(value: String): ParsedMediaType? {
    val parsed = parseSafeMediaType(value) ?: return null
    return parsed.takeIf { it.type == "application" && it.subtype == "json" }
}

private fun parseSafeMediaType(value: String): ParsedMediaType? {
    if (value.length !in 1..MAX_MEDIA_TYPE_CHARACTERS || value.any { it == '\r' || it == '\n' }) return null
    val match = JSON_MEDIA_TYPE_PATTERN.matchEntire(value) ?: return null
    val type = match.groupValues[1].lowercase(Locale.ROOT)
    val subtype = match.groupValues[2].lowercase(Locale.ROOT)
    val parameterName = match.groupValues[3]
    if (parameterName.isEmpty()) return ParsedMediaType(type, subtype, emptyMap())
    if (!parameterName.equals("charset", ignoreCase = true)) return null
    val parameterValue = match.groupValues[4]
    if (!parameterValue.equals("utf-8", ignoreCase = true)) return null
    return ParsedMediaType(type, subtype, mapOf("charset" to "utf-8"))
}

private fun sha256(bytes: ByteArray): ByteArray = MessageDigest.getInstance("SHA-256").digest(bytes)

private const val MAX_RESPONSES = 16
private const val MAX_EXCHANGES = 64
private const val MAX_METHOD_CHARACTERS = 32
private const val MAX_TARGET_CHARACTERS = 2048
private const val MAX_BODY_BYTES = 1024L * 1024L
private const val MAX_MEDIA_TYPE_CHARACTERS = 128
private const val STOP_TIMEOUT_SECONDS = 5L
private const val JSON_MEDIA_TYPE = "application/json"
private val EMPTY_BODY = ByteArray(0)
private val SUPPORTED_METHODS = setOf("POST")
private val MEDIA_TYPE = Regex("[A-Za-z0-9!#$&^_.+-]+/[A-Za-z0-9!#$&^_.+-]+")
private val JSON_MEDIA_TYPE_PATTERN =
    Regex(
        "([A-Za-z0-9!#$&^_.+-]+)/([A-Za-z0-9!#$&^_.+-]+)" +
            "(?:[\\t ]*;[\\t ]*([A-Za-z0-9!#$&^_.+-]+)[\\t ]*=[\\t ]*([A-Za-z0-9!#$&^_.+-]+))?",
    )
private val IPV4_LOOPBACK = InetAddress.getByAddress(byteArrayOf(127, 0, 0, 1))
