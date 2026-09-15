package io.github.fredleonam.droidproof.report

import io.github.fredleonam.droidproof.evidence.AuthenticationStatus
import io.github.fredleonam.droidproof.evidence.Ed25519KeyLoader
import java.nio.file.Path

fun main(args: Array<String>) {
    require(args.size == 3) { "Expected bundle, report output, and optional trusted-public-key path arguments." }
    require(args[0].isNotBlank()) {
        "Set -Pdroidproof.bundlePath to an existing DroidProof evidence bundle directory."
    }
    require(args[1].isNotBlank()) { "Report output path must not be blank." }

    val trustedPublicKey = args[2].takeIf(String::isNotBlank)?.let { Ed25519KeyLoader.loadPublicKey(Path.of(it)) }
    val result = EvidenceReportGenerator().generate(Path.of(args[0]), Path.of(args[1]), trustedPublicKey)
    println("DroidProof evidence report: ${result.output}")
    check(result.verification.isValid) {
        "Bundle verification failed; the diagnostic HTML report was written, and no evidence content was rendered."
    }
    check(result.verification.authentication.status != AuthenticationStatus.INVALID) {
        "Bundle authentication failed; the diagnostic HTML report was written, and no evidence content was rendered."
    }
}
