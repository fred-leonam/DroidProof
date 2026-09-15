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
    implementation(project(":droidproof-mock-server"))
    implementation(libs.kotlinx.serialization.json)
    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testImplementation(libs.kotlin.test)
}

tasks.test {
    useJUnitPlatform()
    inputs.files(
        rootProject.layout.projectDirectory.file("samples/smoke-app/scenarios/interactive-passing.json"),
        rootProject.layout.projectDirectory.file("samples/smoke-app/scenarios/interactive-failing.json"),
        rootProject.layout.projectDirectory.file("samples/smoke-app/scenarios/network-passing.json"),
        rootProject.layout.projectDirectory.file("samples/smoke-app/scenarios/network-request-passing.json"),
        rootProject.layout.projectDirectory.file("samples/smoke-app/scenarios/network-request-failing.json"),
    )
    systemProperty("droidproof.repositoryRoot", rootProject.layout.projectDirectory.asFile.absolutePath)
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
        "signingPrivateKeyPath" to "",
        "signingPublicKeyPath" to "",
        "environmentPath" to "",
    )) {
        args(providers.gradleProperty("droidproof.$name").orElse(default).get())
    }
    args(project.version.toString())
}
