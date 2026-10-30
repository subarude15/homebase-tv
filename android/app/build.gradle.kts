plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
}

android {
    namespace = "com.homebase.tv"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.homebase.tv"
        minSdk = 28
        targetSdk = 36
        versionCode = 1
        versionName = "0.1.0"
    }

    // CI only (release.yml): signs in Gradle when HOMEBASE_KEYSTORE_FILE is set. Without it the release
    // APK stays unsigned for sign-release.ps1. This path has no rotation lineage.
    val ciKeystore = providers.environmentVariable("HOMEBASE_KEYSTORE_FILE").orNull
    if (ciKeystore != null) {
        signingConfigs {
            create("ciRelease") {
                storeFile = file(ciKeystore)
                storePassword = providers.environmentVariable("HOMEBASE_KEYSTORE_PASSWORD").get()
                keyAlias = providers.environmentVariable("HOMEBASE_KEY_ALIAS").get()
                keyPassword = providers.environmentVariable("HOMEBASE_KEY_PASSWORD").get()
                enableV1Signing = false
                enableV2Signing = false
                enableV3Signing = true
            }
        }
    }

    buildTypes {
        // Unsigned locally: sign-release.ps1 signs with the release key and its rotation lineage.
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"))
            if (ciKeystore != null) signingConfig = signingConfigs.getByName("ciRelease")
        }
    }

    buildFeatures {
        compose = true
    }
}

dependencies {
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.foundation)
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.tooling.preview)
    debugImplementation(libs.compose.ui.tooling)
    implementation(libs.tv.material)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.retrofit)
    implementation(libs.retrofit.kotlinx.serialization)
    implementation(libs.okhttp)
    implementation(libs.coil.compose)
    implementation(libs.coil.network.okhttp)

    testImplementation(libs.junit)
}
