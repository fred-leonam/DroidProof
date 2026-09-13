import org.gradle.process.CommandLineArgumentProvider

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
    implementation(libs.kotlinx.serialization.json)
    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testImplementation(libs.kotlin.test)
}

tasks.test {
    useJUnitPlatform()
}

tasks.register<JavaExec>("generateEvidenceReport") {
    group = "droidproof"
    description = "Generates a static HTML report from an existing evidence bundle."
    classpath = sourceSets.main.get().runtimeClasspath
    mainClass.set("io.github.fredleonam.droidproof.report.GenerateEvidenceReportKt")
    outputs.upToDateWhen { false }
    outputs.cacheIf { false }

    val bundlePath = providers.gradleProperty("droidproof.bundlePath").orElse("")
    val reportPath =
        providers.gradleProperty("droidproof.reportPath").orElse(
            layout.buildDirectory.file("reports/droidproof/evidence-report.html").map { it.asFile.absolutePath },
        )
    argumentProviders.add(CommandLineArgumentProvider { listOf(bundlePath.get(), reportPath.get()) })
}
