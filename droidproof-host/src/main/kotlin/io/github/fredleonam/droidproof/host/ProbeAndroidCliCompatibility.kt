package io.github.fredleonam.droidproof.host

import java.nio.file.Path

/** Runs the bounded read-only probe, writes its JSON report, and returns the outcome name. */
fun writeAndroidCliCompatibilityReport(
    androidCliPath: Path,
    sdkRoot: Path,
    timeoutMillis: Long,
    outputPath: Path,
): String {
    val report =
        AndroidCliCompatibilityProbe().inspect(
            AndroidCliProbeConfiguration(androidCliPath, sdkRoot, timeoutMillis),
        )
    AndroidCliCompatibilityReportCodec.write(report, outputPath)
    return report.outcome.name
}

fun main(args: Array<String>) {
    val configuration = AndroidCliDiagnosticConfiguration.parse(args)
    val report =
        AndroidCliCompatibilityProbe().inspect(
            AndroidCliProbeConfiguration(
                configuration.androidCliPath,
                configuration.sdkRoot,
                configuration.timeoutMillis,
            ),
        )
    AndroidCliCompatibilityReportCodec.write(report, configuration.outputPath)
    println("DroidProof Android CLI compatibility: ${report.outcome}")
    println("Version: ${report.normalizedVersion ?: "unverified"}")
    println("Host: ${report.hostOs} (${report.hostOsSupport})")
    println("SDK selection: ${report.sdkSelection}")
    report.issues.forEach { println("${it.code}: ${it.explanation}") }
    println("JSON report: ${configuration.outputPath}")
}

internal data class AndroidCliDiagnosticConfiguration(
    val androidCliPath: Path,
    val sdkRoot: Path,
    val timeoutMillis: Long,
    val outputPath: Path,
) {
    init {
        require(androidCliPath.isAbsolute) { "droidproof.androidCliPath must be absolute." }
        require(sdkRoot.isAbsolute) { "droidproof.sdkRoot must be absolute." }
        require(outputPath.isAbsolute) { "Diagnostic output path must be absolute." }
        require(timeoutMillis in 1..3_600_000) {
            "droidproof.androidCliProbeTimeoutMillis must be from 1 through 3600000 milliseconds."
        }
    }

    companion object {
        private val keys = setOf("androidCliPath", "sdkRoot", "timeoutMillis", "outputPath")

        fun parse(arguments: Array<String>): AndroidCliDiagnosticConfiguration {
            val values =
                arguments.associate { argument ->
                    require(argument.startsWith("--") && argument.contains('=')) {
                        "Expected named --key=value Android CLI diagnostic arguments."
                    }
                    val (key, value) = argument.removePrefix("--").split('=', limit = 2)
                    require(key in keys) { "Unrecognized Android CLI diagnostic option: $key." }
                    key to value
                }
            require(values.size == arguments.size) { "Duplicate Android CLI diagnostic options are not allowed." }

            fun required(key: String) =
                requireNotNull(values[key]?.takeIf(String::isNotBlank)) {
                    "droidproof.$key is required."
                }

            val timeout =
                required("timeoutMillis").toLongOrNull()
                    ?: throw IllegalArgumentException("droidproof.androidCliProbeTimeoutMillis must be a number.")
            return AndroidCliDiagnosticConfiguration(
                Path.of(required("androidCliPath")),
                Path.of(required("sdkRoot")),
                timeout,
                Path.of(required("outputPath")),
            )
        }
    }
}
