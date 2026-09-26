plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.ktlint)
    `java-gradle-plugin`
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
    implementation(project(":droidproof-evidence"))
    implementation(project(":droidproof-model"))
}

gradlePlugin {
    website.set("https://github.com/fred-leonam/DroidProof")
    vcsUrl.set("https://github.com/fred-leonam/DroidProof.git")
    plugins {
        create("droidProof") {
            id = "io.github.fredleonam.droidproof"
            implementationClass = "io.github.fredleonam.droidproof.gradle.DroidProofPlugin"
            displayName = "DroidProof"
            description = "Runs artifact-bound Android scenarios and generates verified evidence reports."
        }
    }
}

tasks.jar {
    manifest.attributes["Implementation-Version"] = project.version
}
