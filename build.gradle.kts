import org.gradle.api.publish.PublishingExtension
import org.gradle.api.publish.maven.MavenPublication

plugins {
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.kotlin.serialization) apply false
    alias(libs.plugins.ktlint) apply false
}

allprojects {
    group = "io.github.fredleonam.droidproof"
    version = "0.1.0-SNAPSHOT"

    repositories {
        mavenCentral()
    }
}

configure(
    listOf(
        project(":droidproof-model"),
        project(":droidproof-evidence"),
        project(":droidproof-device"),
        project(":droidproof-mock-server"),
        project(":droidproof-host"),
        project(":droidproof-report"),
    ),
) {
    apply(plugin = "maven-publish")
    plugins.withId("org.jetbrains.kotlin.jvm") {
        extensions.configure<PublishingExtension> {
            publications.create<MavenPublication>("maven") {
                from(components["java"])
            }
        }
    }
}
