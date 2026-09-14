package io.github.fredleonam.droidproof.host

import io.github.fredleonam.droidproof.evidence.Sha256Calculator
import io.github.fredleonam.droidproof.mockserver.MockServerLimits
import io.github.fredleonam.droidproof.mockserver.MockServerPlan
import io.github.fredleonam.droidproof.mockserver.PlannedHttpResponse
import io.github.fredleonam.droidproof.model.ScenarioId
import io.github.fredleonam.droidproof.model.Sha256
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.ByteArrayInputStream
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path

@Serializable
data class UiExpectation(
    val resourceId: String,
    val text: String,
)

interface ScenarioDefinition {
    val schemaVersion: Int
    val scenarioId: ScenarioId
    val expectedPackage: String
    val launchComponent: String
    val orderedSteps: List<ScenarioStep>
    val backendPlan: ScenarioBackendPlan? get() = null
}

@Serializable
data class ScenarioBackendPlan(
    val devicePort: Int,
    val method: String,
    val path: String,
    val requestBodyLimitBytes: Long,
    val responseBodyLimitBytes: Long,
    val responsePlan: List<PlannedHttpResponse>,
) {
    init {
        require(devicePort in 1024..65535) { "Device backend port must be between 1024 and 65535." }
        require(requestBodyLimitBytes in 1..MAX_NETWORK_BODY_BYTES) { "Request-body limit is outside supported bounds." }
        require(responseBodyLimitBytes in 1..MAX_NETWORK_BODY_BYTES) { "Response-body limit is outside supported bounds." }
        val serverPlan = MockServerPlan(method, path, responsePlan)
        responsePlan.forEach { response ->
            require(response.mediaType == "application/json") { "Scenario-v3 responses must use application/json." }
            require(runCatching { scenarioJson.parseToJsonElement(response.body) }.isSuccess) {
                "Scenario-v3 response bodies must be valid JSON."
            }
            require(response.body.toByteArray(StandardCharsets.UTF_8).size.toLong() <= responseBodyLimitBytes) {
                "Planned response body exceeds the configured response-body limit."
            }
        }
        require(serverPlan.responses.size + EXTRA_EXCHANGE_ALLOWANCE <= MAX_NETWORK_EXCHANGES)
    }

    fun serverPlan(): MockServerPlan = MockServerPlan(method, path, responsePlan)

    fun serverLimits(): MockServerLimits =
        MockServerLimits(
            requestBodyLimitBytes,
            responseBodyLimitBytes,
            responsePlan.size + EXTRA_EXCHANGE_ALLOWANCE,
        )
}

@Serializable
sealed class ScenarioStep {
    abstract val resourceId: String
}

internal val ScenarioStep.stepType: StepType
    get() =
        when (this) {
            is TypeTextUiNode -> StepType.TYPE_TEXT_UI_NODE
            is TapUiNode -> StepType.TAP_UI_NODE
            is AssertUiNode -> StepType.ASSERT_UI_NODE
        }

@Serializable
@SerialName("tapUiNode")
data class TapUiNode(override val resourceId: String) : ScenarioStep()

@Serializable
@SerialName("typeTextUiNode")
data class TypeTextUiNode(
    override val resourceId: String,
    val text: String,
) : ScenarioStep() {
    init {
        validateInputText(text)
    }
}

internal fun validateInputText(text: String) {
    require(text.length in 1..128 && SAFE_INPUT_TEXT.matches(text)) {
        "Input text must contain 1 to 128 ASCII letters, digits, or . _ - @ characters; use non-secret test data only."
    }
}

private val SAFE_INPUT_TEXT = Regex("[A-Za-z0-9._@-]+")

@Serializable
@SerialName("assertUiNode")
data class AssertUiNode(
    override val resourceId: String,
    val text: String,
    val deadlineMillis: Long,
    val pollIntervalMillis: Long,
) : ScenarioStep() {
    init {
        validateAssertion(text, deadlineMillis, pollIntervalMillis)
    }
}

@Serializable
data class SmokeScenarioV2(
    override val schemaVersion: Int,
    override val scenarioId: ScenarioId,
    override val expectedPackage: String,
    override val launchComponent: String,
    val steps: List<ScenarioStep>,
) : ScenarioDefinition {
    override val orderedSteps: List<ScenarioStep> get() = steps

    init {
        require(schemaVersion == 2) { "Unsupported scenario schema version." }
        validateScope(expectedPackage, launchComponent)
        require(steps.size in 1..100) { "Scenario must contain 1 to 100 steps." }
        require(steps.last() is AssertUiNode) { "Scenario must end with an assertion." }
        steps.forEach { validateResource(expectedPackage, it.resourceId) }
    }
}

@Serializable
data class SmokeScenarioV3(
    override val schemaVersion: Int,
    override val scenarioId: ScenarioId,
    override val expectedPackage: String,
    override val launchComponent: String,
    override val backendPlan: ScenarioBackendPlan,
    val steps: List<ScenarioStep>,
) : ScenarioDefinition {
    override val orderedSteps: List<ScenarioStep> get() = steps

    init {
        require(schemaVersion == 3) { "Unsupported scenario schema version." }
        validateScope(expectedPackage, launchComponent)
        require(steps.size in 1..100) { "Scenario must contain 1 to 100 steps." }
        require(steps.last() is AssertUiNode) { "Scenario must end with an assertion." }
        steps.forEach { validateResource(expectedPackage, it.resourceId) }
    }
}

@Serializable
data class SmokeScenario(
    override val schemaVersion: Int,
    override val scenarioId: ScenarioId,
    override val expectedPackage: String,
    override val launchComponent: String,
    val expectedUi: UiExpectation,
    val assertionDeadlineMillis: Long,
    val pollIntervalMillis: Long,
) : ScenarioDefinition {
    override val orderedSteps: List<ScenarioStep>
        get() = listOf(AssertUiNode(expectedUi.resourceId, expectedUi.text, assertionDeadlineMillis, pollIntervalMillis))

    init {
        require(schemaVersion == 1) { "Unsupported scenario schema version: $schemaVersion." }
        validateScope(expectedPackage, launchComponent)
        validateResource(expectedPackage, expectedUi.resourceId)
        validateAssertion(expectedUi.text, assertionDeadlineMillis, pollIntervalMillis)
    }
}

private fun validateScope(
    expectedPackage: String,
    launchComponent: String,
) {
    require(PACKAGE_NAME.matches(expectedPackage)) { "Expected package name is invalid." }
    val parts = launchComponent.split('/')
    require(parts.size == 2 && parts[0] == expectedPackage) { "Launch component must belong to the expected package." }
    require(CLASS_NAME.matches(parts[1]) && parts[1].startsWith("$expectedPackage.")) {
        "Launch activity must be a fully qualified class in the expected package."
    }
}

private fun validateResource(
    expectedPackage: String,
    resourceId: String,
) {
    require(resourceId.startsWith("$expectedPackage:id/") && RESOURCE_ID.matches(resourceId)) {
        "UI resource ID must be fully qualified with the expected package."
    }
}

private fun validateAssertion(
    text: String,
    deadline: Long,
    poll: Long,
) {
    require(text.isNotEmpty() && text.length <= MAX_TEXT_LENGTH) { "Expected UI text must contain 1 to $MAX_TEXT_LENGTH characters." }
    require(deadline in 1..MAX_ASSERTION_DEADLINE_MILLIS) { "Assertion deadline is outside supported bounds." }
    require(poll in 1..deadline) { "Poll interval must be positive and no longer than the assertion deadline." }
}

data class AcceptedScenario(
    val scenario: ScenarioDefinition,
    val exactBytes: ByteArray,
    val sha256: Sha256,
)

object SmokeScenarioLoader {
    fun load(path: Path): AcceptedScenario {
        val attributes = Files.readAttributes(path, java.nio.file.attribute.BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS)
        require(attributes.isRegularFile && !attributes.isSymbolicLink) {
            "Scenario must be a regular non-symbolic-link file."
        }
        require(attributes.size() in 1..MAX_SCENARIO_BYTES) {
            "Scenario document must contain 1 to $MAX_SCENARIO_BYTES bytes."
        }
        val bytes = Files.newInputStream(path, LinkOption.NOFOLLOW_LINKS).use { it.readNBytes(MAX_SCENARIO_BYTES.toInt() + 1) }
        require(bytes.size.toLong() in 1..MAX_SCENARIO_BYTES) { "Scenario document exceeds accepted byte bounds." }
        val text =
            StandardCharsets.UTF_8
                .newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(bytes))
                .toString()
        val version = scenarioJson.parseToJsonElement(text).jsonObject.getValue("schemaVersion").jsonPrimitive.int
        val scenario =
            when (version) {
                1 -> scenarioJson.decodeFromString<SmokeScenario>(text)
                2 -> scenarioJson.decodeFromString<SmokeScenarioV2>(text)
                3 -> scenarioJson.decodeFromString<SmokeScenarioV3>(text)
                else -> error("Unsupported scenario schema version: $version.")
            }
        return AcceptedScenario(scenario, bytes, Sha256Calculator.calculate(ByteArrayInputStream(bytes)))
    }
}

private const val MAX_SCENARIO_BYTES = 1024L * 1024L
private const val MAX_TEXT_LENGTH = 1024
private const val MAX_ASSERTION_DEADLINE_MILLIS = 300_000L
private const val MAX_NETWORK_BODY_BYTES = 1024L * 1024L
private const val MAX_NETWORK_EXCHANGES = 64
private const val EXTRA_EXCHANGE_ALLOWANCE = 8
private val PACKAGE_NAME = Regex("[a-zA-Z][a-zA-Z0-9_]*(?:\\.[a-zA-Z][a-zA-Z0-9_]*)+")
private val CLASS_NAME = Regex("[a-zA-Z][a-zA-Z0-9_]*(?:\\.[a-zA-Z][a-zA-Z0-9_]*)+")
private val RESOURCE_ID = Regex("[a-zA-Z][a-zA-Z0-9_.]*:id/[a-zA-Z][a-zA-Z0-9_]*")
private val scenarioJson =
    Json {
        ignoreUnknownKeys = false
    }
