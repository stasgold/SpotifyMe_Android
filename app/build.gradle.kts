import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
}

// Keys come from local.properties (git-ignored) or, in CI, environment variables. See README.md.
val localProps = Properties().apply {
    rootProject.file("local.properties").takeIf { it.exists() }?.inputStream()?.use { load(it) }
}
fun config(property: String, env: String): String =
    (localProps.getProperty(property) ?: System.getenv(env) ?: "").trim()

android {
    namespace = "com.spotifyme.app"
    compileSdk = 37

    defaultConfig {
        applicationId = config("app.applicationId", "APP_APPLICATION_ID").ifEmpty { "com.stasgold.spotifyme" }
        minSdk = 26
        targetSdk = 37
        // CI passes BUILD_NUMBER so every build gets a higher versionCode.
        versionCode = (project.findProperty("versionCode") as String? ?: System.getenv("BUILD_NUMBER") ?: "1").toInt()
        versionName = project.findProperty("versionName") as String? ?: "1.0"

        buildConfigField("String", "SPOTIFY_CLIENT_ID", "\"${config("spotify.clientId", "SPOTIFY_CLIENT_ID")}\"")
        buildConfigField("String", "GETSONGBPM_API_KEY", "\"${config("getsongbpm.apiKey", "GETSONGBPM_API_KEY")}\"")
        // Must match the Redirect URI registered in the Spotify dashboard (same as the iOS app).
        buildConfigField("String", "REDIRECT_URI", "\"spotifyme://callback\"")
        manifestPlaceholders["redirectScheme"] = "spotifyme"
        manifestPlaceholders["redirectHost"] = "callback"
    }

    signingConfigs {
        // Release builds are signed when a keystore is configured: locally from keystore.properties
        // (git-ignored), in CI from environment variables. Otherwise the release APK is unsigned.
        val keystoreProps = Properties().apply {
            rootProject.file("keystore.properties").takeIf { it.exists() }?.inputStream()?.use { load(it) }
        }
        val keystore = keystoreProps.getProperty("storeFile") ?: System.getenv("ANDROID_KEYSTORE_PATH")
        if (keystore != null) {
            create("release") {
                storeFile = rootProject.file(keystore)
                storePassword = keystoreProps.getProperty("storePassword") ?: System.getenv("ANDROID_KEYSTORE_PASSWORD")
                keyAlias = keystoreProps.getProperty("keyAlias") ?: System.getenv("ANDROID_KEY_ALIAS")
                keyPassword = keystoreProps.getProperty("keyPassword") ?: System.getenv("ANDROID_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.findByName("release")
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
}

kotlin {
    compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) }
}

dependencies {
    implementation(project(":core"))
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.browser)
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.graphics)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.compose.material3)
    implementation(libs.compose.material.icons)
    debugImplementation(libs.compose.ui.tooling)

    testImplementation(libs.junit)
}
