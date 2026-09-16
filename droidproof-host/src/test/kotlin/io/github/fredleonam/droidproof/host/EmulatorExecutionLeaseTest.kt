package io.github.fredleonam.droidproof.host

import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertFailsWith

class EmulatorExecutionLeaseTest {
    @TempDir
    lateinit var directory: Path

    @Test
    fun `same serial contends different serials do not and release permits reacquisition`() {
        val provider = FileEmulatorExecutionLeaseProvider(directory)
        val first = provider.acquire("emulator-5554")
        assertFailsWith<EmulatorExecutionLeaseUnavailableException> { provider.acquire("emulator-5554") }
        val other = provider.acquire("emulator-5556")
        other.close()
        first.close()
        provider.acquire("emulator-5554").close()
    }

    @Test
    fun `invalid serial cannot influence lock namespace`() {
        val provider = FileEmulatorExecutionLeaseProvider(directory)
        assertFailsWith<IllegalArgumentException> { provider.acquire("../private") }
    }
}
