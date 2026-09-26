package io.github.fredleonam.droidproof.scenario

import io.github.fredleonam.droidproof.host.AssertComposeSemantics
import io.github.fredleonam.droidproof.host.SmokeScenarioLoader
import io.github.fredleonam.droidproof.host.SmokeScenarioV6
import io.github.fredleonam.droidproof.mockserver.ResponseFaultKind
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class ScenarioDslTest {
    @Test
    fun `writes deterministic schema v6 JSON accepted by the strict runner loader`() {
        val document = completeScenario()
        val first = document.writeTo(createTempDirectory("droidproof-dsl").resolve("scenario.json"))
        val second = document.writeTo(createTempDirectory("droidproof-dsl").resolve("scenario.json"))

        val accepted = SmokeScenarioLoader.load(first)
        val scenario = accepted.scenario as SmokeScenarioV6

        assertEquals(document.toJson(), first.toFile().readText())
        assertEquals(first.toFile().readText(), second.toFile().readText())
        assertEquals(6, scenario.schemaVersion)
        assertEquals("release-order", scenario.scenarioId.value)
        assertEquals("com.example.app/com.example.app.MainActivity", scenario.launchComponent)
        assertEquals("com.example.app:id/customer", scenario.steps.first().resourceId)
        val compose = scenario.steps.last() as AssertComposeSemantics
        assertEquals("order_status", compose.resourceId)
        assertEquals("Order 42 created", compose.text)
        assertEquals("Order status", compose.contentDescription)
        val backend = requireNotNull(scenario.backendPlan)
        assertEquals("{\"customer\":\"Proof42\"}", backend.expectedRequest.body)
        assertEquals(listOf(503, 201), backend.responsePlan.map { it.status })
        assertEquals(ResponseFaultKind.DELAY_RESPONSE, backend.responsePlan.first().fault?.kind)
        assertEquals(250L, backend.responsePlan.first().fault?.delayMillis)
        assertEquals(ResponseFaultKind.DROP_CONNECTION, backend.responsePlan.last().fault?.kind)
    }

    @Test
    fun `rejects missing required DSL declarations`() {
        val missingId =
            assertFailsWith<IllegalStateException> {
                scenario {
                    packageName = "com.example.app"
                    assertText("status", "Ready")
                }
            }
        assertContains(missingId.message.orEmpty(), "Scenario id")

        val missingExpectedRequest =
            assertFailsWith<IllegalArgumentException> {
                scenario {
                    id = "missing-request"
                    packageName = "com.example.app"
                    backend {
                        respond(200, "{}")
                    }
                    assertText("status", "Ready")
                }
            }
        assertContains(missingExpectedRequest.message.orEmpty(), "expected JSON request")
    }

    @Test
    fun `rejects invalid bounded response and assertion input`() {
        val contradictoryFault =
            assertFailsWith<IllegalArgumentException> {
                scenario {
                    id = "bad-fault"
                    packageName = "com.example.app"
                    backend {
                        expectJson("{}")
                        respond(200, "{}", delayMillis = 1, dropConnection = true)
                    }
                    assertText("status", "Ready")
                }
            }
        assertContains(contradictoryFault.message.orEmpty(), "cannot be delayed and dropped")

        val invalidComposeTag =
            assertFailsWith<IllegalArgumentException> {
                scenario {
                    id = "bad-compose"
                    packageName = "com.example.app"
                    assertCompose("not a tag", "Ready", "Status")
                }
            }
        assertContains(invalidComposeTag.message.orEmpty(), "safe test tag")
    }

    private fun completeScenario(): ScenarioDocument =
        scenario {
            id = "release-order"
            packageName = "com.example.app"
            backend {
                transport = Transport.HTTPS
                expectJson("{\"customer\":\"Proof42\"}")
                respond(503, "{\"error\":\"retry\"}", delayMillis = 250)
                respond(201, "{\"orderId\":\"42\"}", dropConnection = true)
            }
            typeText("customer", "Proof42")
            tap("submit")
            assertCompose("order_status", "Order 42 created", "Order status")
        }
}
