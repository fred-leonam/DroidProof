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
    implementation(project(":droidproof-evidence"))
    implementation(project(":droidproof-device"))
    implementation(libs.kotlinx.serialization.json)
    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testImplementation(libs.kotlin.test)
}

tasks.test {
    useJUnitPlatform()
}

tasks.register<JavaExec>("runSmokeScenario") {
    group = "droidproof"
    description = "Runs one explicit artifact-bound Android smoke scenario."
    classpath = sourceSets.main.get().runtimeClasspath
    mainClass.set("io.github.fredleonam.droidproof.host.RunSmokeScenarioKt")
    outputs.upToDateWhen { false }
    outputs.cacheIf { false }
    args(layout.buildDirectory.dir("droidproof-runs").get().asFile.absolutePath)
    for ((name, default) in mapOf(
        "apkPath" to "",
        "scenarioPath" to "",
        "deviceSerial" to "",
        "adbPath" to "",
        "replaceExisting" to "false",
    )) {
        args(providers.gradleProperty("droidproof.$name").orElse(default).get())
    }
    args(project.version.toString())
}
