pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
    plugins {
        id("com.android.application") version "8.7.3"
        id("org.jetbrains.kotlin.android") version "2.1.21"
        id("org.jetbrains.kotlin.jvm") version "2.1.21"
    }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        if (!providers.gradleProperty("coreOnly").isPresent) google()
        mavenCentral()
    }
}
rootProject.name = "StemSplitter"
include(":core")
// The Android app needs the Android SDK; `-PcoreOnly` lets the core module be
// built and tested on a plain JVM.
if (!providers.gradleProperty("coreOnly").isPresent) include(":app")
