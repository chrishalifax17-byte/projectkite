plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.projectkite.stemsplitter"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.projectkite.stemsplitter"
        minSdk = 29
        targetSdk = 35
        versionCode = 2
        versionName = "1.1"
        ndk { abiFilters += listOf("arm64-v8a", "armeabi-v7a", "x86_64") }
    }

    // CI writes the key from the CKSPLITTER_KEYSTORE secret so every build installs
    // as an update over the previous one. Without it, the debug key is used.
    val keystore = file("cksplitter.keystore")
    val keystorePassword = System.getenv("CKSPLITTER_KEYSTORE_PASSWORD")
    signingConfigs {
        if (keystore.exists() && keystorePassword != null) {
            create("sideload") {
                storeFile = keystore
                storePassword = keystorePassword
                keyAlias = "cksplitter"
                keyPassword = keystorePassword
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.findByName("sideload") ?: signingConfigs.getByName("debug")
        }
    }

    lint {
        // The CI build should not fail on lint warnings; run `./gradlew lint` to see them.
        checkReleaseBuilds = false
        abortOnError = false
    }

    androidResources {
        noCompress += "onnx"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

kotlin {
    compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) }
}

dependencies {
    implementation(project(":core"))
    implementation("com.microsoft.onnxruntime:onnxruntime-android:1.22.0")
}
