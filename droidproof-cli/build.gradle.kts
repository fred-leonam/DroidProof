plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.ktlint)
    application
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
    implementation(project(":droidproof-report"))
    implementation(project(":droidproof-device"))
    implementation(project(":droidproof-evidence"))
    implementation(project(":droidproof-model"))
}

application {
    applicationName = "droidproof"
    mainClass.set("io.github.fredleonam.droidproof.cli.DroidProofCliKt")
}

tasks.jar {
    manifest.attributes["Implementation-Version"] = project.version
}

publishing {
    publications {
        create<MavenPublication>("cli") {
            from(components["java"])
            artifactId = "droidproof-cli"
            pom {
                name.set("DroidProof CLI")
                description.set("Command-line interface for DroidProof Android evidence workflows.")
                url.set("https://github.com/fred-leonam/DroidProof")
            }
        }
    }
}
