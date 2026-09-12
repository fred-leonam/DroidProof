package io.github.fredleonam.droidproof.host

import io.github.fredleonam.droidproof.device.CommandRequest
import io.github.fredleonam.droidproof.device.CommandResult
import io.github.fredleonam.droidproof.device.CommandRunner
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SmokeAdbClientTest {
    @TempDir
    lateinit var directory: Path

    @Test
    fun `preflight requires selected emulator primary user and scopes every device command`() {
        val requests = mutableListOf<CommandRequest>()
        val runner =
            CommandRunner { request ->
                requests += request
                when {
                    "devices" in request.arguments -> CommandResult(stdout = "List of devices attached\nemulator-5554 device\n")
                    request.arguments.last() == "ro.kernel.qemu" -> CommandResult(stdout = "1\n")
                    "get-current-user" in request.arguments -> CommandResult(stdout = "0\n")
                    else -> CommandResult(stdout = "usage: uiautomator dump")
                }
            }

        val result = SmokeAdbClient(Path.of("/fake/adb"), runner).preflight("emulator-5554", 1000)

        assertTrue(result.isSuccessful)
        assertTrue(requests.drop(1).all { it.arguments.take(3) == listOf("/fake/adb", "-s", "emulator-5554") })
    }

    @Test
    fun `activity manager error is a launch failure despite zero process exit`() {
        val runner = CommandRunner { CommandResult(stdout = "Starting: Intent\nError: Activity class does not exist.\n", exitCode = 0) }

        val result =
            SmokeAdbClient(Path.of("/fake/adb"), runner)
                .launch("emulator-5554", "io.droidproof.smoke/io.droidproof.smoke.MainActivity", 1000)

        assertEquals(DeviceFailureKind.INVALID_OUTPUT, result.failure)
    }

    @Test
    fun `package inspection preserves split paths and rejects unsafe output`() {
        var output = "package:/data/app/one/base.apk\npackage:/data/app/two/base.apk\n"
        val client = SmokeAdbClient(Path.of("/fake/adb"), CommandRunner { CommandResult(stdout = output) })

        val split = client.packagePaths("emulator-5554", "io.droidproof.smoke", 1000)
        output = "package:../../host.apk\n"
        val unsafe = client.packagePaths("emulator-5554", "io.droidproof.smoke", 1000)

        assertEquals(2, split.value?.paths?.size)
        assertEquals(DeviceFailureKind.INVALID_OUTPUT, unsafe.failure)
    }

    @Test
    fun `hierarchy retrieval deletes stale local bytes and applies output bound`() {
        val destination = directory.resolve("hierarchy.xml")
        Files.writeString(destination, "stale")
        val requests = mutableListOf<CommandRequest>()
        val runner =
            CommandRunner { request ->
                requests += request
                when {
                    "uiautomator" in request.arguments -> CommandResult(stdout = "UI hierchary dumped to: /sdcard/Download/file.xml")
                    else -> CommandResult()
                }
            }
        val client = SmokeAdbClient(Path.of("/fake/adb"), runner)

        val result =
            client.dumpHierarchy(
                "emulator-5554",
                "/sdcard/Download/droidproof-attempt.xml",
                destination,
                1000,
                32,
            )

        assertEquals(DeviceFailureKind.INVALID_OUTPUT, result.failure)
        assertFalse(Files.exists(destination))
        assertEquals(32, requests.single { "cat" in it.arguments }.stdoutLimitBytes)
        assertTrue(requests.single { "rm" in it.arguments }.arguments.last().startsWith("/sdcard/Download/droidproof-"))
    }
}
