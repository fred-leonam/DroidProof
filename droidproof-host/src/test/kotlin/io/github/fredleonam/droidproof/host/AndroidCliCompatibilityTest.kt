package io.github.fredleonam.droidproof.host

import io.github.fredleonam.droidproof.device.CommandFailure
import io.github.fredleonam.droidproof.device.CommandRequest
import io.github.fredleonam.droidproof.device.CommandResult
import io.github.fredleonam.droidproof.device.CommandRunner
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class AndroidCliCompatibilityTest {
    @TempDir
    lateinit var directory: Path

    @Test
    fun `builder places documented global options before commands`() {
        val executable = Path.of("/tools/android")
        val sdk = Path.of("/sdk root")
        assertEquals(
            listOf("/tools/android", "--sdk=/sdk root", "--no-metrics", "--version"),
            AndroidCliCommandBuilder.version(executable, sdk, true).arguments,
        )
        assertEquals(
            listOf("/tools/android", "--sdk=/sdk root", "--no-metrics", "emulator", "create", "--help"),
            AndroidCliCommandBuilder.emulatorCommandHelp(executable, sdk, true, "create").arguments,
        )
        assertEquals(
            listOf("/tools/android", "--sdk=/sdk root", "emulator", "list"),
            AndroidCliCommandBuilder.emulatorList(executable, sdk, false).arguments,
        )
        assertEquals(
            listOf("/tools/android", "--sdk", "/sdk root", "--version"),
            AndroidCliCommandBuilder.version(
                executable,
                sdk,
                false,
                AndroidCliSdkOptionStyle.SEPARATE,
            ).arguments,
        )
    }

    @Test
    fun `missing non regular and non executable paths are distinguished without commands`() {
        val sdk = Files.createDirectory(directory.resolve("sdk"))
        val requests = mutableListOf<CommandRequest>()
        val probe =
            AndroidCliCompatibilityProbe(
                CommandRunner {
                    requests += it
                    successful(it)
                },
                { "Mac OS X" },
            )

        val missing = probe.inspect(AndroidCliProbeConfiguration(directory.resolve("missing"), sdk))
        assertEquals(AndroidCliCompatibilityOutcome.INCOMPATIBLE, missing.outcome)
        assertTrue(missing.issues.any { it.code == AndroidCliIssueCode.EXECUTABLE_MISSING })

        val notRegular = probe.inspect(AndroidCliProbeConfiguration(directory, sdk))
        assertTrue(notRegular.issues.any { it.code == AndroidCliIssueCode.EXECUTABLE_NOT_REGULAR })

        val notExecutable = directory.resolve("not-executable").also { it.writeText("tool") }
        assertTrue(notExecutable.toFile().setExecutable(false, false))
        val report = probe.inspect(AndroidCliProbeConfiguration(notExecutable, sdk))
        assertTrue(report.issues.any { it.code == AndroidCliIssueCode.EXECUTABLE_NOT_EXECUTABLE })
        assertTrue(requests.isEmpty())
    }

    @Test
    fun `successful read only surfaces remain fail closed on every required guarantee`() {
        val executable = executable()
        val sdk = Files.createDirectory(directory.resolve("sdk"))
        val requests = mutableListOf<CommandRequest>()
        val report =
            AndroidCliCompatibilityProbe(
                CommandRunner { request ->
                    requests += request
                    successful(request)
                },
                { "Mac OS X" },
            ).inspect(AndroidCliProbeConfiguration(executable, sdk, 4321))

        assertEquals("1.0.15985488", report.versionText)
        assertEquals("1.0.15985488", report.normalizedVersion)
        assertEquals(AndroidCliCapabilityStatus.SUPPORTED, report.hostOsSupport)
        assertEquals(AndroidCliCapabilityStatus.SUPPORTED, report.sdkSelection)
        assertTrue(
            listOf(
                report.commands.create,
                report.commands.list,
                report.commands.start,
                report.commands.stop,
                report.commands.remove,
            ).all { it == AndroidCliCapabilityStatus.SUPPORTED },
        )
        assertEquals(AndroidCliCompatibilityOutcome.UNVERIFIED, report.outcome)
        assertTrue(
            listOf(
                report.guarantees.exactImagePackageRevision,
                report.guarantees.isolatedOwnedState,
                report.guarantees.cleanStateStart,
                report.guarantees.deterministicSerialPortAssociation,
                report.guarantees.boundedStop,
                report.guarantees.safeOwnedRemoval,
            ).all { it == AndroidCliCapabilityStatus.UNVERIFIED },
        )
        assertTrue(requests.all { it.timeoutMillis == 4321L })
        assertTrue(requests.all { it.stdoutLimitBytes == 16_384L && it.stderrLimitBytes == 16_384L })
        assertTrue(requests.drop(1).all { it.arguments[1] == "--sdk=$sdk" })
        assertTrue(requests.drop(1).all { it.arguments.getOrNull(2) == "--no-metrics" })
        assertFalse(requests.any(::isMutating))
    }

    @Test
    fun `nonzero timeout process blank ambiguous and truncated observations never become supported`() {
        val executable = executable()
        val sdk = Files.createDirectory(directory.resolve("sdk"))
        val report =
            AndroidCliCompatibilityProbe(
                CommandRunner { request ->
                    val suffix = request.arguments.dropWhile { it != "emulator" }
                    when {
                        request.arguments.last() == "--version" ->
                            CommandResult(exitCode = 7, failure = CommandFailure.NONZERO_EXIT)
                        suffix == listOf("emulator", "--help") ->
                            CommandResult(stdout = "", exitCode = 0)
                        suffix == listOf("emulator", "create", "--help") ->
                            CommandResult(failure = CommandFailure.TIMEOUT)
                        suffix == listOf("emulator", "start", "--help") ->
                            CommandResult(failure = CommandFailure.LAUNCH)
                        suffix == listOf("emulator", "stop", "--help") ->
                            CommandResult(stdout = "", exitCode = 0)
                        suffix == listOf("emulator", "remove", "--help") ->
                            CommandResult(stdout = "unrelated successful output", exitCode = 0)
                        suffix == listOf("emulator", "list") ->
                            CommandResult(stdout = "partial", failure = CommandFailure.OUTPUT_LIMIT)
                        else -> successful(request)
                    }
                },
                { "Linux" },
            ).inspect(AndroidCliProbeConfiguration(executable, sdk))

        assertEquals(AndroidCliCapabilityStatus.INCOMPATIBLE, report.sdkSelection)
        assertEquals(AndroidCliCapabilityStatus.UNVERIFIED, report.commands.create)
        assertEquals(AndroidCliCapabilityStatus.UNVERIFIED, report.commands.list)
        assertEquals(AndroidCliCapabilityStatus.UNVERIFIED, report.commands.start)
        assertEquals(AndroidCliCapabilityStatus.UNVERIFIED, report.commands.stop)
        assertEquals(AndroidCliCapabilityStatus.UNVERIFIED, report.commands.remove)
        assertEquals(null, report.versionText)
        assertTrue(report.issues.any { it.code == AndroidCliIssueCode.VERSION_DISCOVERY_UNAVAILABLE })
        assertTrue(report.issues.any { it.code == AndroidCliIssueCode.EMULATOR_HELP_UNAVAILABLE })
    }

    @Test
    fun `blank list output is a valid empty read only result`() {
        val executable = executable()
        val sdk = Files.createDirectory(directory.resolve("sdk"))
        val report =
            AndroidCliCompatibilityProbe(
                CommandRunner { request ->
                    if (request.arguments.takeLast(2) == listOf("emulator", "list")) {
                        CommandResult(stdout = "", exitCode = 0)
                    } else {
                        successful(request)
                    }
                },
                { "Linux" },
            ).inspect(AndroidCliProbeConfiguration(executable, sdk))
        assertEquals(AndroidCliCapabilityStatus.SUPPORTED, report.commands.list)
        assertEquals(AndroidCliCapabilityStatus.SUPPORTED, report.sdkSelection)
    }

    @Test
    fun `installed help can require separate sdk form without inventing metrics support`() {
        val executable = executable()
        val sdk = Files.createDirectory(directory.resolve("sdk"))
        val requests = mutableListOf<CommandRequest>()
        val report =
            AndroidCliCompatibilityProbe(
                CommandRunner { request ->
                    requests += request
                    if (request.arguments == listOf(executable.toString(), "--help")) {
                        CommandResult(stdout = "Usage: android --sdk PATH [COMMAND]", exitCode = 0)
                    } else {
                        successful(request)
                    }
                },
                { "Linux" },
            ).inspect(AndroidCliProbeConfiguration(executable, sdk))

        assertEquals(AndroidCliCapabilityStatus.SUPPORTED, report.sdkSelection)
        assertTrue(requests.drop(1).all { it.arguments[1] == "--sdk" && it.arguments[2] == sdk.toString() })
        assertFalse(requests.any { "--no-metrics" in it.arguments })
    }

    @Test
    fun `missing command and Windows are incompatible without mutation`() {
        val executable = executable()
        val sdk = Files.createDirectory(directory.resolve("sdk"))
        val requests = mutableListOf<CommandRequest>()
        val report =
            AndroidCliCompatibilityProbe(
                CommandRunner { request ->
                    requests += request
                    if (request.arguments.takeLast(3) == listOf("emulator", "stop", "--help")) {
                        CommandResult(exitCode = 2, failure = CommandFailure.NONZERO_EXIT)
                    } else {
                        successful(request)
                    }
                },
                { "Windows 11" },
            ).inspect(AndroidCliProbeConfiguration(executable, sdk))

        assertEquals(AndroidCliCapabilityStatus.INCOMPATIBLE, report.hostOsSupport)
        assertEquals(AndroidCliCapabilityStatus.INCOMPATIBLE, report.commands.stop)
        assertEquals(AndroidCliCompatibilityOutcome.INCOMPATIBLE, report.outcome)
        assertTrue(report.issues.any { it.code == AndroidCliIssueCode.WINDOWS_EMULATOR_MANAGEMENT_DISABLED })
        assertFalse(requests.any(::isMutating))
    }

    @Test
    fun `provisioner returns structured refusal and never falls back or mutates`() {
        val executable = executable()
        val sdk = Files.createDirectory(directory.resolve("sdk"))
        val requests = mutableListOf<CommandRequest>()
        val provisioner =
            AndroidCliEmulatorProvisioner(
                CommandRunner { request ->
                    requests += request
                    successful(request)
                },
                { "Mac OS X" },
            )
        val error =
            assertFailsWith<AndroidCliCompatibilityException> {
                provisioner.provision(provisioningConfiguration(executable, sdk))
            }

        assertNotNull(error.report)
        assertEquals(AndroidCliCompatibilityOutcome.UNVERIFIED, error.report.outcome)
        assertFalse(requests.any(::isMutating))
        assertFalse(requests.any { it.arguments.first().contains("avdmanager") })
        assertIs<AndroidCliEmulatorProvisioner>(EmulatorBackendFactory.provisioner(EmulatorBackend.ANDROID_CLI))
    }

    @Test
    fun `diagnostic JSON is deterministic and strict`() {
        val executable = executable()
        val sdk = Files.createDirectory(directory.resolve("sdk"))
        val report =
            AndroidCliCompatibilityProbe(CommandRunner(::successful), { "Mac OS X" })
                .inspect(AndroidCliProbeConfiguration(executable, sdk))
        val first = AndroidCliCompatibilityReportCodec.encode(report)
        val second = AndroidCliCompatibilityReportCodec.encode(report)
        assertEquals(first, second)
        assertEquals(report, AndroidCliCompatibilityReportCodec.decode(first))
        assertTrue(first.contains("\"exactImagePackageRevision\""))
        assertTrue(first.contains("\"safeOwnedRemoval\""))

        val unknown = first.trimEnd().dropLast(1) + ",\"unknown\":true}"
        assertFailsWith<Throwable> { AndroidCliCompatibilityReportCodec.decode(unknown) }
        val missing = first.replace(Regex("\\s*\"schemaVersion\": 1,?\\n"), "\n")
        assertFailsWith<Throwable> { AndroidCliCompatibilityReportCodec.decode(missing) }
    }

    private fun successful(request: CommandRequest): CommandResult {
        val suffix = request.arguments.dropWhile { it != "emulator" }
        val stdout =
            when {
                request.arguments == listOf(request.arguments.first(), "--help") ->
                    "Usage: android [--sdk=PARAM] [--no-metrics] [COMMAND]"
                request.arguments.last() == "--version" -> "1.0.15985488\n"
                suffix == listOf("emulator", "--help") -> "Commands: create list start stop remove"
                suffix.size == 3 && suffix.last() == "--help" ->
                    "Usage: android emulator ${suffix[1]} [OPTIONS]"
                suffix == listOf("emulator", "list") -> "Pixel_9\n"
                else -> ""
            }
        return CommandResult(stdout = stdout, exitCode = 0)
    }

    private fun executable(): Path =
        directory.resolve("android").also {
            it.writeText("#!/bin/sh\n")
            assertTrue(it.toFile().setExecutable(true, false))
        }

    private fun isMutating(request: CommandRequest): Boolean {
        val arguments = request.arguments
        if ("sdk" in arguments && arguments.any { it in setOf("install", "update", "remove") }) return true
        val emulator = arguments.indexOf("emulator")
        if (emulator < 0) return false
        val command = arguments.getOrNull(emulator + 1)
        return command in setOf("create", "start", "stop", "remove") && arguments.lastOrNull() != "--help"
    }

    private fun provisioningConfiguration(
        executable: Path,
        sdk: Path,
    ): EmulatorProvisioningConfiguration {
        val contract =
            directory.resolve("contract.json").also {
                it.writeText(
                    """{"schemaVersion":1,"systemImagePackage":"system-images;android-35;google_apis;x86_64",""" +
                        """"systemImageRevision":"1","apiLevel":35,"abi":"x86_64","emulatorRevision":"35.1.4",""" +
                        """"platformToolsRevision":"35.0.2","commandLineToolsRevision":"12.0","deviceProfile":"pixel_5"}""",
                )
            }
        return EmulatorProvisioningConfiguration(
            EmulatorProvisioningContractLoader.load(contract),
            sdk,
            directory.resolve("state"),
            Path.of("avdmanager"),
            Path.of("emulator"),
            Path.of("adb"),
            5554,
            androidCliPath = executable,
        )
    }
}
