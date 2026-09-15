package io.github.fredleonam.droidproof.mockserver

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.ByteArrayInputStream
import java.io.IOException
import java.io.InputStream
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

    @Test
    fun `evaluates exact bytes media type method and path while preserving deterministic responses`() {
        val server = startWithRequestContract()
        try {
            assertEquals(503, request(server.hostPort, "POST", "/orders", EXPECTED_BODY, EXPECTED_MEDIA_TYPE).first)
            assertEquals(201, request(server.hostPort, "POST", "/orders", WRONG_SAME_LENGTH_BODY, EXPECTED_MEDIA_TYPE).first)
            assertEquals(409, request(server.hostPort, "POST", "/orders", "{}", EXPECTED_MEDIA_TYPE).first)
            assertEquals(404, request(server.hostPort, "POST", "/wrong", EXPECTED_BODY, EXPECTED_MEDIA_TYPE).first)
            assertEquals(405, request(server.hostPort, "GET", "/orders", "", null).first)

            val exchanges = server.inspectExchanges()
            assertEquals(
                listOf(
                    RequestContractOutcome.MATCHED,
                    RequestContractOutcome.MISMATCHED,
                    RequestContractOutcome.MISMATCHED,
                    RequestContractOutcome.MISMATCHED,
                    RequestContractOutcome.MISMATCHED,
                ),
                exchanges.map { it.requestContract.outcome },
            )
            assertEquals(listOf(503, 201, 409, 404, 405), exchanges.map { it.responseStatus })
            assertEquals(listOf(1, 2, null, null, null), exchanges.map { it.responsePlanIndex })
            assertEquals(listOf(RequestContractIssue.BODY_SHA256_MISMATCH), exchanges[1].requestContract.issues)
            assertEquals(listOf(RequestContractIssue.BODY_SIZE_MISMATCH), exchanges[2].requestContract.issues)
            assertTrue(RequestContractIssue.PATH_MISMATCH in exchanges[3].requestContract.issues)
            assertTrue(RequestContractIssue.METHOD_MISMATCH in exchanges[4].requestContract.issues)
        } finally {
            server.stop()
        }
    }

    @Test
    fun `retry sends two matching request contracts`() {
        val server = startWithRequestContract()
        try {
            assertEquals(503, request(server.hostPort, "POST", "/orders", EXPECTED_BODY, EXPECTED_MEDIA_TYPE).first)
            assertEquals(201, request(server.hostPort, "POST", "/orders", EXPECTED_BODY, EXPECTED_MEDIA_TYPE).first)

            assertTrue(server.inspectExchanges().all { it.requestContract.outcome == RequestContractOutcome.MATCHED })
        } finally {
            server.stop()
        }
    }

    @Test
    fun `media type parameters are parsed narrowly and compared explicitly`() {
        val server = startWithRequestContract()
        try {
            request(server.hostPort, "POST", "/orders", EXPECTED_BODY, "Application/JSON; Charset=UTF-8")
            request(server.hostPort, "POST", "/orders", EXPECTED_BODY, "application/json")
            request(server.hostPort, "POST", "/orders", EXPECTED_BODY, "text/plain")

            val exchanges = server.inspectExchanges()
            assertEquals(RequestContractOutcome.MATCHED, exchanges[0].requestContract.outcome)
            assertEquals(listOf(RequestContractIssue.MEDIA_TYPE_MISMATCH), exchanges[1].requestContract.issues)
            assertEquals(listOf(RequestContractIssue.MEDIA_TYPE_MISMATCH), exchanges[2].requestContract.issues)
        } finally {
            server.stop()
        }
        listOf(
            "application/json; charset=iso-8859-1",
            "application/json; charset=utf-8; x=y",
            "application/json\r\nX-Evil: yes",
            "application/json; charset=\"utf-8\"",
        ).forEach { value -> assertFailsWith<IllegalArgumentException> { ExpectedHttpRequest(value, EXPECTED_BODY) } }
    }

    @Test
    fun `over-limit and incomplete reads are not reported as request mismatches`() {
        val server =
            DeterministicMockServer(clock).start(
                MockServerPlan(
                    "POST",
                    "/orders",
                    listOf(PlannedHttpResponse(201, "{}")),
                    ExpectedHttpRequest(EXPECTED_MEDIA_TYPE, "abc"),
                ),
                MockServerLimits(requestBodyLimitBytes = 3, responseBodyLimitBytes = 64, maxExchangeCount = 3),
            )
        try {
            assertEquals(413, request(server.hostPort, "POST", "/orders", "four", EXPECTED_MEDIA_TYPE).first)
            val contract = server.inspectExchanges().single().requestContract
            assertEquals(RequestContractOutcome.NOT_EVALUATED, contract.outcome)
            assertEquals(listOf(RequestContractIssue.BODY_LIMIT_EXCEEDED), contract.issues)
        } finally {
            server.stop()
        }

        val incomplete =
            readBounded(
                object : InputStream() {
                    private val source = ByteArrayInputStream("ab".toByteArray())

                    override fun read(): Int {
                        val value = source.read()
                        if (value < 0) throw IOException("truncated")
                        return value
                    }

                    override fun read(
                        bytes: ByteArray,
                        offset: Int,
                        length: Int,
                    ): Int {
                        val count = source.read(bytes, offset, length)
                        if (count < 0) throw IOException("truncated")
                        return count
                    }
                },
                3,
            )
        assertFalse(incomplete.observation.complete)
        assertEquals(2, incomplete.observation.capturedByteSize)
        assertEquals("ab", incomplete.bytes.toString(Charsets.UTF_8))
        val evaluation =
            evaluateRequestContract(
                MockServerPlan(
                    "POST",
                    "/orders",
                    listOf(PlannedHttpResponse(201, "{}")),
                    ExpectedHttpRequest(EXPECTED_MEDIA_TYPE, "abc"),
                ),
                3,
                "POST",
                "/orders",
                listOf(EXPECTED_MEDIA_TYPE),
                incomplete,
            )
        assertEquals(RequestContractOutcome.NOT_EVALUATED, evaluation.outcome)
        assertEquals(listOf(RequestContractIssue.BODY_INCOMPLETE), evaluation.issues)
    }

    @Test
    fun `serialized observations never contain expected or observed request body bytes`() {
        val server = startWithRequestContract()
        try {
            request(server.hostPort, "POST", "/orders", EXPECTED_BODY, EXPECTED_MEDIA_TYPE)
            val encoded = Json.encodeToString(server.inspectExchanges().single())
            assertFalse(encoded.contains(EXPECTED_BODY))
            assertFalse(encoded.contains("DroidProof42"))
            assertTrue(encoded.contains("requestContract"))
            assertTrue(encoded.contains("capturedByteSize"))
        } finally {
            server.stop()
        }
    }

    private fun start(): RunningMockServer = DeterministicMockServer(clock).start(plan(), MockServerLimits(maxExchangeCount = 10))

    private fun startWithRequestContract(): RunningMockServer =
        DeterministicMockServer(clock).start(
            plan().copy(expectedRequest = ExpectedHttpRequest(EXPECTED_MEDIA_TYPE, EXPECTED_BODY)),
            MockServerLimits(maxExchangeCount = 10),
        )

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
        mediaType: String? = null,
    ): Pair<Int, String> {
        val connection = URL("http://127.0.0.1:$port$path").openConnection() as HttpURLConnection
        connection.connectTimeout = 1000
        connection.readTimeout = 1000
        connection.requestMethod = method
        mediaType?.let { connection.setRequestProperty("Content-Type", it) }
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

    private companion object {
        const val EXPECTED_BODY = "{\"customer\":\"DroidProof42\"}"
        const val WRONG_SAME_LENGTH_BODY = "{\"customer\":\"DroidProof41\"}"
        const val EXPECTED_MEDIA_TYPE = "application/json; charset=utf-8"
    }
}
