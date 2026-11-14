// AGP 9 compiles Kotlin itself (built-in Kotlin), so there is no kotlin-android plugin here.
plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
    alias(libs.plugins.hilt)
    alias(libs.plugins.room)
}

// Demo tools (demo panel + simulator data source). On by default: the contest APK and the 12/3 demo need them,
// because the emulator cannot drive. `-PdemoTools=false` builds the store/review app without them.
// Read through `providers`, so the value is a tracked configuration-cache input.
val demoTools: Boolean = providers.gradleProperty("demoTools").map { it.toBooleanStrict() }.getOrElse(true)

// Release signing key. The keystore and its passwords live outside git, in ~/.gradle/gradle.properties
// (baroReleaseStoreFile / baroReleaseStorePassword / baroReleaseKeyAlias / baroReleaseKeyPassword).
// Without them the release build falls back to the debug key, so machines without the key can still build it.
val releaseStoreFile: String? = providers.gradleProperty("baroReleaseStoreFile").orNull
if (releaseStoreFile == null) logger.warn("baroReleaseStoreFile not set: release APK is signed with the debug key")

android {
    namespace = "com.barodrive.app"
    // 37 because Compose 1.12 / core 1.19 / lifecycle-compose 2.11 / navigation-compose 2.10
    // declare minCompileSdk=37 in their AAR metadata.
    compileSdk = 37

    defaultConfig {
        applicationId = "com.barodrive.app"
        minSdk = 28 // Pleos requirement (signing key rotation)
        targetSdk = 35 // Pleos samples; the head unit emulator is API 34
        versionCode = 1
        versionName = "0.1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        buildConfigField("boolean", "DEMO_TOOLS", demoTools.toString())
    }

    signingConfigs {
        if (releaseStoreFile != null) {
            create("release") {
                storeFile = file(releaseStoreFile)
                storePassword = providers.gradleProperty("baroReleaseStorePassword").get()
                keyAlias = providers.gradleProperty("baroReleaseKeyAlias").get()
                keyPassword = providers.gradleProperty("baroReleaseKeyPassword").get()
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.findByName("release") ?: signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    // CarPropertyManager, CarUxRestrictionsManager (platforms/android-XX/optional/android.car.jar)
    useLibrary("android.car")
}

room {
    // Sets room.schemaLocation for KSP with proper task inputs/outputs. Commit app/schemas/.
    schemaDirectory("$projectDir/schemas")
}

dependencies {
    implementation(project(":core:model"))
    implementation(project(":core:engine"))
    implementation(project(":core:sim"))

    implementation(libs.pleos.vehicle)
    implementation(libs.pleos.navihelper)
    implementation(libs.pleos.tts)
    // The Vehicle SDK pulls kotlin-reflect 1.9.25; keep every org.jetbrains.kotlin artifact on our Kotlin version.
    implementation(platform(libs.kotlin.bom))

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.androidx.datastore.preferences)

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    debugImplementation(libs.androidx.compose.ui.tooling)
    debugImplementation(libs.androidx.compose.ui.test.manifest)

    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)
    implementation(libs.androidx.hilt.navigation.compose)
    implementation(libs.androidx.hilt.lifecycle.viewmodel.compose)

    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)

    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.serialization.json)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.turbine)

    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.androidx.test.runner)
}
