package io.github.fredleonam.droidproof.host

import io.github.fredleonam.droidproof.device.CommandRequest
import io.github.fredleonam.droidproof.device.CommandRunner
import io.github.fredleonam.droidproof.device.ProcessCommandRunner
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.util.concurrent.TimeUnit

data class EmulatorLaunchRequest(val arguments: List<String>, val environment: Map<String, String>)

fun interface EmulatorProcessLauncher {
    fun launch(request: EmulatorLaunchRequest): Process
}

data class EmulatorLifecycleConfiguration(
    val deviceSerial: String?,
    val avdName: String?,
    val emulatorPath: Path,
    val adbPath: Path,
    val port: Int = 5554,
    val startupTimeoutMillis: Long = 120_000,
    val shutdownTimeoutMillis: Long = 30_000,
    /** Non-null only for an AVD created in DroidProof's owned provisioning root. */
    val ownedAvdDirectory: Path? = null,
    val environment: Map<String, String> = emptyMap(),
) {
    init {
        require((deviceSerial != null) xor (avdName != null)) { "Set exactly one of droidproof.deviceSerial or droidproof.avdName." }
        deviceSerial?.let { require(Regex("[A-Za-z0-9._:-]{1,128}").matches(it)) { "droidproof.deviceSerial is invalid." } }
        avdName?.let { require(Regex("[A-Za-z0-9._-]{1,128}").matches(it)) { "droidproof.avdName is invalid." } }
        require(port in 5554..5682 && port % 2 == 0) { "droidproof.emulatorPort must be an even emulator port from 5554 through 5682." }
        require(startupTimeoutMillis in 1..3_600_000 && shutdownTimeoutMillis in 1..3_600_000)
    }
}

interface ManagedEmulatorSession : AutoCloseable {
    val serial: String

    override fun close()
}

/** Available only for sessions which launched a child process. */
interface OwnedProcessSession : ManagedEmulatorSession {
    /** True only after the exact launched child has been observed to exit. */
    val terminationConfirmed: Boolean
}

interface EmulatorLifecycleManager {
    fun start(configuration: EmulatorLifecycleConfiguration): ManagedEmulatorSession
}

class EmulatorLifecycleException(
    message: String,
    cause: Throwable? = null,
    /** Null means no child was launched; false means its exit could not be confirmed. */
    val ownedProcessTerminationConfirmed: Boolean? = null,
) : RuntimeException(message, cause)

/** Legacy SDK emulator backend. The explicit port makes the serial association deterministic. */
class LegacyEmulatorLifecycleManager(
    private val runner: CommandRunner = ProcessCommandRunner(),
    // Emulator is long-lived; inherit streams so no unread ProcessBuilder pipe can block it.
    private val launcher: EmulatorProcessLauncher =
        EmulatorProcessLauncher { request ->
            ProcessBuilder(request.arguments).inheritIO().apply { environment().putAll(request.environment) }.start()
        },
    private val sleeper: (Long) -> Unit = { Thread.sleep(it) },
) : EmulatorLifecycleManager {
    override fun start(configuration: EmulatorLifecycleConfiguration): ManagedEmulatorSession {
        configuration.deviceSerial?.let { serial ->
            return object : ManagedEmulatorSession {
                override val serial = serial

                override fun close() = Unit
            }
        }
        val avd = requireNotNull(configuration.avdName)
        val listed =
            runner.execute(
                CommandRequest(
                    listOf(configuration.emulatorPath.toString(), "-list-avds"),
                    configuration.startupTimeoutMillis,
                    environment = configuration.environment,
                ),
            )
        if (configuration.ownedAvdDirectory == null && (listed.failure != null || listed.exitCode != 0)) {
            throw EmulatorLifecycleException(
                "Could not list existing AVDs.",
            )
        }
        if (configuration.ownedAvdDirectory == null &&
            listed.stdout.lineSequence().map(String::trim).none {
                it == avd
            }
        ) {
            throw EmulatorLifecycleException("Requested AVD '$avd' does not exist.")
        }
        val serial = "emulator-${configuration.port}"
        val before =
            runner.execute(
                CommandRequest(
                    listOf(configuration.adbPath.toString(), "devices"),
                    configuration.startupTimeoutMillis,
                    environment = configuration.environment,
                ),
            )
        val observed = parseDevices(before)
        if (serial in observed) {
            throw EmulatorLifecycleException("Target emulator serial is already present before launch.")
        }
        // A failed or ambiguous observation is not evidence that this serial is free.
        val process =
            try {
                launcher.launch(
                    EmulatorLaunchRequest(
                        buildList {
                            add(configuration.emulatorPath.toString())
                            addAll(
                                listOf(
                                    "-avd",
                                    avd,
                                    "-port",
                                    configuration.port.toString(),
                                ),
                            )
                            if (isMarkedOwned(configuration)) addAll(listOf("-wipe-data", "-no-snapshot"))
                        },
                        configuration.environment,
                    ),
                )
            } catch (
                e: Exception,
            ) {
                throw EmulatorLifecycleException("Could not start AVD '$avd'.", e)
            }
        val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(configuration.startupTimeoutMillis)
        try {
            while (System.nanoTime() < deadline) {
                if (Thread.currentThread().isInterrupted) throw InterruptedException("Lifecycle startup interrupted.")
                if (!process.isAlive) throw EmulatorLifecycleException("Emulator process exited during startup.")
                val devices =
                    runner.execute(
                        CommandRequest(
                            listOf(configuration.adbPath.toString(), "devices"),
                            remaining(deadline),
                            environment = configuration.environment,
                        ),
                    )
                val state = runCatching { parseDevices(devices)[serial] }.getOrNull()
                if (state == "device") {
                    val boot =
                        runner.execute(
                            CommandRequest(
                                listOf(
                                    configuration.adbPath.toString(),
                                    "-s",
                                    serial,
                                    "shell",
                                    "getprop",
                                    "sys.boot_completed",
                                ),
                                remaining(deadline),
                                environment = configuration.environment,
                            ),
                        )
                    if (boot.failure == null && boot.exitCode == 0 && boot.stdout.trim() == "1") {
                        return Session(
                            serial,
                            process,
                            configuration,
                            runner,
                        )
                    }
                }
                sleeper(100)
            }
            throw EmulatorLifecycleException("Timed out waiting for ADB and Android boot readiness for $serial.")
        } catch (e: InterruptedException) {
            val cleanup = terminate(process, configuration.shutdownTimeoutMillis)
            Thread.currentThread().interrupt()
            if (cleanup.isFailure) {
                throw EmulatorLifecycleException(
                    "Startup was interrupted and the owned emulator process exit could not be confirmed.",
                    e,
                    false,
                ).also { cleanup.exceptionOrNull()?.let(it::addSuppressed) }
            }
            throw e
        } catch (e: Throwable) {
            val cleanup = terminate(process, configuration.shutdownTimeoutMillis)
            if (cleanup.isFailure) {
                throw EmulatorLifecycleException(
                    "Emulator startup failed and the owned emulator process exit could not be confirmed.",
                    e,
                    false,
                ).also { cleanup.exceptionOrNull()?.let(it::addSuppressed) }
            }
            throw e
        }
    }

    private fun isMarkedOwned(configuration: EmulatorLifecycleConfiguration): Boolean {
        val directory = configuration.ownedAvdDirectory ?: return false
        val marker = directory.resolve(".droidproof-owned.json")
        return Files.isDirectory(directory, LinkOption.NOFOLLOW_LINKS) &&
            Files.isRegularFile(marker, LinkOption.NOFOLLOW_LINKS) && !Files.isSymbolicLink(marker)
    }

    private fun parseDevices(result: io.github.fredleonam.droidproof.device.CommandResult): Map<String, String> {
        if (result.failure != null || result.exitCode != 0) {
            throw EmulatorLifecycleException(
                "Could not discover ADB devices before launch.",
            )
        }
        val lines = result.stdout.replace("\r\n", "\n").lines()
        if (lines.firstOrNull()?.trim() != "List of devices attached") {
            throw EmulatorLifecycleException("ADB device discovery output was malformed.")
        }
        return buildMap {
            lines.drop(1).filter { it.isNotBlank() }.forEach { line ->
                val parts = line.split('\t')
                if (parts.size != 2 || !DEVICE_SERIAL.matches(parts[0]) || parts[1].trim() !in setOf("device", "offline", "unauthorized")) {
                    throw EmulatorLifecycleException("ADB device discovery output was malformed.")
                }
                put(parts[0], parts[1].trim())
            }
        }
    }

    private fun terminate(
        process: Process,
        timeoutMillis: Long,
    ): Result<Unit> =
        runCatching {
            if (!process.isAlive) return@runCatching
            var interrupted = false

            fun waitBounded(millis: Long) {
                try {
                    process.waitFor(millis.coerceAtLeast(1), TimeUnit.MILLISECONDS)
                } catch (_: InterruptedException) {
                    interrupted = true
                }
            }
            waitBounded(timeoutMillis / 3)
            if (process.isAlive) {
                process.destroy()
                waitBounded(timeoutMillis / 3)
            }
            if (process.isAlive) {
                process.destroyForcibly()
                waitBounded(timeoutMillis - (timeoutMillis / 3 * 2))
            }
            if (interrupted) Thread.currentThread().interrupt()
            if (process.isAlive) throw EmulatorLifecycleException("Owned emulator process exit could not be confirmed.")
        }

    private fun remaining(deadline: Long) = ((deadline - System.nanoTime()) / 1_000_000).coerceAtLeast(1)

    private fun error(message: String): Nothing = throw EmulatorLifecycleException(message)

    private inner class Session(
        override val serial: String,
        private val process: Process,
        private val configuration: EmulatorLifecycleConfiguration,
        private val runner: CommandRunner,
    ) : OwnedProcessSession {
        private var closed = false
        override var terminationConfirmed: Boolean = !process.isAlive
            private set

        override fun close() {
            if (closed) return
            closed = true
            if (!process.isAlive) {
                terminationConfirmed = true
                return
            }
            runner.execute(
                CommandRequest(
                    listOf(configuration.adbPath.toString(), "-s", serial, "emu", "kill"),
                    configuration.shutdownTimeoutMillis,
                    environment = configuration.environment,
                ),
            )
            val termination = terminate(process, configuration.shutdownTimeoutMillis)
            terminationConfirmed = !process.isAlive
            if (termination.isFailure || !terminationConfirmed) {
                throw EmulatorLifecycleException(
                    "Owned emulator $serial exit could not be confirmed.",
                    termination.exceptionOrNull(),
                )
            }
            // ADB is only a graceful request. Its failure does not negate confirmed child exit.
        }
    }
}
