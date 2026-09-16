package io.github.fredleonam.droidproof.model

import kotlinx.serialization.Serializable

/** A bounded point-in-time observation, deliberately not an image provenance claim. */
@Serializable
data class EmulatorCapabilityObservationV1(
    val capabilitySchemaVersion: Int = 1,
    val apiLevel: Int,
    val buildFingerprint: String,
    val bootIdentifier: String,
    val commandSurfaces: List<String>,
    val limitations: List<String> =
        listOf(
            "Image metadata is an observation, not image provenance.",
            "The boot identifier is a bounded continuity signal, not remote attestation.",
            "Advertised command surfaces do not prove future write permission.",
        ),
) {
    init {
        require(capabilitySchemaVersion == 1)
        require(apiLevel in 1..999)
        require(buildFingerprint.length in 1..256 && buildFingerprint.all { it.code in 0x21..0x7e })
        require(Regex("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}").matches(bootIdentifier))
        require(commandSurfaces.isNotEmpty() && commandSurfaces.all { it.length in 1..96 && it.all { c -> c.code in 0x21..0x7e } })
    }
}

@Serializable
enum class EmulatorContinuityOutcome { MATCHED, MISMATCHED, UNAVAILABLE }

@Serializable
data class EmulatorContinuityDocumentV1(
    val continuitySchemaVersion: Int = 1,
    val initial: EmulatorCapabilityObservationV1,
    val final: EmulatorCapabilityObservationV1? = null,
    val environment: EmulatorEnvironmentEvaluationV1? = null,
    val outcome: EmulatorContinuityOutcome,
    val explanation: String,
    val limitations: List<String> =
        listOf(
            "The cooperative lease cannot prevent Android Studio, humans, or arbitrary ADB processes from changing the emulator.",
            "Sequential observations do not prove uninterrupted state stability.",
        ),
) {
    init {
        require(continuitySchemaVersion == 1 && explanation.isNotBlank() && explanation.length <= 512)
    }
}

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
enum class EnvironmentExecutionMode { VERIFY_ONLY, APPLY_AND_RESTORE }

@Serializable
enum class EnvironmentRestorationOutcome { NOT_REQUIRED, NOT_ATTEMPTED, RESTORED, RESTORE_MISMATCH, RESTORE_UNAVAILABLE }

@Serializable
data class EmulatorEnvironmentState(
    val locale: String,
    val accelerometerRotation: Int,
    val userRotation: Int,
    val windowScale: Double,
    val transitionScale: Double,
    val animatorScale: Double,
) {
    init {
        require(accelerometerRotation in 0..1 && userRotation in 0..3)
        require(listOf(windowScale, transitionScale, animatorScale).all { it.isFinite() && it >= 0.0 })
    }
}

@Serializable
data class EnvironmentTransactionDocument(
    val transactionSchemaVersion: Int = 1,
    val mode: EnvironmentExecutionMode,
    val original: EmulatorEnvironmentState? = null,
    val mutationAttempted: Boolean = false,
    val requestedVerification: EnvironmentEvaluationOutcome? = null,
    val restorationAttempted: Boolean = false,
    val restored: EmulatorEnvironmentState? = null,
    val restorationOutcome: EnvironmentRestorationOutcome,
    val detail: String,
    val limitations: List<String> =
        listOf(
            "Restoration is best effort and cannot survive SIGKILL, host power loss, emulator crash, or permanent ADB loss.",
            "The serial-scoped in-process boundary does not prevent external emulator mutation.",
        ),
) {
    init {
        require(detail.isNotBlank() && detail.length <= 512)
    }
}

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
            "Environment evaluation is a bounded point-in-time observation and does not prove stability for the entire execution.",
            "Requested mutation and restoration are documented separately by environment transaction evidence.",
            "DroidProof does not provision, start, or stop the emulator.",
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
