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
