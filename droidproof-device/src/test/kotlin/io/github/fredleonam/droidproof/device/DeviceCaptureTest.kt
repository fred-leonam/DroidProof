package io.github.fredleonam.droidproof.device

import io.github.fredleonam.droidproof.evidence.EvidenceBundleRequest
import io.github.fredleonam.droidproof.evidence.EvidenceBundleVerifier
import io.github.fredleonam.droidproof.evidence.EvidenceBundleWriter
import io.github.fredleonam.droidproof.evidence.EvidenceFileInput
import io.github.fredleonam.droidproof.model.AndroidArtifactIdentity
import io.github.fredleonam.droidproof.model.AndroidArtifactType
import io.github.fredleonam.droidproof.model.AnimationConfiguration
import io.github.fredleonam.droidproof.model.BundleId
import io.github.fredleonam.droidproof.model.DeviceInformation
import io.github.fredleonam.droidproof.model.DroidProofVersion
import io.github.fredleonam.droidproof.model.EnvironmentContract
import io.github.fredleonam.droidproof.model.EventId
import io.github.fredleonam.droidproof.model.EventSource
import io.github.fredleonam.droidproof.model.EvidenceBundleManifest
import io.github.fredleonam.droidproof.model.EvidenceReference
import io.github.fredleonam.droidproof.model.Orientation
import io.github.fredleonam.droidproof.model.ScenarioId
import io.github.fredleonam.droidproof.model.ScenarioIdentity
import io.github.fredleonam.droidproof.model.Sha256
import io.github.fredleonam.droidproof.model.TimelineEvent
import io.github.fredleonam.droidproof.model.UtcTimestamp
import org.junit.jupiter.api.io.TempDir
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.nio.file.Files
import java.nio.file.Path
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DeviceCaptureTest {
    @TempDir
    lateinit var directory: Path
    private val clock = Clock.fixed(Instant.parse("2026-09-08T12:00:00Z"), ZoneOffset.UTC)

    @Test
    fun `parses attributes and refuses ambiguous or unavailable devices without skipping`() {
        val devices =
            DeviceSelector.parse(
                "List of devices attached\n\na device product:p model:Pixel transport_id:1\nb unauthorized\nc offline\n",
            )
        assertEquals(3, devices.size)
        assertEquals("Pixel", devices.first().attributes["model"])
        assertEquals(CollectionIssueCode.AMBIGUOUS_DEVICE, DeviceSelector.select(devices, null).issue?.code)
        assertEquals(CollectionIssueCode.UNAUTHORIZED, DeviceSelector.select(devices, "b").issue?.code)
        assertEquals(CollectionIssueCode.OFFLINE, DeviceSelector.select(devices, "c").issue?.code)
        assertEquals(CollectionIssueCode.DISCONNECTED, DeviceSelector.select(devices, "missing").issue?.code)
        assertEquals(CollectionIssueCode.NO_DEVICES, DeviceSelector.select(emptyList(), null).issue?.code)
        assertEquals("a", DeviceSelector.select(devices, "a").device?.serial)
        assertEquals(CollectionIssueCode.UNAUTHORIZED, DeviceSelector.select(listOf(devices[1]), null).issue?.code)
        assertEquals(CollectionIssueCode.OFFLINE, DeviceSelector.select(listOf(devices[2]), null).issue?.code)
        assertEquals(CollectionIssueCode.UNAVAILABLE, DeviceSelector.select(DeviceSelector.parse("x recovery"), null).issue?.code)
    }

    @Test
    fun `adb resolution respects explicit sdk and path precedence including spaces`() {
        fun executable(path: Path): Path {
            Files.createDirectories(path.parent)
            Files.writeString(path, "test placeholder")
            assertTrue(path.toFile().setExecutable(true))
            return path.toAbsolutePath()
        }
        val explicit = executable(directory.resolve("tools with spaces/adb"))
        val sdk = directory.resolve("sdk")
        val sdkAdb = executable(sdk.resolve("platform-tools/adb"))
        val legacy = directory.resolve("legacy")
        val legacyAdb = executable(legacy.resolve("platform-tools/adb"))
        val env = mapOf("ANDROID_HOME" to sdk.toString(), "ANDROID_SDK_ROOT" to legacy.toString(), "PATH" to explicit.parent.toString())
        assertEquals(explicit, AdbPathResolver.resolve(explicit.toString(), env, false))
        assertEquals(sdkAdb, AdbPathResolver.resolve(null, env, false))
        assertEquals(legacyAdb, AdbPathResolver.resolve(null, env - "ANDROID_HOME", false))
        assertEquals(explicit, AdbPathResolver.resolve(null, env - "ANDROID_HOME" - "ANDROID_SDK_ROOT", false))
        assertFailsWith<IllegalArgumentException> { AdbPathResolver.resolve("/absent/adb", env, false) }
        assertFailsWith<IllegalArgumentException> { AdbPathResolver.resolve(null, emptyMap(), false) }
    }

    @Test
    fun `capture preserves bytes emits deterministic observations and uses only scoped allowlisted commands`() {
        val fake = FakeAdb()
        val result = collector(fake).capture(CaptureRequest(directory, serial = "test-1"))
        assertEquals(CaptureStatus.SUCCESS, result.document.status)
        assertEquals("fixed-id", result.document.captureId)
        assertEquals(clock.instant().toString(), result.document.hostStartedAt)
        assertEquals(result.document.hostStartedAt, result.document.hostEndedAt)
        assertEquals("synthetic/fingerprint", result.document.metadata["buildFingerprint"]?.value)
        assertContentEquals(fake.png, Files.readAllBytes(result.files.single().source))
        assertEquals("screenshots/display.png", result.files.single().destination.value)
        val json = Files.readString(result.directory.resolve("capture.json"))
        assertFalse(json.contains(directory.toString()))
        assertFalse(json.contains("artifact"))
        assertTrue(fake.requests.none { "logcat" in it.arguments })
        assertTrue(fake.requests.drop(1).all { it.arguments.take(3) == listOf("/tools with spaces/adb", "-s", "test-1") })
        assertEquals(listOf("exec-out", "screencap", "-p"), fake.requests.last().arguments.drop(3))
        val second = collector(FakeAdb()).capture(CaptureRequest(directory, serial = "test-1"))
        assertEquals(result.document, second.document)
        assertTrue(second.directory != result.directory)
    }

    @Test
    fun `empty truncated corrupt and failed screenshots never publish a completed file`() {
        for (variant in listOf("empty", "truncated", "corrupt", "failed", "limit")) {
            val fake = FakeAdb(screenshot = variant)
            val result = collector(fake).capture(CaptureRequest(directory))
            assertEquals(CaptureStatus.FAILED, result.document.status, variant)
            assertTrue(result.files.isEmpty())
            assertFalse(Files.exists(result.directory.resolve("screenshots/display.png")))
            Files.walk(result.directory).use { paths -> assertFalse(paths.anyMatch { it.toString().endsWith(".tmp") }) }
        }
    }

    @Test
    fun `logs require opt in and positive PID and retain screenshot on distinct log outcomes`() {
        assertFailsWith<IllegalArgumentException> { CaptureRequest(directory, includeLogcat = true) }
        assertFailsWith<IllegalArgumentException> { CaptureRequest(directory, includeLogcat = true, pid = 0) }
        assertFailsWith<IllegalArgumentException> { CaptureRequest(directory, pid = 10) }
        assertFailsWith<IllegalArgumentException> { CaptureRequest(directory, serial = "a;reboot") }
        for ((mode, expected) in mapOf(
            "ok" to CollectionOutcome.SUCCESS,
            "empty" to CollectionOutcome.EMPTY,
            "unsupported" to CollectionOutcome.UNSUPPORTED,
            "failed" to CollectionOutcome.FAILED,
            "limit" to CollectionOutcome.TRUNCATED,
        )) {
            val fake = FakeAdb(log = mode)
            val result = collector(fake).capture(CaptureRequest(directory, includeLogcat = true, pid = 123))
            assertEquals(expected, result.document.logcat)
            assertEquals(if (mode == "ok") CaptureStatus.SUCCESS else CaptureStatus.PARTIAL, result.document.status)
            assertTrue(Files.exists(result.directory.resolve("screenshots/display.png")))
            assertEquals(mode == "ok", Files.exists(result.directory.resolve("logs/logcat.txt")))
            val logRequests = fake.requests.filter { "logcat" in it.arguments }
            assertTrue(logRequests.all { "--help" in it.arguments || "--pid=123" in it.arguments })
            assertTrue(logRequests.filterNot { "--help" in it.arguments }.all { "-d" in it.arguments })
        }
    }

    @Test
    fun `unavailable metadata and disconnected devices are explicit`() {
        val fake = FakeAdb(metadata = "")
        val partial = collector(fake).capture(CaptureRequest(directory))
        assertEquals(CaptureStatus.PARTIAL, partial.document.status)
        assertTrue(partial.document.metadata.values.all { it.value == null && it.reason != null })
        val disconnected = FakeAdb(screenshot = "disconnected")
        val failed = collector(disconnected).capture(CaptureRequest(directory))
        assertTrue(failed.document.issues.any { it.code == CollectionIssueCode.DISCONNECTED })
        val missingCommand = collector(FakeAdb(screenshot = "missing-command")).capture(CaptureRequest(directory))
        assertTrue(missingCommand.document.issues.any { it.code == CollectionIssueCode.NONZERO_EXIT })
        assertFalse(missingCommand.document.issues.any { it.code == CollectionIssueCode.DISCONNECTED })
    }

    @Test
    fun `selection and listing failures write a failed document without targeted commands`() {
        for (listing in listOf(
            CommandResult(stdout = "List of devices attached\n"),
            CommandResult(stdout = "x unauthorized\n"),
            CommandResult(stdout = "x offline\n"),
            CommandResult(stdout = "x device\ny offline\n"),
            CommandResult(failure = CommandFailure.LAUNCH),
        )) {
            var calls = 0
            val runner =
                CommandRunner {
                    calls++
                    assertEquals(listOf("/fake/adb", "devices", "-l"), it.arguments)
                    listing
                }
            val result =
                DeviceCollector(AdbClient(Path.of("/fake/adb"), runner), clock) { "selection" }
                    .capture(CaptureRequest(directory))
            assertEquals(1, calls)
            assertEquals(CaptureStatus.FAILED, result.document.status)
            assertTrue(result.files.isEmpty())
            assertTrue(Files.exists(result.directory.resolve("capture.json")))
        }
    }

    @Test
    fun `invalid metadata and command limits propagate through the capture API`() {
        val requests = mutableListOf<CommandRequest>()
        val fake = FakeAdb()
        val runner =
            CommandRunner {
                requests += it
                if (it.arguments.last() == "ro.build.version.sdk") CommandResult(stdout = "not-an-api") else fake.execute(it)
            }
        val limits = CaptureLimits(500, 1000, 2000, 3000)
        val result =
            DeviceCollector(AdbClient(Path.of("/fake/adb"), runner), clock) { "limits" }
                .capture(CaptureRequest(directory, includeLogcat = true, pid = 123, limits = limits))
        assertEquals(CaptureStatus.PARTIAL, result.document.status)
        assertEquals(null, result.document.metadata["apiLevel"]?.value)
        assertTrue(requests.all { it.timeoutMillis == 500L && it.stderrLimitBytes == 1000L })
        assertEquals(2000L, requests.single { "screencap" in it.arguments }.stdoutLimitBytes)
        assertEquals(3000L, requests.single { "--pid=123" in it.arguments }.stdoutLimitBytes)
    }

    @Test
    fun `encoded logs cannot exceed the configured published byte limit`() {
        val result =
            collector(FakeAdb()).capture(
                CaptureRequest(directory, includeLogcat = true, pid = 123, limits = CaptureLimits(logcatLimitBytes = 4)),
            )
        assertEquals(CollectionOutcome.TRUNCATED, result.document.logcat)
        assertEquals(CaptureStatus.PARTIAL, result.document.status)
        assertFalse(Files.exists(result.directory.resolve("logs/logcat.txt")))
    }

    @Test
    fun `offline capture maps to evidence v2 with every reference resolving`() {
        val capture = collector(FakeAdb()).capture(CaptureRequest(directory, includeLogcat = true, pid = 123))
        // All application/scenario/environment values below are explicitly synthetic test data.
        val synthetic =
            EvidenceBundleManifest(
                2,
                BundleId("synthetic-capture-test"),
                UtcTimestamp(clock.instant().toString()),
                AndroidArtifactIdentity(AndroidArtifactType.APK, Sha256("a".repeat(64))),
                scenario = ScenarioIdentity(ScenarioId("synthetic-scenario"), Sha256("b".repeat(64))),
                environment =
                    EnvironmentContract(
                        DeviceInformation("synthetic", 35),
                        "en-US",
                        Orientation.PORTRAIT,
                        AnimationConfiguration(0.0, 0.0, 0.0),
                        1,
                    ),
                droidProofVersion = DroidProofVersion("0.1.0"),
            )
        val event =
            TimelineEvent(
                EventId("synthetic-observation"),
                synthetic.createdAt,
                EventSource.HOST,
                "synthetic.capture",
                evidence = capture.files.map { EvidenceReference(it.destination, it.mediaType) },
            )
        val bundle =
            EvidenceBundleWriter().write(
                EvidenceBundleRequest(
                    synthetic,
                    listOf(event),
                    capture.files.map { EvidenceFileInput(it.source, it.destination, it.mediaType, it.role) },
                ),
                directory.resolve("bundle"),
            )
        assertTrue(EvidenceBundleVerifier().verify(bundle).isValid)
        assertEquals(2, EvidenceBundleVerifier().verify(bundle).schemaVersion)
        capture.files.forEach { assertTrue(Files.isRegularFile(bundle.resolve(it.destination.value))) }
    }

    private fun collector(fake: FakeAdb) = DeviceCollector(AdbClient(Path.of("/tools with spaces/adb"), fake), clock) { "fixed-id" }
}

private class FakeAdb(
    val screenshot: String = "ok",
    val log: String = "ok",
    val metadata: String = "synthetic/fingerprint",
) : CommandRunner {
    val requests = mutableListOf<CommandRequest>()
    val png = ByteArrayOutputStream().also { ImageIO.write(BufferedImage(2, 2, BufferedImage.TYPE_INT_RGB), "png", it) }.toByteArray()

    override fun execute(request: CommandRequest): CommandResult {
        requests += request
        val args = request.arguments
        if ("devices" in args) return CommandResult(stdout = "List of devices attached\ntest-1 device model:Test\n")
        if ("getprop" in args) {
            return CommandResult(
                stdout = if (args.last() == "ro.build.version.sdk" && metadata.isNotEmpty()) "35\n" else metadata,
            )
        }
        if ("--help" in args) return CommandResult(stdout = if (log == "unsupported") "-d dump" else "--pid=PID -d dump")
        if ("logcat" in args) {
            return when (log) {
                "failed" -> CommandResult(failure = CommandFailure.NONZERO_EXIT)
                "limit" -> CommandResult(stdout = "partial", failure = CommandFailure.OUTPUT_LIMIT)
                "empty" -> CommandResult()
                else -> CommandResult(stdout = "123 synthetic log\n")
            }
        }
        val bytes =
            when (screenshot) {
                "empty" -> byteArrayOf()
                "truncated" -> png.copyOf(png.size - 5)
                "corrupt" -> png.copyOf().also { it[30] = (it[30].toInt() xor 1).toByte() }
                else -> png
            }
        Files.write(requireNotNull(request.stdoutFile), bytes)
        return when (screenshot) {
            "failed" -> CommandResult(failure = CommandFailure.NONZERO_EXIT)
            "limit" -> CommandResult(failure = CommandFailure.OUTPUT_LIMIT)
            "disconnected" -> CommandResult(stderr = "error: device 'test-1' not found", failure = CommandFailure.NONZERO_EXIT)
            "missing-command" -> CommandResult(stderr = "screencap: not found", failure = CommandFailure.NONZERO_EXIT)
            else -> CommandResult()
        }
    }
}
