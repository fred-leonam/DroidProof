package io.github.fredleonam.droidproof.scenario

import java.nio.file.Path

fun main(arguments: Array<String>) {
    require(arguments.size == 1) { "Usage: GenerateExternalConsumerScenario OUTPUT" }
    scenario {
        id = "consumer-proof"
        packageName = "com.example.consumer"
        backend {
            exchange("catalog") {
                method = "GET"
                target = "/catalog"
                respond(200, "{\"items\":[]}")
            }
            exchange("order") {
                method = "POST"
                target = "/orders"
                expectJson("{\"customer\":\"Proof42\"}")
                respond(201, "{\"orderId\":\"42\"}")
            }
        }
        typeText("customer", "Proof42")
        tap("submit")
        assertCompose("order_status", "Order 42 created", "Order status")
    }.writeTo(Path.of(arguments.single()))
}
