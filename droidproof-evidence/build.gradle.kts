plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ktlint)
}

kotlin {
    jvmToolchain(17)
    compilerOptions {
        allWarningsAsErrors.set(true)
        freeCompilerArgs.add("-Xjsr305=strict")
    }
}

dependencies {
    implementation(project(":droidproof-model"))
    implementation(libs.kotlinx.serialization.json)
    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testImplementation(libs.kotlin.test)
}

tasks.test {
    useJUnitPlatform()
}

tasks.register<JavaExec>("generateSampleEvidence") {
    group = "droidproof"
    description = "Generates a deterministic checkout retry evidence bundle."
    classpath = sourceSets.main.get().runtimeClasspath
    mainClass.set("io.github.fredleonam.droidproof.evidence.SampleEvidenceKt")
    args(layout.buildDirectory.dir("droidproof-samples/proof-checkout-offline-retry").get().asFile.absolutePath)
}
