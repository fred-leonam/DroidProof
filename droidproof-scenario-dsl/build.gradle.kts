plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ktlint)
    `maven-publish`
}

kotlin {
    jvmToolchain(17)
    compilerOptions {
        allWarningsAsErrors.set(true)
        freeCompilerArgs.add("-Xjsr305=strict")
    }
}

dependencies {
    implementation(project(":droidproof-host"))
    implementation(project(":droidproof-mock-server"))
    implementation(project(":droidproof-model"))
    implementation(libs.kotlinx.serialization.json)
    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testImplementation(libs.kotlin.test)
}

tasks.test {
    useJUnitPlatform()
}

tasks.register<JavaExec>("generateExternalConsumerScenario") {
    group = "verification"
    description = "Generates the schema-v6 scenario used by the isolated plugin consumer check."
    classpath = sourceSets.main.get().runtimeClasspath
    mainClass.set("io.github.fredleonam.droidproof.scenario.GenerateExternalConsumerScenarioKt")
    args(
        providers.gradleProperty("droidproof.consumerScenarioPath")
            .orElse(layout.buildDirectory.file("external-consumer/consumer-proof.json").map { it.asFile.absolutePath })
            .get(),
    )
}

publishing {
    publications {
        create<MavenPublication>("scenarioDsl") {
            from(components["java"])
            artifactId = "droidproof-scenario-dsl"
            pom {
                name.set("DroidProof Scenario DSL")
                description.set("Typed Kotlin authoring DSL for DroidProof scenario schema v6.")
                url.set("https://github.com/fred-leonam/DroidProof")
            }
        }
    }
}
