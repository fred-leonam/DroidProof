package io.github.fredleonam.droidproof.device

import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CommandRunnerTest {
    @TempDir
    lateinit var directory: Path

    private fun command(mode: String) =
        listOf(
            Path.of(System.getProperty("java.home"), "bin", "java").toString(),
            "-cp",
            System.getProperty("child.classpath"),
            ChildProcess::class.java.name,
            mode,
        )

    @Test
    fun `binary bytes and stderr are separate with paths containing spaces`() {
        val file = Files.createTempFile(directory, "binary with spaces", ".tmp")
        val result = ProcessCommandRunner().execute(CommandRequest(command("binary"), stdoutFile = file))
        assertEquals(null, result.failure)
        assertContentEquals(ByteArray(256) { it.toByte() }, Files.readAllBytes(file))
        assertEquals("diagnostic", result.stderr)
        assertEquals("", result.stdout)
    }

    @Test
    fun `large concurrent streams do not deadlock and are bounded`() {
        val runner = ProcessCommandRunner()
        val complete = runner.execute(CommandRequest(command("large"), stdoutLimitBytes = 200000, stderrLimitBytes = 200000))
        assertEquals(null, complete.failure)
        assertEquals(131072, complete.stdout.length)
        assertEquals(131072, complete.stderr.length)
        for (mode in listOf("large", "stderr")) {
            val result = runner.execute(CommandRequest(command(mode), stdoutLimitBytes = 1024, stderrLimitBytes = 1024))
            assertEquals(CommandFailure.OUTPUT_LIMIT, result.failure)
            assertTrue(result.stdout.length <= 1024)
            assertTrue(result.stderr.length <= 1024)
        }
    }

    @Test
    fun `binary size limits include exact boundary and disk failures are structured`() {
        val runner = ProcessCommandRunner()
        val file = Files.createTempFile(directory, "bounded", ".tmp")
        assertEquals(null, runner.execute(CommandRequest(command("binary"), stdoutLimitBytes = 256, stdoutFile = file)).failure)
        assertEquals(256L, Files.size(file))
        assertEquals(
            CommandFailure.OUTPUT_LIMIT,
            runner.execute(CommandRequest(command("binary"), stdoutLimitBytes = 255, stdoutFile = file)).failure,
        )
        assertEquals(255L, Files.size(file))
        assertEquals(CommandFailure.IO, runner.execute(CommandRequest(command("large"), stdoutFile = directory)).failure)
    }

    @Test
    fun `host shell characters remain literal arguments`() {
        val literal = "spaces ; $(not-a-command) `also-not-a-command`"
        val result = ProcessCommandRunner().execute(CommandRequest(command("echo") + literal))
        assertEquals(null, result.failure)
        assertEquals(literal, result.stdout)
    }

    @Test
    fun `already interrupted calls do not launch`() {
        var launched = false
        Thread.currentThread().interrupt()
        try {
            val result = ProcessCommandRunner { launched = true }.execute(CommandRequest(command("sleep")))
            assertEquals(CommandFailure.INTERRUPTED, result.failure)
            assertTrue(Thread.currentThread().isInterrupted)
            assertFalse(launched)
        } finally {
            Thread.interrupted()
        }
    }

    @Test
    fun `timeout nonzero and launch failures are distinct`() {
        var process: Process? = null
        val runner = ProcessCommandRunner { process = it }
        assertEquals(CommandFailure.TIMEOUT, runner.execute(CommandRequest(command("sleep"), timeoutMillis = 200)).failure)
        assertFalse(requireNotNull(process).isAlive)
        val nonzero = runner.execute(CommandRequest(command("nonzero")))
        assertEquals(CommandFailure.NONZERO_EXIT, nonzero.failure)
        assertEquals(7, nonzero.exitCode)
        assertEquals(CommandFailure.LAUNCH, runner.execute(CommandRequest(listOf(directory.resolve("absent").toString()))).failure)
    }

    @Test
    fun `interruption terminates the owned process and restores interrupt status`() {
        val started = CountDownLatch(1)
        var result: CommandResult? = null
        var interrupted = false
        var process: Process? = null
        val runner =
            ProcessCommandRunner {
                process = it
                started.countDown()
            }
        val thread =
            Thread {
                result = runner.execute(CommandRequest(command("sleep")))
                interrupted = Thread.currentThread().isInterrupted
            }
        thread.start()
        assertTrue(started.await(10, TimeUnit.SECONDS))
        thread.interrupt()
        thread.join(5000)
        assertFalse(thread.isAlive)
        assertEquals(CommandFailure.INTERRUPTED, result?.failure)
        assertTrue(interrupted)
        assertFalse(requireNotNull(process).isAlive)
    }
}

object ChildProcess {
    @JvmStatic
    fun main(args: Array<String>) {
        when (args.first()) {
            "echo" -> System.out.print(args[1])
            "binary" -> {
                System.out.write(ByteArray(256) { it.toByte() })
                System.err.print("diagnostic")
            }
            "large" -> {
                val writer = Thread { System.err.write(ByteArray(131072) { 65 }) }
                writer.start()
                System.out.write(ByteArray(131072) { 66 })
                writer.join()
            }
            "stderr" -> System.err.write(ByteArray(131072) { 65 })
            "sleep" -> Thread.sleep(60000)
            "nonzero" -> kotlin.system.exitProcess(7)
        }
    }
}
