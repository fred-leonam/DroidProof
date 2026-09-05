package io.github.fredleonam.droidproof.evidence

import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals

class Sha256CalculatorTest {
    @TempDir
    lateinit var temporaryDirectory: java.nio.file.Path

    @Test
    fun `calculates SHA-256 from a file stream`() {
        val data = temporaryDirectory.resolve("checkout.json")
        Files.writeString(data, "scenario data")

        assertEquals(
            "79c222848a21907e6dd3619f330d25c2ea3f14ffcc429b4940cffe035961f4b4",
            Sha256Calculator.calculate(data).value,
        )
    }
}
