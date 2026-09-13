package io.github.fredleonam.droidproof.host

import io.github.fredleonam.droidproof.device.CommandRequest
import io.github.fredleonam.droidproof.device.CommandResult
import io.github.fredleonam.droidproof.device.CommandRunner
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
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

    @Test
    fun `tap uses serial scoped argument list bounded timeout and rejects unsafe inputs`() {
        val requests = mutableListOf<CommandRequest>()
        val client =
            SmokeAdbClient(
                Path.of("/fake/adb with spaces"),
                CommandRunner {
                    requests += it
                    CommandResult()
                },
            )
        assertTrue(client.tap("emulator-5554", TapCoordinates(20, 40), 123).isSuccessful)
        assertEquals(
            listOf("/fake/adb with spaces", "-s", "emulator-5554", "shell", "input", "tap", "20", "40"),
            requests.single().arguments,
        )
        assertEquals(123L, requests.single().timeoutMillis)
        assertFailsWith<IllegalArgumentException> { client.tap("serial;command", TapCoordinates(0, 0), 123) }
        assertFailsWith<IllegalArgumentException> { TapCoordinates(-1, 0) }
        assertEquals(1, requests.size)
        val failed = SmokeAdbClient(Path.of("/fake/adb"), CommandRunner { CommandResult(stderr = "Error: private device text") })
        assertEquals(DeviceFailureKind.INVALID_OUTPUT, failed.tap("emulator-5554", TapCoordinates(1, 2), 123).failure)
    }

    @Test
    fun `text input is bounded validated serial scoped and argument based`() {
        val requests = mutableListOf<CommandRequest>()
        val client =
            SmokeAdbClient(
                Path.of("/fake/adb with spaces"),
                CommandRunner {
                    requests += it
                    CommandResult()
                },
            )
        assertTrue(client.inputText("emulator-5554", "AZaz09._-@", 123).isSuccessful)
        assertEquals(
            listOf("/fake/adb with spaces", "-s", "emulator-5554", "shell", "input", "text", "AZaz09._-@"),
            requests.single().arguments,
        )
        assertEquals(123L, requests.single().timeoutMillis)
        for (text in listOf("", "a".repeat(129), "a b", "%s", "a;id", "$(id)", "é", "\n", "\u0000")) {
            assertFailsWith<IllegalArgumentException> { client.inputText("emulator-5554", text, 123) }
        }
        assertFailsWith<IllegalArgumentException> { client.inputText("serial;id", "safe", 123) }
        for (timeout in listOf(0L, -1L, 3_600_001L)) {
            assertFailsWith<IllegalArgumentException> { client.inputText("emulator-5554", "safe", timeout) }
        }
        assertEquals(1, requests.size)
    }

    @Test
    fun `text input preserves device failure semantics without private output`() {
        val cases =
            listOf(
                CommandResult(stderr = "private stderr") to DeviceFailureKind.INVALID_OUTPUT,
                CommandResult(stdout = "private stderr") to DeviceFailureKind.INVALID_OUTPUT,
                CommandResult(
                    failure = io.github.fredleonam.droidproof.device.CommandFailure.NONZERO_EXIT,
                    stderr = "private stderr",
                ) to DeviceFailureKind.COMMAND,
                CommandResult(
                    failure = io.github.fredleonam.droidproof.device.CommandFailure.INTERRUPTED,
                    stderr = "private stderr",
                ) to DeviceFailureKind.CANCELLED,
                CommandResult(
                    failure = io.github.fredleonam.droidproof.device.CommandFailure.NONZERO_EXIT,
                    stderr = "offline private stderr",
                ) to DeviceFailureKind.DISCONNECTED,
            )
        for ((response, expected) in cases) {
            val result = SmokeAdbClient(Path.of("/fake/adb"), CommandRunner { response }).inputText("emulator-5554", "safe", 123)
            assertEquals(expected, result.failure)
            assertFalse(result.detail.orEmpty().contains("private stderr"))
        }
    }
}
