plugins {
    id("org.jetbrains.kotlin.jvm")
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

kotlin {
    compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) }
}

dependencies {
    testImplementation(kotlin("test"))
    testImplementation("com.microsoft.onnxruntime:onnxruntime:1.22.0")
}

tasks.test {
    useJUnitPlatform()
    maxHeapSize = "4g"
    // Directory produced by `tools/export_onnx.py --random --test-vectors`.
    providers.gradleProperty("testVectors").orNull?.let { systemProperty("testVectors", it) }
    testLogging { events("passed", "skipped", "failed"); showStandardStreams = true }
}
