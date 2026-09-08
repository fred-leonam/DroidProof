package io.github.fredleonam.droidproof.device

import java.nio.file.Path

fun main(args: Array<String>) {
    require(args.size == 9) { "Expected capture task configuration arguments." }
    val includeLogs = args[3].toBooleanStrictOrNull()
    require(includeLogs != null) { "droidproof.includeLogcat must be true or false." }
    val pid =
        args[4].takeIf { it.isNotEmpty() }?.let {
            requireNotNull(it.toIntOrNull()) { "droidproof.pid must be a positive integer." }
        }

    fun positive(
        index: Int,
        property: String,
    ): Long =
        requireNotNull(args[index].toLongOrNull()?.takeIf { it > 0 }) {
            "droidproof.$property must be a positive integer."
        }
    val request =
        CaptureRequest(
            Path.of(args[0]),
            args[2].takeIf { it.isNotEmpty() },
            includeLogs,
            pid,
            CaptureLimits(
                positive(5, "commandTimeoutMillis"),
                positive(6, "textLimitBytes"),
                positive(7, "screenshotLimitBytes"),
                positive(8, "logcatLimitBytes"),
            ),
        )
    val executable = AdbPathResolver.resolve(args[1].takeIf { it.isNotEmpty() })
    val result = DeviceCollector(AdbClient(executable)).capture(request)
    println("Capture ${result.document.status}: ${result.directory}")
    result.document.issues.forEach { println("${it.code}: ${it.message}") }
    check(result.document.status == CaptureStatus.SUCCESS) {
        "Capture is ${result.document.status}; inspect capture.json. Successful files were preserved."
    }
}
