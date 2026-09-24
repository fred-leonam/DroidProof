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
        rootProject.layout.projectDirectory.file("samples/smoke-app/scenarios/compose-semantics-passing.json"),
        rootProject.layout.projectDirectory.file("samples/smoke-app/scenarios/compose-semantics-failing.json"),
    )
    systemProperty("droidproof.repositoryRoot", rootProject.layout.projectDirectory.asFile.absolutePath)
}

tasks.register<JavaExec>("runSmokeScenario") {
    group = "droidproof"
    description = "Runs one artifact-bound Android smoke scenario on an external serial, existing AVD, or provisioned owned AVD."
    classpath = sourceSets.main.get().runtimeClasspath
    mainClass.set("io.github.fredleonam.droidproof.host.RunSmokeScenarioKt")
    outputs.upToDateWhen { false }
    outputs.cacheIf { false }

    fun option(
        name: String,
        value: String,
    ) = args("--$name=$value")
    option("outputRoot", layout.buildDirectory.dir("droidproof-runs").get().asFile.absolutePath)
    for ((name, default) in mapOf(
        "apkPath" to "",
        "scenarioPath" to "",
        "deviceSerial" to "",
        "adbPath" to "",
        "replaceExisting" to "false",
        "signingPrivateKeyPath" to "",
        "signingPublicKeyPath" to "",
        "environmentPath" to "",
        "environmentMode" to "VERIFY_ONLY",
        "recoveryStateRoot" to layout.projectDirectory.dir(".droidproof-recovery").asFile.absolutePath,
        "avdName" to "",
        "emulatorPath" to "emulator",
        "emulatorPort" to "5554",
        "lifecycleStartupTimeoutMillis" to "120000",
        "lifecycleShutdownTimeoutMillis" to "30000",
        "provisioningPath" to "",
        "sdkRoot" to "",
        "provisioningStateRoot" to layout.projectDirectory.dir(".droidproof-provisioning").asFile.absolutePath,
        "avdManagerPath" to "avdmanager",
    )) {
        option(name, providers.gradleProperty("droidproof.$name").orElse(default).get())
    }
    option("version", project.version.toString())
    option("emulatorBackend", providers.gradleProperty("droidproof.emulatorBackend").orElse("legacy").get())
    option("androidCliPath", providers.gradleProperty("droidproof.androidCliPath").orElse("").get())
}

tasks.register<JavaExec>("probeAndroidCliCompatibility") {
    group = "droidproof"
    description = "Writes a bounded read-only Android CLI compatibility report."
    classpath = sourceSets.main.get().runtimeClasspath
    mainClass.set("io.github.fredleonam.droidproof.host.ProbeAndroidCliCompatibilityKt")
    val report = layout.buildDirectory.file("reports/android-cli-compatibility.json")
    outputs.file(report)
    outputs.upToDateWhen { false }
    outputs.cacheIf { false }
    args("--androidCliPath=${providers.gradleProperty("droidproof.androidCliPath").orElse("").get()}")
    args("--sdkRoot=${providers.gradleProperty("droidproof.sdkRoot").orElse("").get()}")
    args(
        "--timeoutMillis=" +
            providers.gradleProperty("droidproof.androidCliProbeTimeoutMillis").orElse("15000").get(),
    )
    args("--outputPath=${report.get().asFile.absolutePath}")
}

tasks.register<JavaExec>("recoverEmulatorEnvironment") {
    group = "droidproof"
    description = "Explicitly restores a durable interrupted emulator-environment transaction."
    classpath = sourceSets.main.get().runtimeClasspath
    mainClass.set("io.github.fredleonam.droidproof.host.RecoverEmulatorEnvironmentKt")
    outputs.upToDateWhen { false }
    outputs.cacheIf { false }
    args(providers.gradleProperty("droidproof.deviceSerial").orElse("").get())
    args(providers.gradleProperty("droidproof.adbPath").orElse("").get())
    args(
        providers.gradleProperty("droidproof.recoveryStateRoot")
            .orElse(layout.projectDirectory.dir(".droidproof-recovery").asFile.absolutePath)
            .get(),
    )
}
