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
    testImplementation(project(":droidproof-evidence"))
    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testImplementation(libs.kotlin.test)
}

tasks.test {
    useJUnitPlatform()
    systemProperty("child.classpath", sourceSets.test.get().runtimeClasspath.asPath)
}

tasks.register<JavaExec>("captureDeviceEvidence") {
    group = "droidproof"
    description = "Explicitly collects a display screenshot and observed metadata from an authorized test device."
    classpath = sourceSets.main.get().runtimeClasspath
    mainClass.set("io.github.fredleonam.droidproof.device.CaptureDeviceEvidenceKt")
    outputs.upToDateWhen { false }
    outputs.cacheIf { false }
    args(layout.buildDirectory.dir("droidproof-captures").get().asFile.absolutePath)
    for ((name, default) in mapOf(
        "adbPath" to "",
        "deviceSerial" to "",
        "includeLogcat" to "false",
        "pid" to "",
        "commandTimeoutMillis" to "15000",
        "textLimitBytes" to "65536",
        "screenshotLimitBytes" to "33554432",
        "logcatLimitBytes" to "1048576",
    )) {
        args(providers.gradleProperty("droidproof.$name").orElse(default).get())
    }
}
