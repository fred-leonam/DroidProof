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
