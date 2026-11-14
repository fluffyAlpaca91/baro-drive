import org.jetbrains.kotlin.gradle.dsl.JvmTarget

// Pure Kotlin/JVM: no Android dependencies allowed in this module.
// Step-driven (the app's SimSignalSource runs the clock), so no coroutines here.
// Main code must not depend on :core:engine (the simulator never judges). Tests plug the engine in through SessionHook.
plugins {
    `java-library`
    alias(libs.plugins.kotlin.jvm)
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

kotlin {
    compilerOptions {
        jvmTarget = JvmTarget.JVM_17
        // See core/model/build.gradle.kts.
        freeCompilerArgs.add("-Xjdk-release=17")
    }
}

dependencies {
    api(project(":core:model"))

    testImplementation(libs.junit)
    // Integration test only (EngineHookIntegrationTest): main code must never see the engine.
    testImplementation(project(":core:engine"))
}
