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

    private fun assertRejected(content: String) {
        val source = directory.resolve("invalid-${content.hashCode()}.json")
        Files.writeString(source, content)
        assertFailsWith<Exception> { SmokeScenarioLoader.load(source) }
    }

    private companion object {
        const val VALID_SCENARIO =
            """{"schemaVersion":1,"scenarioId":"smoke-ready","expectedPackage":"io.droidproof.smoke",""" +
                """"launchComponent":"io.droidproof.smoke/io.droidproof.smoke.MainActivity",""" +
                """"expectedUi":{"resourceId":"io.droidproof.smoke:id/status","text":"DroidProof ready"},""" +
                """"assertionDeadlineMillis":1000,"pollIntervalMillis":100}"""
    }
}
