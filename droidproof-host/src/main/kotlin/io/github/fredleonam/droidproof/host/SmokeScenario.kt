package io.github.fredleonam.droidproof.host

import io.github.fredleonam.droidproof.evidence.Sha256Calculator
import io.github.fredleonam.droidproof.model.ScenarioId
import io.github.fredleonam.droidproof.model.Sha256
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
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

@Serializable
data class SmokeScenario(
    val schemaVersion: Int,
    val scenarioId: ScenarioId,
    val expectedPackage: String,
    val launchComponent: String,
    val expectedUi: UiExpectation,
    val assertionDeadlineMillis: Long,
    val pollIntervalMillis: Long,
) {
    init {
        require(schemaVersion == SUPPORTED_SCENARIO_VERSION) { "Unsupported scenario schema version: $schemaVersion." }
        require(PACKAGE_NAME.matches(expectedPackage)) { "Expected package name is invalid." }
        val componentParts = launchComponent.split('/')
        require(componentParts.size == 2 && componentParts[0] == expectedPackage) {
            "Launch component must belong to the expected package."
        }
        require(CLASS_NAME.matches(componentParts[1]) && componentParts[1].startsWith("$expectedPackage.")) {
            "Launch activity must be a fully qualified class in the expected package."
        }
        require(expectedUi.resourceId.startsWith("$expectedPackage:id/") && RESOURCE_ID.matches(expectedUi.resourceId)) {
            "Expected UI resource ID must be fully qualified with the expected package."
        }
        require(expectedUi.text.isNotEmpty() && expectedUi.text.length <= MAX_TEXT_LENGTH) {
            "Expected UI text must contain 1 to $MAX_TEXT_LENGTH characters."
        }
        require(assertionDeadlineMillis in 1..MAX_ASSERTION_DEADLINE_MILLIS) {
            "Assertion deadline must be between 1 and $MAX_ASSERTION_DEADLINE_MILLIS milliseconds."
        }
        require(pollIntervalMillis in 1..assertionDeadlineMillis) {
            "Poll interval must be positive and no longer than the assertion deadline."
        }
    }
}

data class AcceptedScenario(
    val scenario: SmokeScenario,
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
        val bytes = Files.readAllBytes(path)
        val text =
            StandardCharsets.UTF_8
                .newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(bytes))
                .toString()
        val scenario = scenarioJson.decodeFromString<SmokeScenario>(text)
        return AcceptedScenario(scenario, bytes, Sha256Calculator.calculate(ByteArrayInputStream(bytes)))
    }
}

private const val SUPPORTED_SCENARIO_VERSION = 1
private const val MAX_SCENARIO_BYTES = 1024L * 1024L
private const val MAX_TEXT_LENGTH = 1024
private const val MAX_ASSERTION_DEADLINE_MILLIS = 300_000L
private val PACKAGE_NAME = Regex("[a-zA-Z][a-zA-Z0-9_]*(?:\\.[a-zA-Z][a-zA-Z0-9_]*)+")
private val CLASS_NAME = Regex("[a-zA-Z][a-zA-Z0-9_]*(?:\\.[a-zA-Z][a-zA-Z0-9_]*)+")
private val RESOURCE_ID = Regex("[a-zA-Z][a-zA-Z0-9_.]*:id/[a-zA-Z][a-zA-Z0-9_]*")
private val scenarioJson =
    Json {
        ignoreUnknownKeys = false
    }
