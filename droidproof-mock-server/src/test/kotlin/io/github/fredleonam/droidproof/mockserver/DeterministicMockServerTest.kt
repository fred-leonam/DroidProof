package io.github.fredleonam.droidproof.mockserver

import java.net.HttpURLConnection
import java.net.URL
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DeterministicMockServerTest {
    private val clock = Clock.fixed(Instant.parse("2026-09-14T12:00:00Z"), ZoneOffset.UTC)

    @Test
    fun `serves and records the deterministic response sequence`() {
        val server = start()
        try {
            assertEquals(503, request(server.hostPort, "POST", "/orders", "one").first)
            assertEquals(201, request(server.hostPort, "POST", "/orders", "two").first)

            val exchanges = server.inspectExchanges()
            assertEquals(listOf(1, 2), exchanges.map { it.sequence })
            assertEquals(listOf(503, 201), exchanges.map { it.responseStatus })
            assertTrue(exchanges.all { it.matchedResponsePlan && it.requestBody.complete && it.responseBody.complete })
            assertEquals("2026-09-14T12:00:00Z", exchanges.first().hostObservedAt)
            assertFalse(exchanges.first().requestBody.sha256.contains("one"))
        } finally {
            server.stop()
        }
    }

    @Test
    fun `wrong method path and extra request are bounded observations and do not alter the plan`() {
        val server = start()
        try {
            assertEquals(405, request(server.hostPort, "GET", "/orders", "").first)
            assertEquals(404, request(server.hostPort, "POST", "/wrong", "").first)
            assertEquals(503, request(server.hostPort, "POST", "/orders", "one").first)
            assertEquals(201, request(server.hostPort, "POST", "/orders", "two").first)
            assertEquals(409, request(server.hostPort, "POST", "/orders", "three").first)

            val exchanges = server.inspectExchanges()
            assertEquals(5, exchanges.size)
            assertEquals(listOf(false, false, true, true, false), exchanges.map { it.matchedResponsePlan })
        } finally {
            server.stop()
        }
    }

    @Test
    fun `enforces request and response body bounds`() {
        val server =
            DeterministicMockServer(clock).start(
                plan(),
                MockServerLimits(requestBodyLimitBytes = 3, responseBodyLimitBytes = 64, maxExchangeCount = 10),
            )
        try {
            assertEquals(413, request(server.hostPort, "POST", "/orders", "four").first)
            val observed = server.inspectExchanges().single()
            assertFalse(observed.requestBody.complete)
            assertEquals(4, observed.requestBody.capturedByteSize)
        } finally {
            server.stop()
        }
        assertFailsWith<IllegalArgumentException> {
            DeterministicMockServer(clock).start(
                MockServerPlan("POST", "/orders", listOf(PlannedHttpResponse(200, "too large"))),
                MockServerLimits(responseBodyLimitBytes = 3),
            )
        }
        assertFailsWith<IllegalArgumentException> { MockServerLimits(requestBodyLimitBytes = 0) }
        assertFailsWith<IllegalArgumentException> { MockServerPlan("GET", "/orders", plan().responses) }
        assertFailsWith<IllegalArgumentException> { PlannedHttpResponse(199, "") }
    }

    @Test
    fun `start exposes only loopback and stop is idempotent`() {
        val server = start()
        assertEquals("127.0.0.1", server.hostAddress)
        assertTrue(server.hostPort in 1..65535)
        assertEquals(503, request(server.hostPort, "POST", "/orders", "one").first)
        server.stop()
        server.stop()
        assertFailsWith<Exception> { request(server.hostPort, "POST", "/orders", "two") }
        assertEquals(1, server.inspectExchanges().size)
    }

    private fun start(): RunningMockServer = DeterministicMockServer(clock).start(plan(), MockServerLimits(maxExchangeCount = 10))

    private fun plan() =
        MockServerPlan(
            "POST",
            "/orders",
            listOf(
                PlannedHttpResponse(503, "{\"error\":\"retry\"}"),
                PlannedHttpResponse(201, "{\"orderId\":\"order-42\"}"),
            ),
        )

    private fun request(
        port: Int,
        method: String,
        path: String,
        body: String,
    ): Pair<Int, String> {
        val connection = URL("http://127.0.0.1:$port$path").openConnection() as HttpURLConnection
        connection.connectTimeout = 1000
        connection.readTimeout = 1000
        connection.requestMethod = method
        if (method == "POST") {
            connection.doOutput = true
            connection.outputStream.use { it.write(body.toByteArray()) }
        }
        return try {
            val status = connection.responseCode
            val stream = if (status >= 400) connection.errorStream else connection.inputStream
            status to (stream?.bufferedReader()?.use { it.readText() }.orEmpty())
        } finally {
            connection.disconnect()
        }
    }
}
