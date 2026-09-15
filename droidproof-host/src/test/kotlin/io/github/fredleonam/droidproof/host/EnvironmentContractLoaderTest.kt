package io.github.fredleonam.droidproof.host

import io.github.fredleonam.droidproof.evidence.Sha256Calculator
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class EnvironmentContractLoaderTest {
    @TempDir
    lateinit var directory: Path

    @Test
    fun `strict loader preserves exact accepted bytes and digest`() {
        val bytes = contract().toByteArray()
        val path = directory.resolve("environment.json").also { Files.write(it, bytes) }

        val accepted = EnvironmentContractLoader.load(path)

        assertContentEquals(bytes, accepted.exactBytes)
        assertEquals(Sha256Calculator.calculate(path), accepted.sha256)
        assertEquals("en-US", accepted.contract.locale)
    }

    @Test
    fun `loader rejects unknown fields malformed UTF-8 versions symlinks and oversized input`() {
        val cases =
            listOf(
                contract().replace("\"locale\"", "\"unknown\":true,\"locale\""),
                contract().replace("\"schemaVersion\":1", "\"schemaVersion\":2"),
            )
        cases.forEachIndexed { index, text ->
            val path = directory.resolve("bad-$index.json").also { Files.writeString(it, text) }
            assertFailsWith<Exception> { EnvironmentContractLoader.load(path) }
        }
        val malformed = directory.resolve("malformed.json").also { Files.write(it, byteArrayOf(0xc3.toByte(), 0x28)) }
        assertFailsWith<Exception> { EnvironmentContractLoader.load(malformed) }
        val oversized = directory.resolve("oversized.json").also { Files.write(it, ByteArray(64 * 1024 + 1)) }
        assertFailsWith<Exception> { EnvironmentContractLoader.load(oversized) }
        val target = directory.resolve("target.json").also { Files.writeString(it, contract()) }
        val link = directory.resolve("link.json")
        Files.createSymbolicLink(link, target.fileName)
        assertFailsWith<Exception> { EnvironmentContractLoader.load(link) }
    }

    @Test
    fun `loader rejects invalid locale orientation and animation scales`() {
        val texts =
            listOf(
                contract().replace("en-US", "not_a_locale"),
                contract().replace("PORTRAIT", "UPSIDE_DOWN"),
                contract().replace("\"windowScale\":0.0", "\"windowScale\":-1.0"),
                contract().replace("\"transitionScale\":0.0", "\"transitionScale\":1e999"),
                contract().replace("\"animatorScale\":0.0", "\"animatorScale\":\"NaN\""),
            )
        texts.forEachIndexed { index, text ->
            val path = directory.resolve("invalid-$index.json").also { Files.writeString(it, text) }
            assertFailsWith<Exception>("case $index") { EnvironmentContractLoader.load(path) }
        }
    }

    private fun contract(): String =
        """
        {
          "schemaVersion":1,
          "locale":"en-US",
          "orientation":"PORTRAIT",
          "animations":{"windowScale":0.0,"transitionScale":0.0,"animatorScale":0.0}
        }
        """.trimIndent()
}
