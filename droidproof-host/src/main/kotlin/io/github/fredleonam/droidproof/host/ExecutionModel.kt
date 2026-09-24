package io.github.fredleonam.droidproof.host

import io.github.fredleonam.droidproof.mockserver.RequestContractOutcome
import io.github.fredleonam.droidproof.model.BundleRelativePath
import io.github.fredleonam.droidproof.model.EvidenceCompleteness
import io.github.fredleonam.droidproof.model.ExecutionStatus
import io.github.fredleonam.droidproof.model.ScenarioVerdict
import io.github.fredleonam.droidproof.model.Sha256
import kotlinx.serialization.Serializable
import java.nio.file.Path

@Serializable
enum class ExecutionStage {
    PREFLIGHT,
    ENVIRONMENT,
    ENVIRONMENT_CONTINUITY,
    ENVIRONMENT_RESTORE,
    ARTIFACT_BINDING,
    LAUNCH,
    ASSERTION,
    CAPTURE,
    FINALIZATION,
    NETWORK_SETUP,
    NETWORK_EVALUATION,
}

@Serializable
enum class StageStatus {
    SUCCEEDED,
    FAILED,
    CANCELLED,
    SKIPPED,
}

@Serializable
data class StageOutcome(
    val stage: ExecutionStage,
    val status: StageStatus,
    val startedAt: String,
    val endedAt: String,
    val detail: String? = null,
)

@Serializable
data class HostObservation(
    val timestamp: String,
    val category: String,
    val detail: String,
)

@Serializable
enum class AssertionOutcome {
    MATCHED,
    NOT_MATCHED,
    NOT_EVALUATED,
}

@Serializable
data class AssertionDocument(
    val outcome: AssertionOutcome,
    val expectedPackage: String,
    val expectedResourceId: String,
    val expectedText: String,
    val expectedContentDescription: String? = null,
    val hierarchyPath: BundleRelativePath? = null,
    val successfulHierarchyObservations: Int = 0,
    val detail: String,
)

@Serializable
enum class NetworkEvaluationOutcome {
    MATCHED,
    MISMATCHED,
    NOT_EVALUATED,
}

@Serializable
data class NetworkExchangeSummary(
    val sequence: Int,
    val method: String,
    val path: String,
    val responseStatus: Int,
    val evidencePath: BundleRelativePath,
    val requestContractOutcome: RequestContractOutcome = RequestContractOutcome.NOT_EVALUATED,
)

@Serializable
data class NetworkEvaluationDocument(
    val outcome: NetworkEvaluationOutcome,
    val expectedExchangeCount: Int,
    val observedExchangeCount: Int,
    val exchanges: List<NetworkExchangeSummary> = emptyList(),
    val detail: String,
    val limitations: List<String> =
        listOf(
            "Network evidence records only exchanges observed by DroidProof's controlled mock server.",
            "It is not packet capture and does not prove the absence or content of arbitrary Android network traffic.",
            "Server-observed sequence establishes ordering at this server, not a globally synchronized causal clock.",
        ),
)

@Serializable
enum class StepType(val timelineEventType: String, val hierarchySuffix: String, val deviceOperationCount: Int) {
    @kotlinx.serialization.SerialName("tapUiNode")
    TAP_UI_NODE("scenario.step.tap", "tap-before", 2),

    @kotlinx.serialization.SerialName("typeTextUiNode")
    TYPE_TEXT_UI_NODE("scenario.step.type_text", "input-before", 3),

    @kotlinx.serialization.SerialName("assertUiNode")
    ASSERT_UI_NODE("scenario.step.assert", "assert", 2),

    @kotlinx.serialization.SerialName("assertComposeSemantics")
    ASSERT_COMPOSE_SEMANTICS("scenario.step.assert_compose_semantics", "compose-semantics", 2),
}

@Serializable
enum class StepStatus {
    SUCCEEDED,
    ASSERTION_FAILED,
    ERROR,
    CANCELLED,
    SKIPPED,
}

@Serializable
data class StepOutcome(
    val index: Int,
    val type: StepType,
    val status: StepStatus,
    val hostStartedAt: String,
    val hostEndedAt: String,
    val detail: String? = null,
    val hierarchyPath: BundleRelativePath? = null,
    val assertion: AssertionDocument? = null,
)

@Serializable
data class ExecutionResultDocument(
    val resultSchemaVersion: Int = 2,
    val executionId: String,
    val hostStartedAt: String,
    val hostEndedAt: String,
    val status: ExecutionStatus,
    val verdict: ScenarioVerdict,
    val evidenceCompleteness: EvidenceCompleteness,
    val stages: List<StageOutcome>,
    val observations: List<HostObservation>,
    val assertion: AssertionDocument,
    val steps: List<StepOutcome> = emptyList(),
    val network: NetworkEvaluationDocument? = null,
    val primaryError: String? = null,
    val finalizationError: String? = null,
)

@Serializable
enum class InstallationAction {
    NOT_ATTEMPTED,
    INSTALLED_ABSENT_PACKAGE,
    REUSED_MATCHING_INSTALLATION,
    REPLACED_EXISTING_INSTALLATION,
    REFUSED_DIFFERENT_INSTALLATION,
}

@Serializable
data class InstalledArtifactObservation(
    val observedAt: String,
    val packagePaths: List<String>,
    val sha256: Sha256? = null,
    val unavailableReason: String? = null,
)

@Serializable
data class ArtifactBindingDocument(
    val bindingSchemaVersion: Int = 1,
    val packageName: String,
    val inputApkSha256: Sha256,
    val action: InstallationAction,
    val beforeLaunch: InstalledArtifactObservation? = null,
    val afterCapture: InstalledArtifactObservation? = null,
    val limitations: List<String> =
        listOf(
            "APK byte equality is observed at discrete points and is not continuous attestation against concurrent updates.",
            "No signing-certificate fingerprint was collected; byte hashes do not authenticate the producer.",
            "Only one application APK in the emulator primary user is supported.",
        ),
)

data class SmokeRunResult(
    val output: Path?,
    val diagnostic: Path?,
    val document: ExecutionResultDocument?,
    val bundleIntegrityValid: Boolean,
) {
    val isSuccessful: Boolean
        get() =
            document?.status == ExecutionStatus.COMPLETED &&
                document.verdict == ScenarioVerdict.PASSED &&
                document.evidenceCompleteness == EvidenceCompleteness.COMPLETE &&
                bundleIntegrityValid
}

fun interface CancellationSignal {
    fun isCancelled(): Boolean
}

fun interface MonotonicClock {
    fun nanoTime(): Long
}

fun interface ScenarioWaiter {
    @Throws(InterruptedException::class)
    fun delay(millis: Long)
}

internal object SystemCancellationSignal : CancellationSignal {
    override fun isCancelled(): Boolean = Thread.currentThread().isInterrupted
}

internal object SystemMonotonicClock : MonotonicClock {
    override fun nanoTime(): Long = System.nanoTime()
}

internal object ThreadScenarioWaiter : ScenarioWaiter {
    override fun delay(millis: Long) = Thread.sleep(millis)
}
