package io.github.fredleonam.droidproof.mockserver

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import kotlinx.serialization.Serializable
import java.io.ByteArrayOutputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.time.Clock
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

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
) {
    init {
        require(method in SUPPORTED_METHODS) { "Only the POST method is supported by this milestone." }
        require(path == "/orders") { "Only the /orders endpoint is supported by this milestone." }
        require(responses.size in 1..MAX_RESPONSES) { "A response plan must contain 1 to $MAX_RESPONSES responses." }
    }
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
        require(limits.maxExchangeCount >= plan.responses.size) {
            "Exchange-count limit must be at least the response-plan size."
        }
        val executor =
            Executors.newSingleThreadExecutor { task ->
                Thread(task, "droidproof-mock-server").apply { isDaemon = true }
            }
        val server = HttpServer.create(InetSocketAddress(IPV4_LOOPBACK, 0), 0)
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
            val request = readBounded(exchange, limits.requestBodyLimitBytes)
            val target =
                exchange.requestURI.rawPath +
                    exchange.requestURI.rawQuery?.let { query -> "?$query" }.orEmpty()
            val observedMethod = exchange.requestMethod.take(MAX_METHOD_CHARACTERS)
            val observedTarget = target.take(MAX_TARGET_CHARACTERS)
            val decision =
                synchronized(lock) {
                    val selected = decide(exchange.requestMethod, target, request.complete)
                    exchanges +=
                        ObservedHttpExchange(
                            sequence = selected.sequence,
                            hostObservedAt = clock.instant().toString(),
                            method = observedMethod,
                            path = observedTarget,
                            requestBody = request,
                            responseStatus = selected.status,
                            responseBody = bodyObservation(selected.body, complete = true),
                            matchedResponsePlan = selected.planIndex != null,
                            responsePlanIndex = selected.planIndex,
                            methodComplete = observedMethod.length == exchange.requestMethod.length,
                            pathComplete = observedTarget.length == target.length,
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

private fun readBounded(
    exchange: HttpExchange,
    limit: Long,
): HttpBodyObservation {
    val output = ByteArrayOutputStream()
    val buffer = ByteArray(8192)
    var complete = true
    exchange.requestBody.use { input ->
        while (true) {
            val count = input.read(buffer)
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
    return bodyObservation(output.toByteArray(), complete)
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

private const val MAX_RESPONSES = 16
private const val MAX_EXCHANGES = 64
private const val MAX_METHOD_CHARACTERS = 32
private const val MAX_TARGET_CHARACTERS = 2048
private const val MAX_BODY_BYTES = 1024L * 1024L
private const val STOP_TIMEOUT_SECONDS = 5L
private const val JSON_MEDIA_TYPE = "application/json"
private val EMPTY_BODY = ByteArray(0)
private val SUPPORTED_METHODS = setOf("POST")
private val MEDIA_TYPE = Regex("[A-Za-z0-9!#$&^_.+-]+/[A-Za-z0-9!#$&^_.+-]+")
private val IPV4_LOOPBACK = InetAddress.getByAddress(byteArrayOf(127, 0, 0, 1))
