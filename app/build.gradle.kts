plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "dev.reclaimed.player"
    compileSdk = 36

    defaultConfig {
        applicationId = "dev.reclaimed.player"
        minSdk = 29
        targetSdk = 36
        versionCode = 1
        versionName = "0.1.0"
    }

    buildFeatures {
        compose = true
    }

    providers.gradleProperty("reclaimedSigningFile").orNull?.let { signingFile ->
        signingConfigs.create("reclaimedDebug") {
            storeFile = file(signingFile)
            storePassword = providers.gradleProperty("reclaimedSigningPassword")
                .orElse("android")
                .get()
            keyAlias = providers.gradleProperty("reclaimedSigningAlias")
                .orElse("reclaimeddebug")
                .get()
            keyPassword = providers.gradleProperty("reclaimedSigningPassword")
                .orElse("android")
                .get()
        }
        buildTypes.getByName("debug").signingConfig = signingConfigs.getByName("reclaimedDebug")
    }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2026.06.00")

    implementation(composeBom)
    implementation("androidx.activity:activity-compose:1.13.0")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.media3:media3-exoplayer:1.10.0")
    implementation("androidx.media3:media3-session:1.10.0")
    implementation("androidx.work:work-runtime:2.11.1")
    implementation("com.journeyapps:zxing-android-embedded:4.3.0")

    debugImplementation("androidx.compose.ui:ui-tooling")
    testImplementation(kotlin("test-junit"))
    testImplementation("org.json:json:20250517")
}
