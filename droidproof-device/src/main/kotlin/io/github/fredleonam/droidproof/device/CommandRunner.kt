package io.github.fredleonam.droidproof.device

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

enum class CommandFailure { LAUNCH, NONZERO_EXIT, TIMEOUT, INTERRUPTED, OUTPUT_LIMIT, IO }

data class CommandRequest(
    val arguments: List<String>,
    val timeoutMillis: Long = 15000,
    val stdoutLimitBytes: Long = 65536,
    val stderrLimitBytes: Long = 65536,
    val stdoutFile: Path? = null,
) {
    init {
        require(arguments.isNotEmpty() && arguments.none { '\u0000' in it })
        require(timeoutMillis in 1..3600000)
        require(stdoutLimitBytes in 1..Int.MAX_VALUE.toLong() && stderrLimitBytes in 1..Int.MAX_VALUE.toLong())
    }
}

data class CommandResult(
    val stdout: String = "",
    val stderr: String = "",
    val exitCode: Int? = null,
    val failure: CommandFailure? = null,
)

fun interface CommandRunner {
    /** A file destination belongs to the caller; failure can leave bounded partial bytes there. */
    fun execute(request: CommandRequest): CommandResult
}

class ProcessCommandRunner internal constructor(private val onStarted: (Process) -> Unit) : CommandRunner {
    constructor() : this({})

    override fun execute(request: CommandRequest): CommandResult {
        if (Thread.currentThread().isInterrupted) return CommandResult(failure = CommandFailure.INTERRUPTED)
        val process =
            try {
                ProcessBuilder(request.arguments).redirectErrorStream(false).start()
            } catch (_: IOException) {
                return CommandResult(failure = CommandFailure.LAUNCH)
            } catch (_: SecurityException) {
                return CommandResult(failure = CommandFailure.LAUNCH)
            }
        val pool = Executors.newFixedThreadPool(2) { task -> Thread(task, "droidproof-command-drain").apply { isDaemon = true } }
        val output = ByteArrayOutputStream()
        val errors = ByteArrayOutputStream()
        val exceeded = AtomicBoolean(false)
        var failure: CommandFailure? = null
        var interrupted = false
        val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(request.timeoutMillis)
        try {
            process.outputStream.close()
            val stdout =
                pool.submit {
                    if (request.stdoutFile == null) {
                        drain(process.inputStream, output, request.stdoutLimitBytes, exceeded)
                    } else {
                        Files.newOutputStream(request.stdoutFile).use {
                            drain(process.inputStream, it, request.stdoutLimitBytes, exceeded)
                        }
                    }
                }
            val stderr = pool.submit { drain(process.errorStream, errors, request.stderrLimitBytes, exceeded) }
            onStarted(process)
            while (true) {
                if (exceeded.get()) {
                    failure = CommandFailure.OUTPUT_LIMIT
                    break
                }
                // Inspect completed drains promptly so disk/read failures cannot stall behind a full pipe.
                if (stdout.isDone) stdout.get()
                if (stderr.isDone) stderr.get()
                if (!process.isAlive && stdout.isDone && stderr.isDone) break
                if (System.nanoTime() >= deadline) {
                    failure = CommandFailure.TIMEOUT
                    break
                }
                Thread.sleep(5)
            }
            if (failure == null && exceeded.get()) failure = CommandFailure.OUTPUT_LIMIT
            if (failure == null && process.exitValue() != 0) failure = CommandFailure.NONZERO_EXIT
        } catch (_: InterruptedException) {
            interrupted = true
            failure = CommandFailure.INTERRUPTED
        } catch (error: ExecutionException) {
            when (val cause = error.cause) {
                is IOException, is SecurityException -> failure = CommandFailure.IO
                is Error -> throw cause
                is RuntimeException -> throw cause
                else -> throw IllegalStateException("Unexpected command drain failure", cause)
            }
        } catch (_: IOException) {
            failure = CommandFailure.IO
        } finally {
            // Only this client process is owned. In particular, never kill the shared ADB server.
            if (process.isAlive) process.destroy()
            try {
                if (!process.waitFor(200, TimeUnit.MILLISECONDS)) {
                    process.destroyForcibly()
                    process.waitFor(1000, TimeUnit.MILLISECONDS)
                }
            } catch (_: InterruptedException) {
                interrupted = true
                failure = CommandFailure.INTERRUPTED
                process.destroyForcibly()
            }
            for (stream in listOf(process.inputStream, process.errorStream)) {
                try {
                    stream.close()
                } catch (_: IOException) {
                    // Already closed by a drain.
                }
            }
            pool.shutdownNow()
            try {
                pool.awaitTermination(1000, TimeUnit.MILLISECONDS)
            } catch (_: InterruptedException) {
                interrupted = true
                failure = CommandFailure.INTERRUPTED
            }
            if (interrupted) Thread.currentThread().interrupt()
        }
        return CommandResult(
            output.toString(Charsets.UTF_8),
            errors.toString(Charsets.UTF_8),
            if (process.isAlive) null else process.exitValue(),
            failure,
        )
    }

    private fun drain(
        input: InputStream,
        output: OutputStream,
        limit: Long,
        exceeded: AtomicBoolean,
    ) {
        input.use {
            val buffer = ByteArray(8192)
            var copied = 0L
            while (true) {
                val count = it.read(buffer)
                if (count < 0) return
                val allowed = minOf(count.toLong(), limit - copied).toInt()
                output.write(buffer, 0, allowed)
                copied += allowed
                if (allowed < count) {
                    exceeded.set(true)
                    return
                }
            }
        }
    }
}
