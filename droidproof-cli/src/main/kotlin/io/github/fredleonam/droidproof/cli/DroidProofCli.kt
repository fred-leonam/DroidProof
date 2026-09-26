package io.github.fredleonam.droidproof.cli

import io.github.fredleonam.droidproof.device.AdbPathResolver
import io.github.fredleonam.droidproof.evidence.AuthenticationStatus
import io.github.fredleonam.droidproof.evidence.Ed25519KeyLoader
import io.github.fredleonam.droidproof.host.EmulatorEnvironmentRecoveryCoordinator
import io.github.fredleonam.droidproof.host.EmulatorRecoveryRequest
import io.github.fredleonam.droidproof.host.FileEmulatorRecoveryJournalStore
import io.github.fredleonam.droidproof.host.RunSmokeScenarioConfiguration
import io.github.fredleonam.droidproof.host.SmokeAdbClient
import io.github.fredleonam.droidproof.host.SmokeScenarioLoader
import io.github.fredleonam.droidproof.host.runSmokeScenario
import io.github.fredleonam.droidproof.host.writeAndroidCliCompatibilityReport
import io.github.fredleonam.droidproof.report.EvidenceReportGenerator
import java.nio.file.Path
import kotlin.system.exitProcess

fun main(arguments: Array<String>) {
    val exitCode =
        try {
            execute(arguments)
            0
        } catch (error: IllegalArgumentException) {
            System.err.println("droidproof: ${error.message}")
            2
        } catch (error: IllegalStateException) {
            System.err.println("droidproof: ${error.message}")
            1
        } catch (error: Exception) {
            System.err.println("droidproof: ${error.message ?: error.javaClass.simpleName}")
            1
        }
    if (exitCode != 0) exitProcess(exitCode)
}

private fun execute(arguments: Array<String>) {
    if (arguments.isEmpty() || arguments.contentEquals(arrayOf("--help")) || arguments.contentEquals(arrayOf("-h"))) {
        println(HELP)
        return
    }
    if (arguments.contentEquals(arrayOf("--version"))) {
        println("droidproof ${implementationVersion()}")
        return
    }
    val command = arguments.first()
    val options = Options.parse(arguments.drop(1))
    if (options.flag("help")) {
        println(commandHelp(command))
        return
    }
    when (command) {
        "run" -> run(options)
        "validate-scenario" -> validateScenario(options)
        "report" -> report(options)
        "recover" -> recover(options)
        "probe-android-cli" -> probeAndroidCli(options)
        else -> throw IllegalArgumentException("Unknown command '$command'. Run droidproof --help.")
    }
}

private fun run(options: Options) {
    val working = Path.of("").toAbsolutePath()
    val values =
        linkedMapOf(
            "outputRoot" to working.resolve("droidproof-runs").toString(),
            "apkPath" to options.required("apk"),
            "scenarioPath" to options.required("scenario"),
            "deviceSerial" to options.optional("device-serial"),
            "adbPath" to options.optional("adb"),
            "replaceExisting" to options.boolean("replace-existing", false).toString(),
            "signingPrivateKeyPath" to options.optional("signing-private-key"),
            "signingPublicKeyPath" to options.optional("signing-public-key"),
            "environmentPath" to options.optional("environment"),
            "environmentMode" to options.optional("environment-mode", "VERIFY_ONLY"),
            "recoveryStateRoot" to working.resolve(".droidproof-recovery").toString(),
            "avdName" to options.optional("avd-name"),
            "emulatorPath" to options.optional("emulator", "emulator"),
            "emulatorPort" to options.optional("emulator-port", "5554"),
            "lifecycleStartupTimeoutMillis" to options.optional("startup-timeout-ms", "120000"),
            "lifecycleShutdownTimeoutMillis" to options.optional("shutdown-timeout-ms", "30000"),
            "provisioningPath" to options.optional("provisioning"),
            "sdkRoot" to options.optional("sdk-root"),
            "provisioningStateRoot" to working.resolve(".droidproof-provisioning").toString(),
            "avdManagerPath" to options.optional("avdmanager", "avdmanager"),
            "version" to implementationVersion(),
            "emulatorBackend" to options.optional("emulator-backend", "legacy"),
            "androidCliPath" to options.optional("android-cli"),
        )
    options.optionalValue("output")?.let { values["outputRoot"] = it }
    options.optionalValue("recovery-state-root")?.let { values["recoveryStateRoot"] = it }
    options.optionalValue("provisioning-state-root")?.let { values["provisioningStateRoot"] = it }
    options.ensureConsumed()
    runSmokeScenario(
        RunSmokeScenarioConfiguration.parse(values.map { "--${it.key}=${it.value}" }.toTypedArray()),
    )
}

private fun validateScenario(options: Options) {
    val path = Path.of(options.required("scenario"))
    options.ensureConsumed()
    val accepted = SmokeScenarioLoader.load(path)
    println("Valid DroidProof scenario v${accepted.scenario.schemaVersion}: ${accepted.scenario.scenarioId.value}")
    println("SHA-256: ${accepted.sha256.value}")
}

private fun report(options: Options) {
    val bundle = Path.of(options.required("bundle"))
    val output = Path.of(options.required("output"))
    val trusted = options.optionalValue("trusted-public-key")?.let { Ed25519KeyLoader.loadPublicKey(Path.of(it)) }
    options.ensureConsumed()
    val result = EvidenceReportGenerator().generate(bundle, output, trusted)
    println("DroidProof evidence report: ${result.output}")
    check(result.verification.isValid) { "Bundle verification failed; a diagnostic report was written." }
    check(result.verification.authentication.status != AuthenticationStatus.INVALID) {
        "Bundle authentication failed; a diagnostic report was written."
    }
}

private fun recover(options: Options) {
    val serial = options.required("device-serial")
    val adb = AdbPathResolver.resolve(options.optionalValue("adb"))
    val stateRoot = Path.of(options.required("recovery-state-root"))
    val timeout =
        options.optional("command-timeout-ms", "15000").toLongOrNull()
            ?: throw IllegalArgumentException("--command-timeout-ms must be a number.")
    options.ensureConsumed()
    val result =
        EmulatorEnvironmentRecoveryCoordinator(
            SmokeAdbClient(adb),
            FileEmulatorRecoveryJournalStore(stateRoot),
        ).recover(EmulatorRecoveryRequest(serial, timeout))
    println("DroidProof environment recovery: ${result.detail}")
    check(result.successful) { result.detail }
}

private fun probeAndroidCli(options: Options) {
    val executable = Path.of(options.required("android-cli"))
    val sdkRoot = Path.of(options.required("sdk-root"))
    val output = Path.of(options.required("output"))
    val timeout =
        options.optional("timeout-ms", "15000").toLongOrNull()
            ?: throw IllegalArgumentException("--timeout-ms must be a number.")
    options.ensureConsumed()
    val outcome = writeAndroidCliCompatibilityReport(executable, sdkRoot, timeout, output)
    println("DroidProof Android CLI compatibility: $outcome")
    println("JSON report: $output")
}

private class Options private constructor(private val values: Map<String, String>) {
    private val consumed = mutableSetOf<String>()

    fun required(name: String): String =
        optionalValue(name)?.takeIf(String::isNotBlank)
            ?: throw IllegalArgumentException("--$name is required.")

    fun optional(
        name: String,
        default: String = "",
    ): String = optionalValue(name) ?: default

    fun optionalValue(name: String): String? {
        consumed += name
        return values[name]
    }

    fun boolean(
        name: String,
        default: Boolean,
    ): Boolean {
        val value = optionalValue(name) ?: return default
        return value.toBooleanStrictOrNull()
            ?: throw IllegalArgumentException("--$name must be true or false.")
    }

    fun flag(name: String): Boolean = boolean(name, false)

    fun ensureConsumed() {
        val unknown = values.keys - consumed
        require(unknown.isEmpty()) { "Unknown option --${unknown.first()}." }
    }

    companion object {
        fun parse(arguments: List<String>): Options {
            val parsed = linkedMapOf<String, String>()
            var index = 0
            while (index < arguments.size) {
                val argument = arguments[index]
                require(argument.startsWith("--")) { "Unexpected argument '$argument'. Options must start with --." }
                val raw = argument.removePrefix("--")
                val (name, value, advance) =
                    if ('=' in raw) {
                        Triple(raw.substringBefore('='), raw.substringAfter('='), 1)
                    } else if (index + 1 < arguments.size && !arguments[index + 1].startsWith("--")) {
                        Triple(raw, arguments[index + 1], 2)
                    } else {
                        Triple(raw, "true", 1)
                    }
                require(name.isNotBlank()) { "Option name must not be empty." }
                require(parsed.put(name, value) == null) { "Duplicate option --$name." }
                index += advance
            }
            return Options(parsed)
        }
    }
}

private fun implementationVersion(): String = object {}.javaClass.`package`.implementationVersion ?: "0.1.0-SNAPSHOT"

private fun commandHelp(command: String): String =
    when (command) {
        "run" -> RUN_HELP
        "validate-scenario" -> "Usage: droidproof validate-scenario --scenario PATH"
        "report" -> "Usage: droidproof report --bundle DIR --output FILE [--trusted-public-key FILE]"
        "recover" -> "Usage: droidproof recover --device-serial SERIAL --recovery-state-root DIR [--adb PATH]"
        "probe-android-cli" -> "Usage: droidproof probe-android-cli --android-cli PATH --sdk-root DIR --output FILE"
        else -> throw IllegalArgumentException("Unknown command '$command'.")
    }

private const val HELP = """Usage: droidproof COMMAND [OPTIONS]

Commands:
  run                  Run an artifact-bound Android scenario
  validate-scenario    Validate and identify a scenario document
  report               Verify a bundle and generate offline HTML
  recover              Restore an interrupted environment transaction
  probe-android-cli    Write an Android CLI compatibility report

Run 'droidproof COMMAND --help' for command usage."""

private const val RUN_HELP = """Usage: droidproof run --apk FILE --scenario FILE TARGET [OPTIONS]

Choose one target:
  --device-serial SERIAL
  --avd-name NAME
  --provisioning FILE --sdk-root DIR

Common options:
  --output DIR                  Evidence output root
  --adb PATH                    Explicit ADB executable
  --environment FILE            Environment contract
  --environment-mode MODE       VERIFY_ONLY or APPLY_AND_RESTORE
  --replace-existing[=true]     Replace different installed APK bytes
  --signing-private-key FILE    Ed25519 private key (requires public key)
  --signing-public-key FILE     Ed25519 public key"""
