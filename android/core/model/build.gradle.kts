import org.jetbrains.kotlin.gradle.dsl.JvmTarget

// Pure Kotlin/JVM: no Android dependencies allowed in this module.
plugins {
    `java-library`
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

kotlin {
    compilerOptions {
        jvmTarget = JvmTarget.JVM_17
        // Compile against the JDK 17 API even though Gradle runs on JDK 21, so JDK 21-only
        // members (e.g. List.removeFirst) can't leak into code that later runs on Android.
        freeCompilerArgs.add("-Xjdk-release=17")
    }
}

dependencies {
    // @Serializable model types expose kotlinx.serialization in this module's API.
    api(libs.kotlinx.serialization.json)

    testImplementation(libs.junit)
}
