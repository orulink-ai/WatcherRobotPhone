plugins {
    kotlin("jvm")
    alias(libs.plugins.kotlin.serialization)
    application
    alias(libs.plugins.ktlint)
    alias(libs.plugins.detekt)
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

application {
    mainClass.set("com.danielealbano.androidremotecontrolmcp.benchmark.BenchmarkMainKt")
}

tasks.named<JavaExec>("run") {
    // Corpus A holds ~116k samples in memory plus ONNX Runtime buffers.
    maxHeapSize = "4g"
    // The CLI's default --cache-dir/--out are repo-root-relative (privacy-benchmark/.cache, matching
    // .gitignore); JavaExec would otherwise run from this module dir and nest them one level deeper.
    workingDir = rootProject.projectDir
}

ktlint {
    version.set("1.8.0")
}

configurations.matching { it.name.startsWith("ktlint") }.configureEach {
    resolutionStrategy {
        force(
            "ch.qos.logback:logback-core:1.5.34",
            "ch.qos.logback:logback-classic:1.5.34",
        )
    }
}

dependencies {
    implementation(project(":privacy"))
    implementation(libs.onnxruntime.jvm)
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.libphonenumber)

    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter.api)
    testImplementation(libs.junit.jupiter.params)
    testRuntimeOnly(libs.junit.jupiter.engine)
    testRuntimeOnly(libs.junit.platform.launcher)
    testImplementation(libs.kotlinx.coroutines.test)
}

tasks.test {
    useJUnitPlatform()
}
