package io.github.fredleonam.droidproof.host

import io.github.fredleonam.droidproof.device.CommandRequest
import io.github.fredleonam.droidproof.device.CommandRunner
import io.github.fredleonam.droidproof.device.ProcessCommandRunner
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

interface EmulatorLifecycleManager {
    fun start(configuration: EmulatorLifecycleConfiguration): ManagedEmulatorSession
}

class EmulatorLifecycleException(message: String, cause: Throwable? = null) : RuntimeException(message, cause)

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
        if (before.stdout.lineSequence().any { it.startsWith("$serial\t") }) {
            throw EmulatorLifecycleException("Target emulator serial is already present before launch.")
        }
        val process =
            try {
                launcher.launch(
                    EmulatorLaunchRequest(
                        listOf(
                            configuration.emulatorPath.toString(),
                            "-avd",
                            avd,
                            "-wipe-data",
                            "-no-snapshot",
                            "-port",
                            configuration.port.toString(),
                        ),
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
                val state = devices.stdout.lineSequence().firstOrNull { it.startsWith("$serial\t") }?.substringAfter('\t')?.trim()
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
                            sleeper,
                        )
                    }
                }
                sleeper(100)
            }
            throw EmulatorLifecycleException("Timed out waiting for ADB and Android boot readiness for $serial.")
        } catch (e: InterruptedException) {
            process.destroyForcibly()
            Thread.currentThread().interrupt()
            throw e
        } catch (e: Throwable) {
            process.destroyForcibly()
            throw e
        }
    }

    private fun remaining(deadline: Long) = ((deadline - System.nanoTime()) / 1_000_000).coerceAtLeast(1)

    private fun error(message: String): Nothing = throw EmulatorLifecycleException(message)

    private class Session(
        override val serial: String,
        private val process: Process,
        private val configuration: EmulatorLifecycleConfiguration,
        private val runner: CommandRunner,
        private val sleeper: (Long) -> Unit,
    ) : ManagedEmulatorSession {
        override fun close() {
            if (!process.isAlive) return
            val result =
                runner.execute(
                    CommandRequest(
                        listOf(configuration.adbPath.toString(), "-s", serial, "emu", "kill"),
                        configuration.shutdownTimeoutMillis,
                        environment = configuration.environment,
                    ),
                )
            val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(configuration.shutdownTimeoutMillis)
            while (process.isAlive && System.nanoTime() < deadline) sleeper(100)
            if (process.isAlive) {
                process.destroy()
                process.waitFor(200, TimeUnit.MILLISECONDS)
            }
            if (process.isAlive) {
                process.destroyForcibly()
                process.waitFor(1000, TimeUnit.MILLISECONDS)
            }
            if (result.failure != null || result.exitCode != 0 || process.isAlive) {
                throw EmulatorLifecycleException(
                    "Owned emulator $serial did not shut down cleanly.",
                )
            }
        }
    }
}
