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
    fun `transaction commands snapshot apply and restore exact state with serial scoped arguments`() {
        val requests = mutableListOf<CommandRequest>()
        val client =
            SmokeAdbClient(
                Path.of("/fake/adb"),
                CommandRunner { request ->
                    requests += request
                    CommandResult(
                        stdout =
                            when (request.arguments.last()) {
                                "persist.sys.locale" -> "en-US\n"
                                "accelerometer_rotation" -> "1\n"
                                "user_rotation" -> "3\n"
                                "window_animation_scale" -> "0.25\n"
                                "transition_animation_scale" -> "0.5\n"
                                "animator_duration_scale" -> "1.0\n"
                                else -> ""
                            },
                    )
                },
            )
        val snapshot = requireNotNull(client.snapshotEnvironment("emulator-5554", 1_000).value)
        assertEquals("en-US", snapshot.locale)
        assertEquals(1, snapshot.accelerometerRotation)
        assertEquals(3, snapshot.userRotation)
        val contract =
            io.github.fredleonam.droidproof.model.EmulatorEnvironmentContractV1(
                1,
                "en-US",
                io.github.fredleonam.droidproof.model.Orientation.LANDSCAPE,
                io.github.fredleonam.droidproof.model.AnimationConfiguration(0.0, 0.5, 1.0),
            )
        assertTrue(client.applyEnvironment("emulator-5554", contract, 1_000).isSuccessful)
        assertTrue(client.restoreEnvironment("emulator-5554", snapshot, 1_000).isSuccessful)
        assertTrue(requests.all { it.arguments.take(3) == listOf("/fake/adb", "-s", "emulator-5554") })
        val mutations = requests.drop(6).map { it.arguments.drop(3) }
        assertEquals(listOf("shell", "cmd", "locale", "set", "en-US"), mutations[0])
        assertEquals("0", mutations[1].last())
        assertEquals("1", mutations[2].last())
        assertEquals(listOf("shell", "cmd", "locale", "set", "en-US"), mutations[6])
        assertEquals("1", mutations[7].last())
        assertEquals("3", mutations[8].last())
        assertEquals(listOf("0.25", "0.5", "1.0"), mutations.drop(9).map { it.last() })
    }

    @Test
    fun `transaction fails closed and stops after private command failure`() {
        val requests = mutableListOf<CommandRequest>()
        val client =
            SmokeAdbClient(
                Path.of("/fake/adb"),
                CommandRunner { request ->
                    requests += request
                    CommandResult(stderr = "private stderr")
                },
            )
        val contract =
            io.github.fredleonam.droidproof.model.EmulatorEnvironmentContractV1(
                1,
                "en-US",
                io.github.fredleonam.droidproof.model.Orientation.PORTRAIT,
                io.github.fredleonam.droidproof.model.AnimationConfiguration(0.0, 0.0, 0.0),
            )
        val result = client.applyEnvironment("emulator-5554", contract, 1_000)
        assertEquals(DeviceFailureKind.UNSUPPORTED, result.failure)
        assertFalse(result.detail.orEmpty().contains("private"))
        assertEquals(1, requests.size)
        val malformed =
            SmokeAdbClient(Path.of("/fake/adb"), CommandRunner { CommandResult(stdout = "NaN\n") })
                .snapshotEnvironment("emulator-5554", 1_000)
        assertEquals(DeviceFailureKind.INVALID_OUTPUT, malformed.failure)
    }

    @Test
    fun `environment observations use serial scoped bounded argument lists and parse supported values`() {
        val requests = mutableListOf<CommandRequest>()
        val client =
            SmokeAdbClient(
                Path.of("/fake/adb with spaces"),
                CommandRunner { request ->
                    requests += request
                    val key = request.arguments.last()
                    CommandResult(
                        stdout =
                            when (key) {
                                "persist.sys.locale" -> "en-US\n"
                                "accelerometer_rotation" -> "0\n"
                                "user_rotation" -> "3\n"
                                "window_animation_scale" -> "0\n"
                                "transition_animation_scale" -> "0.5\n"
                                "animator_duration_scale" -> "1.0\n"
                                else -> error("unexpected command")
                            },
                    )
                },
            )

        assertEquals("en-US", client.observeLocale("emulator-5554", 123).value?.normalized)
        assertEquals(
            io.github.fredleonam.droidproof.model.Orientation.LANDSCAPE,
            client.observeOrientation("emulator-5554", 123).value?.orientation,
        )
        assertEquals(DeviceAnimationObservations(0.0, 0.5, 1.0), client.observeAnimations("emulator-5554", 123).value)
        assertTrue(requests.all { it.arguments.take(3) == listOf("/fake/adb with spaces", "-s", "emulator-5554") })
        assertTrue(requests.all { it.stdoutLimitBytes == 1024L && it.stderrLimitBytes == 65_536L })
        assertEquals(
            listOf("window_animation_scale", "transition_animation_scale", "animator_duration_scale"),
            requests.takeLast(3).map { it.arguments.last() },
        )
    }

    @Test
    fun `environment observations reject auto rotation malformed private and unexpected output without leaking it`() {
        val outputs =
            listOf(
                CommandResult(stdout = "1\n") to DeviceFailureKind.UNSUPPORTED,
                CommandResult(stdout = "private value\n") to DeviceFailureKind.INVALID_OUTPUT,
                CommandResult(stderr = "private stderr") to DeviceFailureKind.INVALID_OUTPUT,
                CommandResult(stdout = "NaN\n") to DeviceFailureKind.INVALID_OUTPUT,
            )
        val auto = SmokeAdbClient(Path.of("/fake/adb"), CommandRunner { outputs[0].first }).observeOrientation("emulator-5554", 100)
        assertEquals(outputs[0].second, auto.failure)
        for ((response, expected) in outputs.drop(1)) {
            val result = SmokeAdbClient(Path.of("/fake/adb"), CommandRunner { response }).observeAnimations("emulator-5554", 100)
            assertEquals(expected, result.failure)
            assertFalse(result.detail.orEmpty().contains("private"))
        }
        val missing =
            SmokeAdbClient(Path.of("/fake/adb"), CommandRunner { CommandResult(stdout = "null\n") })
                .observeLocale("emulator-5554", 100)
        assertEquals(DeviceFailureKind.INVALID_OUTPUT, missing.failure)
        assertFailsWith<IllegalArgumentException> {
            SmokeAdbClient(Path.of("/fake/adb"), CommandRunner { CommandResult() }).observeLocale("bad;serial", 100)
        }
    }

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
        val requests = mutableListOf<CommandRequest>()
        val client =
            SmokeAdbClient(
                Path.of("/fake/adb"),
                CommandRunner {
                    requests += it
                    if ("list" in it.arguments) {
                        CommandResult(stdout = "package:io.droidproof.smoke\n")
                    } else {
                        CommandResult(stdout = output)
                    }
                },
            )

        val split = client.packagePaths("emulator-5554", "io.droidproof.smoke", 1000)
        output = "package:../../host.apk\n"
        val unsafe = client.packagePaths("emulator-5554", "io.droidproof.smoke", 1000)

        assertEquals(2, split.value?.paths?.size)
        assertEquals(DeviceFailureKind.INVALID_OUTPUT, unsafe.failure)
        assertEquals(
            listOf(
                "/fake/adb", "-s", "emulator-5554", "shell", "pm", "list", "packages", "--user", "0",
                "io.droidproof.smoke",
            ),
            requests[0].arguments,
        )
        assertEquals(
            listOf("/fake/adb", "-s", "emulator-5554", "shell", "pm", "path", "io.droidproof.smoke"),
            requests[1].arguments,
        )
    }

    @Test
    fun `absent package is detected before platform-specific pm path failure`() {
        val requests = mutableListOf<CommandRequest>()
        val client =
            SmokeAdbClient(
                Path.of("/fake/adb"),
                CommandRunner {
                    requests += it
                    CommandResult()
                },
            )

        val result = client.packagePaths("emulator-5554", "io.droidproof.smoke", 1000)

        assertTrue(result.isSuccessful)
        assertTrue(requireNotNull(result.value).paths.isEmpty())
        assertEquals(1, requests.size)
        assertTrue("list" in requests.single().arguments)
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

    @Test
    fun `ADB reverse setup and cleanup are serial scoped bounded and validated`() {
        val requests = mutableListOf<CommandRequest>()
        val client =
            SmokeAdbClient(
                Path.of("/fake/adb"),
                CommandRunner {
                    requests += it
                    if ("--remove" in it.arguments) CommandResult() else CommandResult(stdout = "38637\n")
                },
            )

        assertTrue(client.reverseTcp("emulator-5554", 38637, 43210, 321).isSuccessful)
        assertTrue(client.removeReverseTcp("emulator-5554", 38637, 654).isSuccessful)
        assertEquals(
            listOf("/fake/adb", "-s", "emulator-5554", "reverse", "tcp:38637", "tcp:43210"),
            requests[0].arguments,
        )
        assertEquals(
            listOf("/fake/adb", "-s", "emulator-5554", "reverse", "--remove", "tcp:38637"),
            requests[1].arguments,
        )
        assertEquals(listOf(321L, 654L), requests.map { it.timeoutMillis })
        assertFailsWith<IllegalArgumentException> { client.reverseTcp("emulator-5554", 0, 43210, 100) }
        assertFailsWith<IllegalArgumentException> { client.removeReverseTcp("bad;serial", 38637, 100) }
    }
}
