package io.github.fredleonam.droidproof.host

import io.github.fredleonam.droidproof.device.CommandResult
import io.github.fredleonam.droidproof.device.CommandRunner
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class EmulatorLifecycleTest {
    private fun config(
        serial: String? = null,
        avd: String? = null,
    ) = EmulatorLifecycleConfiguration(
        serial,
        avd,
        Path.of("emulator"),
        Path.of("adb"),
        startupTimeoutMillis = 100,
        shutdownTimeoutMillis = 100,
    )

    @Test
    fun `configuration requires exactly one target`() {
        assertFailsWith<IllegalArgumentException> { config() }
        assertFailsWith<IllegalArgumentException> { config("emulator-5554", "pixel") }
    }

    @Test
    fun `missing avd fails before process creation`() {
        var created = false
        val manager =
            LegacyEmulatorLifecycleManager(
                runner =
                    CommandRunner { request ->
                        assertEquals(listOf("emulator", "-list-avds"), request.arguments)
                        CommandResult(stdout = "other\n", exitCode = 0)
                    },
                launcher =
                    EmulatorProcessLauncher {
                        created = true
                        error("must not create")
                    },
            )
        assertFailsWith<EmulatorLifecycleException> { manager.start(config(avd = "pixel")) }
        assertEquals(false, created)
    }

    @Test
    fun `external session is non owning`() {
        val session =
            object : ManagedEmulatorSession {
                override val serial = requireNotNull(config("emulator-5554").deviceSerial)

                override fun close() = Unit
            }
        session.close()
        assertEquals("emulator-5554", session.serial)
    }
}
