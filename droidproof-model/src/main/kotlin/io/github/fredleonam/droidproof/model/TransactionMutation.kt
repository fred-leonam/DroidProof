package io.github.fredleonam.droidproof.model

import kotlinx.serialization.Serializable

@Serializable
enum class MutationObservationOutcome {
    MATCHED,
    DRIFT_DETECTED,
    UNAVAILABLE,
    NOT_EVALUATED,
}

@Serializable
enum class TransactionMutationCheckpoint {
    AFTER_ARTIFACT_BINDING,
    AFTER_LAUNCH,
    AFTER_SCENARIO_STEP,
    BEFORE_FINAL_CAPTURE,
    AFTER_FINAL_CAPTURE,
}

@Serializable
data class TransactionArtifactBaselineV1(
    val packageName: String,
    val inputApkSha256: Sha256,
    val installedApkSha256: Sha256,
) {
    init {
        require(PACKAGE_NAME.matches(packageName))
    }
}

@Serializable
data class TransactionMutationBaselineV1(
    val identity: EmulatorCapabilityObservationV1,
    val environment: EmulatorEnvironmentEvaluationV1? = null,
    val artifact: TransactionArtifactBaselineV1? = null,
)

@Serializable
data class IdentityMutationObservationV1(
    val outcome: MutationObservationOutcome,
    val observed: EmulatorCapabilityObservationV1? = null,
    val detail: String,
) {
    init {
        requireBoundedDetail(detail)
        require((outcome == MutationObservationOutcome.UNAVAILABLE) == (observed == null))
        require(outcome != MutationObservationOutcome.NOT_EVALUATED)
    }
}

@Serializable
data class EnvironmentMutationObservationV1(
    val outcome: MutationObservationOutcome,
    val evaluation: EmulatorEnvironmentEvaluationV1? = null,
    val detail: String,
) {
    init {
        requireBoundedDetail(detail)
        require(
            when (outcome) {
                MutationObservationOutcome.MATCHED -> evaluation?.outcome == EnvironmentEvaluationOutcome.MATCHED
                MutationObservationOutcome.DRIFT_DETECTED -> evaluation?.outcome == EnvironmentEvaluationOutcome.MISMATCHED
                MutationObservationOutcome.UNAVAILABLE -> evaluation?.outcome == EnvironmentEvaluationOutcome.UNAVAILABLE
                MutationObservationOutcome.NOT_EVALUATED -> evaluation == null
            },
        )
    }
}

@Serializable
data class ArtifactMutationObservationV1(
    val outcome: MutationObservationOutcome,
    val installedApkSha256: Sha256? = null,
    val detail: String,
) {
    init {
        requireBoundedDetail(detail)
        require(outcome != MutationObservationOutcome.NOT_EVALUATED)
        require((outcome == MutationObservationOutcome.UNAVAILABLE) == (installedApkSha256 == null))
    }
}

@Serializable
data class TransactionMutationCheckpointObservationV1(
    val sequence: Int,
    val checkpoint: TransactionMutationCheckpoint,
    val afterScenarioStep: Int? = null,
    val observedAt: UtcTimestamp,
    val identity: IdentityMutationObservationV1,
    val environment: EnvironmentMutationObservationV1,
    val artifact: ArtifactMutationObservationV1,
    val outcome: MutationObservationOutcome,
    val detail: String,
) {
    init {
        require(sequence >= 1)
        require((checkpoint == TransactionMutationCheckpoint.AFTER_SCENARIO_STEP) == (afterScenarioStep != null))
        require(afterScenarioStep == null || afterScenarioStep >= 1)
        requireBoundedDetail(detail)
        require(outcome == aggregateOutcome(identity.outcome, environment.outcome, artifact.outcome))
    }
}

@Serializable
data class TransactionMutationDocumentV1(
    val mutationObservationSchemaVersion: Int = 1,
    val baseline: TransactionMutationBaselineV1,
    val checkpoints: List<TransactionMutationCheckpointObservationV1>,
    val outcome: MutationObservationOutcome,
    val explanation: String,
    val limitations: List<String> =
        listOf(
            "Observations occur only at the recorded checkpoints and are not continuous monitoring.",
            "A matching observation does not prove uninterrupted stability between checkpoints.",
            "DroidProof does not claim exclusive emulator ownership or attribute a change to a specific actor.",
            "Only the recorded emulator identity, requested environment fields, and target APK bytes are evaluated.",
        ),
) {
    init {
        require(mutationObservationSchemaVersion == 1)
        require(checkpoints.map { it.sequence } == (1..checkpoints.size).toList())
        require(outcome == aggregateOutcome(*checkpoints.map { it.outcome }.toTypedArray()))
        requireBoundedDetail(explanation)
        require(limitations.size in 1..16 && limitations.all { it.isNotBlank() && it.length <= MAX_DETAIL_LENGTH })
    }
}

fun aggregateOutcome(vararg outcomes: MutationObservationOutcome): MutationObservationOutcome =
    when {
        MutationObservationOutcome.DRIFT_DETECTED in outcomes -> MutationObservationOutcome.DRIFT_DETECTED
        MutationObservationOutcome.UNAVAILABLE in outcomes -> MutationObservationOutcome.UNAVAILABLE
        MutationObservationOutcome.MATCHED in outcomes -> MutationObservationOutcome.MATCHED
        else -> MutationObservationOutcome.NOT_EVALUATED
    }

private fun requireBoundedDetail(detail: String) {
    require(detail.isNotBlank() && detail.length <= MAX_DETAIL_LENGTH)
}

private const val MAX_DETAIL_LENGTH = 512
private val PACKAGE_NAME = Regex("[a-zA-Z][a-zA-Z0-9_]*(?:\\.[a-zA-Z][a-zA-Z0-9_]*)+")
