package io.github.fredleonam.droidproof.model

import kotlinx.serialization.Serializable

@Serializable
data class EmulatorEnvironmentContractV1(
    val schemaVersion: Int,
    val locale: String,
    val orientation: Orientation,
    val animations: AnimationConfiguration,
) {
    init {
        require(schemaVersion == 1) { "Unsupported environment-contract schema version." }
        require(isCanonicalBcp47(locale)) { "Locale must be a canonical BCP-47 language tag such as en-US." }
    }
}

@Serializable
enum class EnvironmentEvaluationOutcome {
    MATCHED,
    MISMATCHED,
    UNAVAILABLE,
}

@Serializable
data class EnvironmentObservation(
    val normalizedValue: String? = null,
    val rawSafeValue: String? = null,
    val unavailableReason: String? = null,
) {
    init {
        require((normalizedValue == null) != (unavailableReason == null)) {
            "An environment observation must contain a normalized value or an unavailable reason."
        }
        require(normalizedValue == null || normalizedValue.isNotBlank())
        require(rawSafeValue == null || rawSafeValue.isNotBlank())
        require(unavailableReason == null || unavailableReason.isNotBlank())
    }
}

@Serializable
data class ObservedAnimationScales(
    val windowScale: EnvironmentObservation,
    val transitionScale: EnvironmentObservation,
    val animatorScale: EnvironmentObservation,
)

@Serializable
data class EmulatorEnvironmentObservations(
    val locale: EnvironmentObservation,
    val orientation: EnvironmentObservation,
    val animations: ObservedAnimationScales,
)

@Serializable
data class EnvironmentFieldEvaluation(
    val field: String,
    val requested: String,
    val observed: String? = null,
    val outcome: EnvironmentEvaluationOutcome,
    val explanation: String,
)

@Serializable
data class EmulatorEnvironmentEvaluationV1(
    val evaluationSchemaVersion: Int = 1,
    val requested: EmulatorEnvironmentContractV1,
    val observed: EmulatorEnvironmentObservations,
    val fields: List<EnvironmentFieldEvaluation>,
    val outcome: EnvironmentEvaluationOutcome,
    val explanation: String,
    val limitations: List<String> =
        listOf(
            "Locale is the validated persist.sys.locale system property for the selected emulator.",
            "Orientation is the configured user-0 rotation only when Android auto-rotation is disabled.",
            "Animation scales are validated global settings read at one point in time.",
            "DroidProof did not change, lock, restore, provision, start, or stop the emulator.",
        ),
) {
    init {
        require(evaluationSchemaVersion == 1) { "Unsupported environment-evaluation schema version." }
        require(fields.map { it.field } == EXPECTED_ENVIRONMENT_FIELDS) {
            "Environment evaluation fields are not canonical."
        }
        require(explanation.isNotBlank() && explanation.length <= 512)
        require(fields.all { it.explanation.isNotBlank() && it.explanation.length <= 256 })
        require(limitations.size in 1..16 && limitations.all { it.isNotBlank() && it.length <= 512 })
    }
}

val EXPECTED_ENVIRONMENT_FIELDS =
    listOf("locale", "orientation", "animations.windowScale", "animations.transitionScale", "animations.animatorScale")

private fun isCanonicalBcp47(value: String): Boolean {
    if (value.length !in 2..64 || value.any { it.code !in 0x21..0x7e }) return false
    val locale =
        try {
            java.util.Locale.Builder().setLanguageTag(value).build()
        } catch (_: java.util.IllformedLocaleException) {
            return false
        }
    return locale.language.isNotBlank() && locale.language != "und" && locale.toLanguageTag() == value
}
