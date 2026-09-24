package io.github.fredleonam.droidproof.host

import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class SmokeScenarioTest {
    @TempDir
    lateinit var directory: Path

    @Test
    fun `accepts exact scenario bytes and calculates their identity`() {
        val source = directory.resolve("scenario.json")
        Files.writeString(source, VALID_SCENARIO)

        val accepted = SmokeScenarioLoader.load(source)

        assertEquals(VALID_SCENARIO, accepted.exactBytes.toString(Charsets.UTF_8))
        assertEquals("smoke-ready", accepted.scenario.scenarioId.value)
        assertEquals("5f42068c739d80a30b5122dd8393ecc1fe2db0e6aa1ebde85a164802f3649f20", accepted.sha256.value)
    }

    @Test
    fun `rejects unknown fields unsupported versions and invalid package scoped values`() {
        assertRejected(VALID_SCENARIO.replace("\"schemaVersion\":1", "\"schemaVersion\":2"))
        assertRejected(VALID_SCENARIO.replace("\"scenarioId\"", "\"unknown\":true,\"scenarioId\""))
        assertRejected(VALID_SCENARIO.replace("io.droidproof.smoke/io.droidproof.smoke.MainActivity", "other/other.Main"))
        assertRejected(VALID_SCENARIO.replace("io.droidproof.smoke:id/status", "other:id/status"))
        assertRejected(VALID_SCENARIO.replace("\"assertionDeadlineMillis\":1000", "\"assertionDeadlineMillis\":0"))
        assertRejected(VALID_SCENARIO.replace("\"pollIntervalMillis\":100", "\"pollIntervalMillis\":2000"))
    }

    @Test
    fun `accepts ordered v2 steps and preserves exact bytes`() {
        val source = directory.resolve("v2.json")
        Files.writeString(source, INTERACTIVE_SCENARIO)
        val accepted = SmokeScenarioLoader.load(source)
        assertEquals(INTERACTIVE_SCENARIO, accepted.exactBytes.toString(Charsets.UTF_8))
        assertEquals(listOf(TapUiNode::class, AssertUiNode::class), accepted.scenario.orderedSteps.map { it::class })
    }

    @Test
    fun `v2 rejects unknown types fields invalid scope timing and empty steps`() {
        for (invalid in listOf(
            INTERACTIVE_SCENARIO.replace("tapUiNode", "swipe"),
            INTERACTIVE_SCENARIO.replace("\"type\":\"tapUiNode\"", "\"type\":\"tapUiNode\",\"x\":10"),
            INTERACTIVE_SCENARIO.replace("\"steps\"", "\"unknown\":true,\"steps\""),
            INTERACTIVE_SCENARIO.replace("io.droidproof.smoke:id/action", "other.package:id/action"),
            INTERACTIVE_SCENARIO.replace("io.droidproof.smoke:id/status", "status"),
            INTERACTIVE_SCENARIO.replace("\"deadlineMillis\":250", "\"deadlineMillis\":0"),
            INTERACTIVE_SCENARIO.replace("\"deadlineMillis\":250", "\"deadlineMillis\":300001"),
            INTERACTIVE_SCENARIO.replace("\"pollIntervalMillis\":100", "\"pollIntervalMillis\":251"),
            INTERACTIVE_SCENARIO.replace("\"pollIntervalMillis\":100", "\"pollIntervalMillis\":0"),
            INTERACTIVE_SCENARIO.substringBefore("[{") + "[]}",
            INTERACTIVE_SCENARIO.replace("\"schemaVersion\":2", "\"schemaVersion\":3"),
            INTERACTIVE_SCENARIO.replace("\"schemaVersion\":2", "\"schemaVersion\":1"),
        )) assertRejected(invalid)
    }

    @Test
    fun `scenario input remains size bounded`() {
        assertRejected(" ".repeat(1024 * 1024 + 1))
    }

    @Test
    fun `v3 strictly parses a bounded deterministic backend response plan`() {
        val source = directory.resolve("network.json")
        Files.writeString(source, NETWORK_SCENARIO)

        val accepted = SmokeScenarioLoader.load(source)
        val scenario = accepted.scenario as SmokeScenarioV3

        assertEquals(38637, scenario.backendPlan.devicePort)
        assertEquals(listOf(503, 201), scenario.backendPlan.responsePlan.map { it.status })
        assertEquals(3, scenario.orderedSteps.size)
        assertEquals(NETWORK_SCENARIO, accepted.exactBytes.toString(Charsets.UTF_8))
    }

    @Test
    fun `v3 rejects unknown fields and unsafe or unbounded network plans`() {
        listOf(
            NETWORK_SCENARIO.replace("\"backendPlan\"", "\"unknown\":true,\"backendPlan\""),
            NETWORK_SCENARIO.replace("\"devicePort\":38637", "\"devicePort\":80"),
            NETWORK_SCENARIO.replace("\"method\":\"POST\"", "\"method\":\"GET\""),
            NETWORK_SCENARIO.replace("\"path\":\"/orders\"", "\"path\":\"/other\""),
            NETWORK_SCENARIO.replace("\"requestBodyLimitBytes\":4096", "\"requestBodyLimitBytes\":0"),
            NETWORK_SCENARIO.replace("\"responseBodyLimitBytes\":4096", "\"responseBodyLimitBytes\":0"),
            NETWORK_SCENARIO.replace("\"status\":503", "\"status\":199"),
            NETWORK_SCENARIO.replace("\"status\":503", "\"status\":503,\"unknown\":true"),
            NETWORK_SCENARIO.replace(
                "\"responsePlan\":[{\"status\":503,\"body\":\"{\\\"error\\\":\\\"retry\\\"}\"}," +
                    "{\"status\":201,\"body\":\"{\\\"orderId\\\":\\\"order-42\\\"}\"}]",
                "\"responsePlan\":[]",
            ),
            NETWORK_SCENARIO.replace("{\\\"error\\\":\\\"retry\\\"}", "x".repeat(4097)),
        ).forEachIndexed { index, invalid -> assertRejected(invalid, "network invalid case $index") }
    }

    @Test
    fun `v4 strictly parses a bounded expected HTTP request without changing older schemas`() {
        val source = directory.resolve("request-contract.json")
        Files.writeString(source, REQUEST_CONTRACT_SCENARIO)

        val accepted = SmokeScenarioLoader.load(source)
        val scenario = accepted.scenario as SmokeScenarioV4

        assertEquals("application/json; charset=utf-8", scenario.backendPlan.expectedRequest.mediaType)
        assertEquals("{\"customer\":\"DroidProof42\"}", scenario.backendPlan.expectedRequest.body)
        assertEquals(REQUEST_CONTRACT_SCENARIO, accepted.exactBytes.toString(Charsets.UTF_8))

        Files.writeString(source, VALID_SCENARIO)
        assertEquals(1, SmokeScenarioLoader.load(source).scenario.schemaVersion)
        Files.writeString(source, INTERACTIVE_SCENARIO)
        assertEquals(2, SmokeScenarioLoader.load(source).scenario.schemaVersion)
        Files.writeString(source, NETWORK_SCENARIO)
        assertEquals(3, SmokeScenarioLoader.load(source).scenario.schemaVersion)
    }

    @Test
    fun `v4 rejects missing malformed empty unbounded and unknown request contracts`() {
        val withoutExpectedRequest =
            REQUEST_CONTRACT_SCENARIO.replace(
                "\"expectedRequest\":{\"mediaType\":\"application/json; charset=utf-8\"," +
                    "\"body\":\"{\\\"customer\\\":\\\"DroidProof42\\\"}\"},",
                "",
            )
        listOf(
            withoutExpectedRequest,
            REQUEST_CONTRACT_SCENARIO.replace("application/json; charset=utf-8", "application/json; charset=iso-8859-1"),
            REQUEST_CONTRACT_SCENARIO.replace("application/json; charset=utf-8", "application/json; charset=utf-8; x=y"),
            REQUEST_CONTRACT_SCENARIO.replace("application/json; charset=utf-8", "text/plain"),
            REQUEST_CONTRACT_SCENARIO.replace("{\\\"customer\\\":\\\"DroidProof42\\\"}", ""),
            REQUEST_CONTRACT_SCENARIO.replace("\"expectedRequest\":{", "\"expectedRequest\":{\"unknown\":true,"),
            REQUEST_CONTRACT_SCENARIO.replace("\"requestBodyLimitBytes\":4096", "\"requestBodyLimitBytes\":1"),
            NETWORK_SCENARIO.replace("\"schemaVersion\":3", "\"schemaVersion\":4"),
            REQUEST_CONTRACT_SCENARIO.replace("\"schemaVersion\":4", "\"schemaVersion\":3"),
        ).forEachIndexed { index, invalid -> assertRejected(invalid, "request contract invalid case $index") }
    }

    @Test
    fun `text step validates restricted language and preserves exact bytes and hash`() {
        val source = directory.resolve("text.json")
        Files.writeString(source, TEXT_SCENARIO)
        val accepted = SmokeScenarioLoader.load(source)
        assertEquals(TEXT_SCENARIO, accepted.exactBytes.toString(Charsets.UTF_8))
        assertEquals(io.github.fredleonam.droidproof.evidence.Sha256Calculator.calculate(source), accepted.sha256)
        assertEquals(TypeTextUiNode("io.droidproof.smoke:id/name", "DroidProof42"), accepted.scenario.orderedSteps.first())
        for (value in listOf("A".repeat(128), "AZaz09._-@")) {
            Files.writeString(source, TEXT_SCENARIO.replace("DroidProof42", value))
            SmokeScenarioLoader.load(source)
        }
        for (value in listOf("", "a".repeat(129), "a b", "%s", "é", "a;b", "a'b", "\n", "\t", "\u0000", "\r")) {
            assertRejected(TEXT_SCENARIO.replace("\"DroidProof42\"", kotlinx.serialization.json.JsonPrimitive(value).toString()))
        }
        assertRejected(TEXT_SCENARIO.replace("typeTextUiNode", "unknownInput"))
        assertRejected(TEXT_SCENARIO.replace("\"type\":\"typeTextUiNode\"", "\"type\":\"typeTextUiNode\",\"clear\":true"))
        assertRejected(TEXT_SCENARIO.replace("io.droidproof.smoke:id/name", "other.package:id/name"))
        assertRejected(TEXT_SCENARIO.replace("io.droidproof.smoke:id/name", "name"))
    }

    @Test
    fun `v6 accepts a bounded Compose semantics assertion`() {
        val scenario =
            REQUEST_CONTRACT_SCENARIO.replace("\"schemaVersion\":4", "\"schemaVersion\":6")
                .replace(
                    "\"type\":\"assertUiNode\",\"resourceId\":\"io.droidproof.smoke:id/status\",",
                    "\"type\":\"assertComposeSemantics\",\"resourceId\":\"io.droidproof.smoke:id/order_status\"," +
                        "\"contentDescription\":\"Order submission succeeded\",",
                )
        val source = directory.resolve("compose.json").also { Files.writeString(it, scenario) }

        val accepted = SmokeScenarioLoader.load(source)

        assertEquals(6, accepted.scenario.schemaVersion)
        assertEquals(AssertComposeSemantics::class, accepted.scenario.orderedSteps.last()::class)
        assertRejected(scenario.replace("Order submission succeeded", ""))
        assertRejected(scenario.replace("\"schemaVersion\":6", "\"schemaVersion\":5"))
    }

    private fun assertRejected(
        content: String,
        message: String? = null,
    ) {
        val source = directory.resolve("invalid-${content.hashCode()}.json")
        Files.writeString(source, content)
        assertFailsWith<Exception>(message) { SmokeScenarioLoader.load(source) }
    }

    private companion object {
        const val VALID_SCENARIO =
            """{"schemaVersion":1,"scenarioId":"smoke-ready","expectedPackage":"io.droidproof.smoke",""" +
                """"launchComponent":"io.droidproof.smoke/io.droidproof.smoke.MainActivity",""" +
                """"expectedUi":{"resourceId":"io.droidproof.smoke:id/status","text":"DroidProof ready"},""" +
                """"assertionDeadlineMillis":1000,"pollIntervalMillis":100}"""
    }
}

internal const val INTERACTIVE_SCENARIO =
    """{"schemaVersion":2,"scenarioId":"smoke-action","expectedPackage":"io.droidproof.smoke",""" +
        """"launchComponent":"io.droidproof.smoke/io.droidproof.smoke.MainActivity","steps":[{"type":"tapUiNode",""" +
        """"resourceId":"io.droidproof.smoke:id/action"},{"type":"assertUiNode","resourceId":"io.droidproof.smoke:id/status",""" +
        """"text":"DroidProof action completed","deadlineMillis":250,"pollIntervalMillis":100}]}"""

internal val TEXT_SCENARIO =
    INTERACTIVE_SCENARIO.replace(
        "\"steps\":[",
        "\"steps\":[{\"type\":\"typeTextUiNode\",\"resourceId\":\"io.droidproof.smoke:id/name\",\"text\":\"DroidProof42\"},",
    ).replace("DroidProof action completed", "Hello DroidProof42")

internal const val NETWORK_SCENARIO =
    """{"schemaVersion":3,"scenarioId":"smoke-network","expectedPackage":"io.droidproof.smoke",""" +
        """"launchComponent":"io.droidproof.smoke/io.droidproof.smoke.MainActivity","backendPlan":{"devicePort":38637,""" +
        """"method":"POST","path":"/orders","requestBodyLimitBytes":4096,"responseBodyLimitBytes":4096,"responsePlan":[""" +
        """{"status":503,"body":"{\"error\":\"retry\"}"},{"status":201,"body":"{\"orderId\":\"order-42\"}"}]},""" +
        """"steps":[{"type":"typeTextUiNode","resourceId":"io.droidproof.smoke:id/name","text":"DroidProof42"},""" +
        """{"type":"tapUiNode","resourceId":"io.droidproof.smoke:id/action"},{"type":"assertUiNode",""" +
        """"resourceId":"io.droidproof.smoke:id/status","text":"Order order-42 created","deadlineMillis":250,"pollIntervalMillis":100}]}"""

internal const val REQUEST_CONTRACT_SCENARIO =
    """{"schemaVersion":4,"scenarioId":"smoke-network-request","expectedPackage":"io.droidproof.smoke",""" +
        """"launchComponent":"io.droidproof.smoke/io.droidproof.smoke.MainActivity","backendPlan":{"devicePort":38637,""" +
        """"method":"POST","path":"/orders","requestBodyLimitBytes":4096,"responseBodyLimitBytes":4096,""" +
        """"expectedRequest":{"mediaType":"application/json; charset=utf-8","body":"{\"customer\":\"DroidProof42\"}"},""" +
        """"responsePlan":[{"status":503,"body":"{\"error\":\"retry\"}"},""" +
        """{"status":201,"body":"{\"orderId\":\"order-42\"}"}]},""" +
        """"steps":[{"type":"typeTextUiNode","resourceId":"io.droidproof.smoke:id/name","text":"DroidProof42"},""" +
        """{"type":"tapUiNode","resourceId":"io.droidproof.smoke:id/action"},{"type":"assertUiNode",""" +
        """"resourceId":"io.droidproof.smoke:id/status","text":"Order order-42 created","deadlineMillis":250,"pollIntervalMillis":100}]}"""
