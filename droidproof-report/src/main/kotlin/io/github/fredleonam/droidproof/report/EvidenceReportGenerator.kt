package io.github.fredleonam.droidproof.report

import io.github.fredleonam.droidproof.evidence.AuthenticationStatus
import io.github.fredleonam.droidproof.evidence.EvidenceBundleVerificationResult
import io.github.fredleonam.droidproof.evidence.EvidenceBundleVerifier
import io.github.fredleonam.droidproof.evidence.MANIFEST_FILE
import io.github.fredleonam.droidproof.evidence.TIMELINE_FILE
import io.github.fredleonam.droidproof.evidence.V3_SCHEMA_VERSION
import io.github.fredleonam.droidproof.evidence.evidenceJson
import io.github.fredleonam.droidproof.model.BundleRelativePath
import io.github.fredleonam.droidproof.model.EmulatorEnvironmentEvaluationV1
import io.github.fredleonam.droidproof.model.EnvironmentTransactionDocument
import io.github.fredleonam.droidproof.model.EventSource
import io.github.fredleonam.droidproof.model.EvidenceBundleManifest
import io.github.fredleonam.droidproof.model.EvidenceBundleManifestV3
import io.github.fredleonam.droidproof.model.EvidenceFileDescriptor
import io.github.fredleonam.droidproof.model.EvidenceFileRole
import io.github.fredleonam.droidproof.model.ObservedExecutionEnvironment
import io.github.fredleonam.droidproof.model.ObservedValue
import io.github.fredleonam.droidproof.model.TimelineDocument
import kotlinx.serialization.decodeFromString
import java.net.URI
import java.nio.charset.StandardCharsets
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.security.PublicKey

data class EvidenceReportResult(
    val output: Path,
    val verification: EvidenceBundleVerificationResult,
)

/** Produces a disposable offline view of an existing evidence bundle. */
class EvidenceReportGenerator {
    fun generate(
        bundlePath: Path,
        reportPath: Path,
        trustedPublicKey: PublicKey? = null,
    ): EvidenceReportResult {
        val verification = EvidenceBundleVerifier().verify(bundlePath, trustedPublicKey)
        val bundle = bundlePath.toAbsolutePath().normalize()
        val output = reportPath.toAbsolutePath().normalize()
        requireOutputOutsideBundle(bundle, output)

        val html =
            if (verification.isValid && verification.authentication.status != AuthenticationStatus.INVALID) {
                renderVerified(bundle, output, verification)
            } else {
                renderVerificationFailure(verification)
            }
        writeAtomically(output, html)
        return EvidenceReportResult(output, verification)
    }

    private fun renderVerified(
        bundle: Path,
        output: Path,
        verification: EvidenceBundleVerificationResult,
    ): String {
        val manifestText = Files.readString(bundle.resolve(MANIFEST_FILE), StandardCharsets.UTF_8)
        val timeline =
            evidenceJson.decodeFromString<TimelineDocument>(
                Files.readString(bundle.resolve(TIMELINE_FILE), StandardCharsets.UTF_8),
            )
        return if (verification.schemaVersion == V3_SCHEMA_VERSION) {
            renderV3(
                bundle,
                evidenceJson.decodeFromString<EvidenceBundleManifestV3>(manifestText),
                timeline,
                verification,
                LinkResolver(bundle, requireNotNull(output.parent)),
            )
        } else {
            renderLegacy(
                evidenceJson.decodeFromString<EvidenceBundleManifest>(manifestText),
                timeline,
                verification,
                LinkResolver(bundle, requireNotNull(output.parent)),
            )
        }
    }

    private fun renderV3(
        bundle: Path,
        manifest: EvidenceBundleManifestV3,
        timeline: TimelineDocument,
        verification: EvidenceBundleVerificationResult,
        links: LinkResolver,
    ): String {
        val body =
            buildString {
                append(verificationSection(verification, true))
                appendLine("<section><h2>Execution summary</h2><dl class=\"summary\">")
                append(row("Bundle ID", manifest.bundleId.value))
                append(row("Scenario ID", manifest.scenario.id.value))
                append(rowHtml("Execution status", badge(manifest.execution.status.name)))
                append(rowHtml("Scenario verdict", badge(manifest.execution.verdict.name)))
                append(row("Evidence completeness", manifest.execution.evidenceCompleteness.name))
                append(row("Created/start time", manifest.createdAt.value))
                append(row("DroidProof version", manifest.droidProofVersion.value))
                appendLine("</dl></section>")

                appendLine("<section><h2>Artifact identity</h2><dl class=\"summary\">")
                append(row("Artifact type", manifest.artifact.type.name))
                append(row("Artifact SHA-256", manifest.artifact.sha256.value))
                manifest.artifact.signingCertificateSha256?.let { append(row("Signing certificate SHA-256", it.value)) }
                append(row("Artifact-binding status", manifest.artifactBinding.status.name))
                append(row("Package name", manifest.artifactBinding.packageName))
                append(row("Input APK SHA-256", manifest.artifactBinding.inputApkSha256.value))
                manifest.artifactBinding.installedApkSha256Before?.let { append(row("Installed APK SHA-256 before", it.value)) }
                manifest.artifactBinding.installedApkSha256After?.let { append(row("Installed APK SHA-256 after", it.value)) }
                appendLine("</dl></section>")

                appendLine("<section><h2>Requested configuration</h2><dl class=\"summary\">")
                append(row("Device serial", manifest.requestedConfiguration.deviceSerial))
                append(row("Expected package", manifest.requestedConfiguration.packageName))
                append(row("Primary user only", yesNo(manifest.requestedConfiguration.primaryUserOnly)))
                append(row("Replace existing", yesNo(manifest.requestedConfiguration.replaceExisting)))
                appendLine("</dl></section>")

                append(observedEnvironment(manifest.observedEnvironment))
                append(emulatorEnvironmentSection(bundle, manifest.evidenceFiles))
                append(environmentTransactionSection(bundle, manifest.evidenceFiles))
                append(timelineSection(timeline, manifest.evidenceFiles, links, integrityBound = true))
                append(networkSection(timeline, manifest.evidenceFiles, links))
                append(screenshotSection(manifest.evidenceFiles, links))
                append(inventorySection(manifest.evidenceFiles, links))
                append(otherEvidenceSection(manifest.evidenceFiles, links))
            }
        return page("DroidProof report — ${manifest.bundleId.value}", body)
    }

    private fun renderLegacy(
        manifest: EvidenceBundleManifest,
        timeline: TimelineDocument,
        verification: EvidenceBundleVerificationResult,
        links: LinkResolver,
    ): String {
        val isV1 = manifest.schemaVersion == 1
        val body =
            buildString {
                append(verificationSection(verification, true))
                if (isV1) {
                    appendLine(
                        "<section class=\"notice warning\"><h2>Legacy / limited report</h2>" +
                            "<p>Schema version 1 has no evidence-file inventory. Evidence file integrity was not verified, " +
                            "so this report provides no evidence previews or links.</p></section>",
                    )
                }
                appendLine("<section><h2>Bundle summary</h2><dl class=\"summary\">")
                append(row("Bundle ID", manifest.bundleId.value))
                append(row("Scenario ID", manifest.scenario.id.value))
                append(row("Created time", manifest.createdAt.value))
                append(row("DroidProof version", manifest.droidProofVersion.value))
                manifest.gitCommit?.let { append(row("Git commit", it.value)) }
                appendLine("</dl></section>")

                appendLine("<section><h2>Artifact identity</h2><dl class=\"summary\">")
                append(row("Artifact type", manifest.artifact.type.name))
                append(row("Artifact SHA-256", manifest.artifact.sha256.value))
                manifest.artifact.signingCertificateSha256?.let { append(row("Signing certificate SHA-256", it.value)) }
                appendLine("</dl></section>")

                appendLine("<section><h2>Recorded environment contract</h2><dl class=\"summary\">")
                append(row("Device fingerprint", manifest.environment.device.fingerprint))
                append(row("API level", manifest.environment.device.apiLevel.toString()))
                append(row("Locale", manifest.environment.locale))
                append(row("Orientation", manifest.environment.orientation.name))
                append(
                    row(
                        "Animation scales",
                        "window=${manifest.environment.animations.windowScale}, " +
                            "transition=${manifest.environment.animations.transitionScale}, " +
                            "animator=${manifest.environment.animations.animatorScale}",
                    ),
                )
                append(row("Random seed", manifest.environment.randomSeed.toString()))
                manifest.environment.controlledClock?.let {
                    append(row("Controlled clock initial time", it.initialTime.value))
                    append(row("Controlled clock frozen", yesNo(it.isFrozen)))
                }
                appendLine("</dl></section>")
                append(timelineSection(timeline, manifest.evidenceFiles, links, integrityBound = !isV1))
                if (!isV1) {
                    append(networkSection(timeline, manifest.evidenceFiles, links))
                    append(screenshotSection(manifest.evidenceFiles, links))
                    append(inventorySection(manifest.evidenceFiles, links))
                    append(otherEvidenceSection(manifest.evidenceFiles, links))
                }
            }
        return page("DroidProof report — ${manifest.bundleId.value}", body)
    }

    private fun renderVerificationFailure(verification: EvidenceBundleVerificationResult): String {
        val body =
            buildString {
                append(verificationSection(verification, false))
                appendLine(
                    "<section class=\"notice error\"><h2>Unverified content omitted</h2>" +
                        "<p>No evidence previews, evidence links, timeline, artifact claims, or scenario claims " +
                        "are rendered.</p></section>",
                )
            }
        return page("DroidProof — bundle verification failed", body)
    }

    private fun verificationSection(
        verification: EvidenceBundleVerificationResult,
        passed: Boolean,
    ): String =
        buildString {
            val kind = if (passed) "success" else "error"
            val heading = if (passed) "Bundle integrity verification passed" else "Bundle verification failed"
            appendLine("<section class=\"notice $kind\"><h1>${Html.escape(heading)}</h1>")
            appendLine("<dl class=\"summary\">")
            append(row("Integrity verification", if (verification.isValid) "PASSED" else "FAILED"))
            append(row("Schema version", verification.schemaVersion?.toString() ?: "Unavailable"))
            when (verification.authentication.status) {
                AuthenticationStatus.UNSIGNED -> append(row("Authentication", "Bundle is unsigned"))
                AuthenticationStatus.SIGNED_UNTRUSTED -> {
                    append(row("Authentication", "Signature present; authenticity was not established"))
                    verification.authentication.algorithm?.let { append(row("Signature algorithm", it)) }
                    verification.authentication.keyId?.let { append(row("Claimed key ID", it.value)) }
                }
                AuthenticationStatus.AUTHENTICATED -> {
                    append(row("Authentication", "AUTHENTICATED with externally supplied public key"))
                    verification.authentication.algorithm?.let { append(row("Signature algorithm", it)) }
                    verification.authentication.keyId?.let { append(row("Trusted key ID", it.value)) }
                }
                AuthenticationStatus.INVALID -> append(row("Authentication", "FAILED"))
            }
            appendLine("</dl>")
            if (verification.issues.isEmpty()) {
                appendLine("<p>No verification warnings.</p>")
            } else {
                appendLine("<h2>Verification issues and warnings</h2><ul class=\"issues\">")
                verification.issues.forEach { issue ->
                    append("<li><code>${Html.escape(issue.code.name)}</code> ")
                    append("<span class=\"severity\">${Html.escape(issue.severity.name)}</span>: ")
                    append(Html.escape(issue.message))
                    issue.path?.let { append(" <span class=\"path\">(${Html.escape(it)})</span>") }
                    appendLine("</li>")
                }
                appendLine("</ul>")
            }
            if (verification.authentication.issues.isNotEmpty()) {
                appendLine("<h2>Authentication issues</h2><ul class=\"issues\">")
                verification.authentication.issues.forEach { issue ->
                    append("<li><code>${Html.escape(issue.code.name)}</code>: ${Html.escape(issue.message)}")
                    issue.path?.let { append(" <span class=\"path\">(${Html.escape(it)})</span>") }
                    appendLine("</li>")
                }
                appendLine("</ul>")
            }
            appendLine(
                "<p><strong>Integrity scope:</strong> verification applies to the underlying evidence bundle, not this " +
                    "disposable HTML report. SHA-256 consistency alone does not establish producer authenticity.</p></section>",
            )
        }

    private fun observedEnvironment(environment: ObservedExecutionEnvironment): String =
        buildString {
            appendLine("<section><h2>Observed environment</h2><dl class=\"summary\">")
            append(observedRow("Build fingerprint", environment.buildFingerprint))
            append(observedRow("API level", environment.apiLevel))
            append(observedRow("Locale", environment.locale))
            append(observedRow("Orientation", environment.orientation))
            append(observedRow("Animation scales", environment.animations))
            append(observedRow("Random seed", environment.randomSeed))
            append(observedRow("Controlled clock", environment.controlledClock))
            appendLine("</dl></section>")
        }

    private fun emulatorEnvironmentSection(
        bundle: Path,
        inventory: List<EvidenceFileDescriptor>,
    ): String {
        val path = BundleRelativePath("environment/evaluation.json")
        val descriptor =
            inventory.singleOrNull { it.path == path && it.mediaType == "application/json" }
                ?: return ""
        val evaluation =
            evidenceJson.decodeFromString<EmulatorEnvironmentEvaluationV1>(
                Files.readString(bundle.resolve(descriptor.path.value), StandardCharsets.UTF_8),
            )

        fun observed(value: io.github.fredleonam.droidproof.model.EnvironmentObservation): String =
            value.normalizedValue ?: "Unavailable: ${requireNotNull(value.unavailableReason)}"
        return buildString {
            appendLine("<section><h2>Emulator environment</h2><dl class=\"summary\">")
            append(rowHtml("Overall evaluation", badge(evaluation.outcome.name)))
            append(row("Requested locale", evaluation.requested.locale))
            append(row("Observed locale", observed(evaluation.observed.locale)))
            append(row("Requested orientation", evaluation.requested.orientation.name))
            append(row("Observed orientation", observed(evaluation.observed.orientation)))
            append(row("Requested window animation scale", evaluation.requested.animations.windowScale.toString()))
            append(row("Observed window animation scale", observed(evaluation.observed.animations.windowScale)))
            append(row("Requested transition animation scale", evaluation.requested.animations.transitionScale.toString()))
            append(row("Observed transition animation scale", observed(evaluation.observed.animations.transitionScale)))
            append(row("Requested animator animation scale", evaluation.requested.animations.animatorScale.toString()))
            append(row("Observed animator animation scale", observed(evaluation.observed.animations.animatorScale)))
            appendLine("</dl><p>${Html.escape(evaluation.explanation)}</p>")
            appendLine("<ul class=\"issues\">")
            evaluation.fields.forEach { field ->
                appendLine("<li>${Html.escape(field.field)}: ${Html.escape(field.outcome.name)} — ${Html.escape(field.explanation)}</li>")
            }
            appendLine("</ul></section>")
        }
    }

    private fun environmentTransactionSection(
        bundle: Path,
        inventory: List<EvidenceFileDescriptor>,
    ): String {
        val path = BundleRelativePath("environment/transaction.json")
        val descriptor = inventory.singleOrNull { it.path == path && it.mediaType == "application/json" } ?: return ""
        val transaction =
            evidenceJson.decodeFromString<EnvironmentTransactionDocument>(
                Files.readString(bundle.resolve(descriptor.path.value), StandardCharsets.UTF_8),
            )

        fun state(value: io.github.fredleonam.droidproof.model.EmulatorEnvironmentState?): String =
            value?.let {
                "locale=${it.locale}, accelerometer_rotation=${it.accelerometerRotation}, " +
                    "user_rotation=${it.userRotation}, window=${it.windowScale}, " +
                    "transition=${it.transitionScale}, animator=${it.animatorScale}"
            } ?: "Unavailable"
        return buildString {
            appendLine("<section><h2>Emulator environment transaction</h2><dl class=\"summary\">")
            append(row("Mode", transaction.mode.name))
            append(row("Original environment", state(transaction.original)))
            append(row("Mutation attempted", yesNo(transaction.mutationAttempted)))
            append(row("Applied verification", transaction.requestedVerification?.name ?: "Not evaluated"))
            append(rowHtml("Restoration status", badge(transaction.restorationOutcome.name)))
            append(row("Restored environment", state(transaction.restored)))
            appendLine("</dl><p>${Html.escape(transaction.detail)}</p></section>")
        }
    }

    private fun observedRow(
        label: String,
        observed: ObservedValue,
    ): String = row(label, observed.value ?: "Unavailable: ${requireNotNull(observed.unavailableReason)}")

    private fun timelineSection(
        timeline: TimelineDocument,
        inventory: List<EvidenceFileDescriptor>,
        links: LinkResolver,
        integrityBound: Boolean,
    ): String {
        val verifiedPaths = inventory.associateBy { it.path }
        return buildString {
            appendLine("<section><h2>Timeline</h2>")
            if (timeline.events.isEmpty()) {
                appendLine("<p>No timeline events were recorded.</p></section>")
                return@buildString
            }
            appendLine(
                "<div class=\"table-scroll\"><table><thead><tr><th>Timestamp</th><th>Source</th>" +
                    "<th>Event type</th><th>Attributes</th><th>Linked evidence</th></tr></thead><tbody>",
            )
            timeline.events.forEach { event ->
                append("<tr><td>${Html.escape(event.timestamp.value)}</td>")
                append("<td>${Html.escape(event.source.name)}</td>")
                append("<td>${Html.escape(event.type)}</td><td>")
                if (event.attributes.isEmpty()) {
                    append("<span class=\"muted\">None</span>")
                } else {
                    append("<dl class=\"attributes\">")
                    event.attributes.forEach { (key, value) ->
                        append("<dt>${Html.escape(key)}</dt><dd>${Html.escape(value)}</dd>")
                    }
                    append("</dl>")
                }
                append("</td><td>")
                if (event.evidence.isEmpty()) {
                    append("<span class=\"muted\">None</span>")
                } else {
                    append("<ul class=\"links\">")
                    event.evidence.forEach { reference ->
                        val descriptor = verifiedPaths[reference.path]
                        append("<li>")
                        if (integrityBound && descriptor != null) {
                            append(evidenceLink(reference.path, links))
                        } else {
                            append(Html.escape(reference.path.value))
                            append(" <span class=\"muted\">(not integrity-verified)</span>")
                        }
                        appendLine("</li>")
                    }
                    append("</ul>")
                }
                appendLine("</td></tr>")
            }
            appendLine("</tbody></table></div></section>")
        }
    }

    private fun screenshotSection(
        inventory: List<EvidenceFileDescriptor>,
        links: LinkResolver,
    ): String {
        val screenshots = inventory.filter { it.role == EvidenceFileRole.SCREENSHOT && it.mediaType == "image/png" }
        if (screenshots.isEmpty()) return ""
        return buildString {
            appendLine("<section><h2>Screenshot</h2><div class=\"screenshots\">")
            screenshots.forEach { screenshot ->
                val href = Html.escape(links.href(screenshot.path))
                val label = Html.escape(screenshot.path.value)
                appendLine("<figure><a href=\"$href\"><img src=\"$href\" alt=\"Verified screenshot evidence: $label\"></a>")
                appendLine("<figcaption><a href=\"$href\">$label</a></figcaption></figure>")
            }
            appendLine("</div></section>")
        }
    }

    private fun networkSection(
        timeline: TimelineDocument,
        inventory: List<EvidenceFileDescriptor>,
        links: LinkResolver,
    ): String {
        val networkFiles = inventory.filter { it.role == EvidenceFileRole.NETWORK }.associateBy { it.path }
        val exchanges = timeline.events.filter { it.source == EventSource.MOCK_SERVER }
        if (exchanges.isEmpty() && networkFiles.isEmpty()) return ""
        return buildString {
            appendLine("<section><h2>Network</h2>")
            appendLine(
                "<p class=\"muted\">These are bounded observations made by DroidProof's controlled mock server, " +
                    "not packet-level capture or proof of arbitrary Android traffic.</p>",
            )
            if (exchanges.isEmpty()) {
                appendLine("<p>No correlated mock-server exchange events were recorded.</p></section>")
                return@buildString
            }
            appendLine(
                "<div class=\"table-scroll\"><table><thead><tr><th>Sequence</th><th>Method</th>" +
                    "<th>Path</th><th>Request contract</th><th>Request bytes</th><th>Request SHA-256</th>" +
                    "<th>Response status</th><th>Verified exchange evidence</th></tr></thead><tbody>",
            )
            exchanges.forEachIndexed { index, event ->
                append("<tr><td>${Html.escape(event.attributes["serverSequence"] ?: (index + 1).toString())}</td>")
                append("<td>${Html.escape(event.attributes["method"] ?: "Unavailable")}</td>")
                append("<td>${Html.escape(event.attributes["path"] ?: "Unavailable")}</td>")
                append("<td>${Html.escape(event.attributes["requestContractOutcome"] ?: "Unavailable")}")
                event.attributes["requestContractIssues"]?.takeIf { it.isNotEmpty() }?.let { issues ->
                    append("<br><span class=\"muted\">${Html.escape(issues)}</span>")
                }
                append("</td>")
                append("<td>${Html.escape(event.attributes["requestBytes"] ?: "Unavailable")}</td>")
                append("<td><code>${Html.escape(event.attributes["requestSha256"] ?: "Unavailable")}</code></td>")
                append("<td>${Html.escape(event.attributes["responseStatus"] ?: "Unavailable")}</td><td>")
                val references = event.evidence.filter { it.path in networkFiles }
                if (references.isEmpty()) {
                    append("<span class=\"muted\">None</span>")
                } else {
                    append(references.joinToString(", ") { evidenceLink(it.path, links) })
                }
                appendLine("</td></tr>")
            }
            appendLine("</tbody></table></div></section>")
        }
    }

    private fun inventorySection(
        inventory: List<EvidenceFileDescriptor>,
        links: LinkResolver,
    ): String =
        buildString {
            appendLine("<section><h2>Evidence inventory</h2>")
            if (inventory.isEmpty()) {
                appendLine("<p>No evidence files are registered.</p></section>")
                return@buildString
            }
            appendLine(
                "<div class=\"table-scroll\"><table><thead><tr><th>Relative path</th><th>Role</th>" +
                    "<th>Media type</th><th>Bytes</th><th>SHA-256</th></tr></thead><tbody>",
            )
            inventory.forEach { descriptor ->
                append("<tr><td>${evidenceLink(descriptor.path, links)}</td>")
                append("<td>${Html.escape(descriptor.role?.name ?: "Unspecified")}</td>")
                append("<td>${Html.escape(descriptor.mediaType)}</td>")
                append("<td>${Html.escape(descriptor.byteSize.toString())}</td>")
                appendLine("<td><code>${Html.escape(descriptor.sha256.value)}</code></td></tr>")
            }
            appendLine("</tbody></table></div></section>")
        }

    private fun otherEvidenceSection(
        inventory: List<EvidenceFileDescriptor>,
        links: LinkResolver,
    ): String {
        val other =
            inventory.filterNot {
                (it.role == EvidenceFileRole.SCREENSHOT && it.mediaType == "image/png") || it.role == EvidenceFileRole.NETWORK
            }
        if (other.isEmpty()) return ""
        return buildString {
            appendLine("<section><h2>Other evidence</h2><ul class=\"links\">")
            other.forEach { descriptor ->
                appendLine(
                    "<li>${evidenceLink(descriptor.path, links)} " +
                        "<span class=\"muted\">(${Html.escape(descriptor.mediaType)})</span></li>",
                )
            }
            appendLine("</ul><p class=\"muted\">Evidence is linked as a local file and is not embedded or rendered as HTML.</p></section>")
        }
    }

    private fun evidenceLink(
        path: BundleRelativePath,
        links: LinkResolver,
    ): String = "<a href=\"${Html.escape(links.href(path))}\">${Html.escape(path.value)}</a>"

    private fun row(
        label: String,
        value: String,
    ): String = "<dt>${Html.escape(label)}</dt><dd>${Html.escape(value)}</dd>\n"

    private fun rowHtml(
        label: String,
        safeHtml: String,
    ): String = "<dt>${Html.escape(label)}</dt><dd>$safeHtml</dd>\n"

    private fun badge(value: String): String {
        val cssClass =
            when (value) {
                "PASSED" -> "status-passed"
                "FAILED" -> "status-failed"
                "NOT_EVALUATED" -> "status-not-evaluated"
                "ERROR" -> "status-error"
                "CANCELLED" -> "status-cancelled"
                else -> "status-neutral"
            }
        return "<span class=\"badge $cssClass\">${Html.escape(value)}</span>"
    }

    private fun yesNo(value: Boolean): String = if (value) "Yes" else "No"

    private fun page(
        title: String,
        body: String,
    ): String =
        """<!doctype html>
<html lang="en">
<head>
  <meta charset="utf-8">
  <meta name="viewport" content="width=device-width, initial-scale=1">
  <title>${Html.escape(title)}</title>
  <style>
    :root { color-scheme: light dark; --bg: #f4f6f8; --panel: #fff; --text: #17202a; --muted: #59636e; --line: #d9dee3; --ok: #176b3a; --bad: #a12622; --warn: #8a5a00; }
    * { box-sizing: border-box; }
    body { margin: 0; background: var(--bg); color: var(--text); font: 15px/1.5 system-ui, sans-serif; }
    main { width: min(1180px, calc(100% - 32px)); margin: 32px auto 64px; }
    section { background: var(--panel); border: 1px solid var(--line); border-radius: 10px; margin: 16px 0; padding: 20px; }
    h1, h2 { margin: 0 0 14px; line-height: 1.2; }
    h1 { font-size: 1.7rem; } h2 { font-size: 1.2rem; }
    .notice.success { border-left: 6px solid var(--ok); } .notice.error { border-left: 6px solid var(--bad); } .notice.warning { border-left: 6px solid var(--warn); }
    .summary { display: grid; grid-template-columns: minmax(180px, 260px) 1fr; gap: 7px 18px; margin: 0; }
    .summary dt { color: var(--muted); font-weight: 650; } .summary dd { margin: 0; overflow-wrap: anywhere; }
    .badge { display: inline-block; border-radius: 999px; color: white; font-weight: 750; padding: 2px 9px; }
    .status-passed { background: #187044; } .status-failed { background: #a85b00; } .status-error { background: #b52b27; }
    .status-not-evaluated { background: #70642b; } .status-cancelled { background: #6c4675; } .status-neutral { background: #4d6070; }
    .table-scroll { overflow-x: auto; } table { width: 100%; border-collapse: collapse; }
    th, td { border-bottom: 1px solid var(--line); padding: 9px; text-align: left; vertical-align: top; overflow-wrap: anywhere; }
    th { color: var(--muted); font-size: .82rem; text-transform: uppercase; letter-spacing: .04em; }
    code { font-family: ui-monospace, SFMono-Regular, Consolas, monospace; font-size: .88em; word-break: break-all; }
    .attributes { display: grid; grid-template-columns: max-content 1fr; gap: 2px 8px; margin: 0; }
    .attributes dt { font-weight: 650; } .attributes dd { margin: 0; }
    .issues, .links { margin: 8px 0; padding-left: 22px; } .muted, .path { color: var(--muted); }
    a { color: #1769aa; } .screenshots { display: flex; flex-wrap: wrap; gap: 16px; }
    figure { margin: 0; } img { display: block; max-width: min(100%, 720px); max-height: 640px; border: 1px solid var(--line); object-fit: contain; }
    figcaption { margin-top: 7px; }
    @media (prefers-color-scheme: dark) { :root { --bg: #11161b; --panel: #1a2128; --text: #e9eef2; --muted: #aeb8c2; --line: #37414b; } a { color: #75bfff; } }
    @media (max-width: 620px) { .summary { grid-template-columns: 1fr; gap: 2px; } .summary dd { margin-bottom: 8px; } main { width: min(100% - 16px, 1180px); margin-top: 8px; } }
  </style>
</head>
<body>
<main>
$body</main>
</body>
</html>
"""

    private fun requireOutputOutsideBundle(
        bundle: Path,
        output: Path,
    ) {
        require(output != bundle && !output.startsWith(bundle)) {
            "Report output must be outside the source evidence bundle."
        }
        if (!Files.exists(bundle, LinkOption.NOFOLLOW_LINKS)) return
        val realBundle = bundle.toRealPath()
        val existingAncestor = generateSequence(output) { it.parent }.firstOrNull { Files.exists(it, LinkOption.NOFOLLOW_LINKS) }
        if (existingAncestor != null) {
            val physicalOutput = existingAncestor.toRealPath().resolve(existingAncestor.relativize(output)).normalize()
            require(physicalOutput != realBundle && !physicalOutput.startsWith(realBundle)) {
                "Report output must be outside the source evidence bundle."
            }
        }
    }

    private fun writeAtomically(
        output: Path,
        html: String,
    ) {
        val parent = requireNotNull(output.parent) { "Report output must have a parent directory." }
        Files.createDirectories(parent)
        val temporary = Files.createTempFile(parent, ".droidproof-report-", ".html")
        try {
            Files.writeString(temporary, html, StandardCharsets.UTF_8)
            try {
                Files.move(temporary, output, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
            } catch (_: AtomicMoveNotSupportedException) {
                Files.move(temporary, output, StandardCopyOption.REPLACE_EXISTING)
            }
        } finally {
            Files.deleteIfExists(temporary)
        }
    }
}

private class LinkResolver(
    private val bundle: Path,
    private val reportDirectory: Path,
) {
    fun href(path: BundleRelativePath): String {
        val target = path.value.split('/').fold(bundle) { current, segment -> current.resolve(segment) }.normalize()
        check(target.startsWith(bundle)) { "Verified evidence path escaped its bundle." }
        val relative = reportDirectory.relativize(target).toString().replace('\\', '/')
        return URI(null, null, relative, null).rawPath
    }
}

private object Html {
    fun escape(value: String): String =
        buildString(value.length) {
            value.forEach { character ->
                append(
                    when (character) {
                        '&' -> "&amp;"
                        '<' -> "&lt;"
                        '>' -> "&gt;"
                        '\"' -> "&quot;"
                        '\'' -> "&#39;"
                        else -> character
                    },
                )
            }
        }
}
