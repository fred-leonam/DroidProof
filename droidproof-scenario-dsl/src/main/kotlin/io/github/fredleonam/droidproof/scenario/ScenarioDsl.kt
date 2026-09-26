package io.github.fredleonam.droidproof.scenario

import io.github.fredleonam.droidproof.host.AssertComposeSemantics
import io.github.fredleonam.droidproof.host.AssertUiNode
import io.github.fredleonam.droidproof.host.ScenarioBackendPlanV4
import io.github.fredleonam.droidproof.host.ScenarioExpectedRequest
import io.github.fredleonam.droidproof.host.ScenarioStep
import io.github.fredleonam.droidproof.host.SmokeScenarioV6
import io.github.fredleonam.droidproof.host.TapUiNode
import io.github.fredleonam.droidproof.host.TypeTextUiNode
import io.github.fredleonam.droidproof.mockserver.NetworkTransport
import io.github.fredleonam.droidproof.mockserver.PlannedHttpResponse
import io.github.fredleonam.droidproof.mockserver.ResponseFault
import io.github.fredleonam.droidproof.mockserver.ResponseFaultKind
import io.github.fredleonam.droidproof.model.ScenarioId
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption

@DslMarker
annotation class DroidProofScenarioDsl

/** A validated, immutable scenario document ready to save or pass to DroidProof. */
class ScenarioDocument internal constructor(private val scenario: SmokeScenarioV6) {
    fun toJson(): String = scenarioJson.encodeToString(scenario) + "\n"

    fun writeTo(path: Path): Path {
        path.toAbsolutePath().parent?.let(Files::createDirectories)
        Files.writeString(
            path,
            toJson(),
            StandardOpenOption.CREATE,
            StandardOpenOption.TRUNCATE_EXISTING,
            StandardOpenOption.WRITE,
        )
        return path
    }
}

fun scenario(block: ScenarioBuilder.() -> Unit): ScenarioDocument = ScenarioDocument(ScenarioBuilder().apply(block).build())

@DroidProofScenarioDsl
class ScenarioBuilder internal constructor() {
    lateinit var id: String
    lateinit var packageName: String
    var launchActivity: String = ".MainActivity"

    private val steps = mutableListOf<ScenarioStep>()
    private var backend: ScenarioBackendPlanV4? = null

    fun typeText(
        resource: String,
        text: String,
    ) {
        steps += TypeTextUiNode(resourceId(resource), text)
    }

    fun tap(resource: String) {
        steps += TapUiNode(resourceId(resource))
    }

    fun assertText(
        resource: String,
        text: String,
        deadlineMillis: Long = 15_000,
        pollIntervalMillis: Long = 250,
    ) {
        steps += AssertUiNode(resourceId(resource), text, deadlineMillis, pollIntervalMillis)
    }

    fun assertCompose(
        testTag: String,
        text: String,
        contentDescription: String,
        deadlineMillis: Long = 15_000,
        pollIntervalMillis: Long = 250,
    ) {
        steps += AssertComposeSemantics(testTag, text, contentDescription, deadlineMillis, pollIntervalMillis)
    }

    fun backend(block: BackendBuilder.() -> Unit) {
        check(backend == null) { "A scenario may declare only one backend." }
        backend = BackendBuilder().apply(block).build()
    }

    internal fun build(): SmokeScenarioV6 {
        check(::id.isInitialized) { "Scenario id is required." }
        check(::packageName.isInitialized) { "Scenario packageName is required." }
        val component =
            when {
                launchActivity.startsWith(".") -> "$packageName/$packageName$launchActivity"
                '/' in launchActivity -> launchActivity
                else -> "$packageName/$launchActivity"
            }
        return SmokeScenarioV6(6, ScenarioId(id), packageName, component, backend, steps.toList())
    }

    private fun resourceId(resource: String): String = if (":id/" in resource) resource else "$packageName:id/$resource"
}

enum class Transport { HTTP, HTTPS }

@DroidProofScenarioDsl
class BackendBuilder internal constructor() {
    var devicePort: Int = 38_637
    var method: String = "POST"
    var path: String = "/orders"
    var requestBodyLimitBytes: Long = 4_096
    var responseBodyLimitBytes: Long = 4_096
    var transport: Transport = Transport.HTTP

    private var expectedRequest: ScenarioExpectedRequest? = null
    private val responses = mutableListOf<PlannedHttpResponse>()

    fun expectJson(
        body: String,
        mediaType: String = "application/json; charset=utf-8",
    ) {
        check(expectedRequest == null) { "An expected request was already declared." }
        expectedRequest = ScenarioExpectedRequest(mediaType, body)
    }

    fun respond(
        status: Int,
        body: String,
        mediaType: String = "application/json",
        delayMillis: Long? = null,
        dropConnection: Boolean = false,
    ) {
        require(delayMillis == null || !dropConnection) { "A response cannot be delayed and dropped." }
        val fault =
            when {
                delayMillis != null -> ResponseFault(ResponseFaultKind.DELAY_RESPONSE, delayMillis)
                dropConnection -> ResponseFault(ResponseFaultKind.DROP_CONNECTION)
                else -> null
            }
        responses += PlannedHttpResponse(status, body, mediaType, fault)
    }

    internal fun build(): ScenarioBackendPlanV4 =
        ScenarioBackendPlanV4(
            devicePort,
            method,
            path,
            requestBodyLimitBytes,
            responseBodyLimitBytes,
            requireNotNull(expectedRequest) { "The backend expected JSON request is required." },
            responses.toList(),
            NetworkTransport.valueOf(transport.name),
        )
}

@OptIn(ExperimentalSerializationApi::class)
private val scenarioJson =
    Json {
        prettyPrint = true
        prettyPrintIndent = "  "
        encodeDefaults = false
        classDiscriminator = "type"
    }
