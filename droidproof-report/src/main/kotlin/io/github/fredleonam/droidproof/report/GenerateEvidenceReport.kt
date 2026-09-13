package io.github.fredleonam.droidproof.report

import java.nio.file.Path

fun main(args: Array<String>) {
    require(args.size == 2) { "Expected bundle and report output path arguments." }
    require(args[0].isNotBlank()) {
        "Set -Pdroidproof.bundlePath to an existing DroidProof evidence bundle directory."
    }
    require(args[1].isNotBlank()) { "Report output path must not be blank." }

    val result = EvidenceReportGenerator().generate(Path.of(args[0]), Path.of(args[1]))
    println("DroidProof evidence report: ${result.output}")
    check(result.verification.isValid) {
        "Bundle verification failed; the diagnostic HTML report was written, and no evidence content was rendered."
    }
}
