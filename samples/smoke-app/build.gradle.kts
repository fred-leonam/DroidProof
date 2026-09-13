plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

val keystorePath = providers.gradleProperty("droidproof.sample.keystore").orNull
val keyAliasValue = providers.gradleProperty("droidproof.sample.keyAlias").orNull
val storePasswordValue = providers.environmentVariable("DROIDPROOF_SAMPLE_STORE_PASSWORD").orNull
val keyPasswordValue = providers.environmentVariable("DROIDPROOF_SAMPLE_KEY_PASSWORD").orNull
val localSigningConfigured =
    listOf(keystorePath, keyAliasValue, storePasswordValue, keyPasswordValue).all { !it.isNullOrBlank() }

android {
    namespace = "io.github.fredleonam.droidproof.smokeapp"
    compileSdk = 35

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
        allWarningsAsErrors = true
    }

    defaultConfig {
        applicationId = "io.github.fredleonam.droidproof.smokeapp"
        minSdk = 23
        targetSdk = 35
        versionCode = 1
        versionName = "1.0"
    }

    signingConfigs {
        if (localSigningConfigured) {
            create("localDemo") {
                storeFile = file(requireNotNull(keystorePath))
                storePassword = requireNotNull(storePasswordValue)
                keyAlias = requireNotNull(keyAliasValue)
                keyPassword = requireNotNull(keyPasswordValue)
            }
        }
    }

    buildTypes {
        release {
            isDebuggable = false
            isMinifyEnabled = false
            if (localSigningConfigured) signingConfig = signingConfigs.getByName("localDemo")
        }
    }
}
