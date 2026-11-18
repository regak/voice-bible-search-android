import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

// API key lives in local.properties (gitignored, never committed) and is
// baked into a BuildConfig field at compile time — never hardcoded in
// source, never logged. Same pattern used by the sibling
// voice-conversation-app-android / visual-impaired-assistant-android projects.
val localProperties = Properties().apply {
    val file = rootProject.file("local.properties")
    if (file.exists()) {
        file.inputStream().use { load(it) }
    }
}
val geminiApiKey: String = localProperties.getProperty("GEMINI_API_KEY", "")

android {
    namespace = "sw.bible.voicesearch"
    compileSdk = 34

    defaultConfig {
        applicationId = "sw.bible.voicesearch"
        minSdk = 24
        targetSdk = 34
        versionCode = 10
        versionName = "0.1.10"

        buildConfigField("String", "GEMINI_API_KEY", "\"$geminiApiKey\"")

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    // Prebuilt SQLite Bible database is bundled as a raw asset (not
    // compressed further by AAPT) so it can be streamed/copied byte-for-byte
    // to app storage on first launch without corruption.
    androidResources {
        noCompress += "db"
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.6")
    implementation("androidx.activity:activity-compose:1.9.2")
    implementation(platform("androidx.compose:compose-bom:2024.09.03"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.compose.foundation:foundation")

    // OkHttp — raw WebSocket client to Gemini Live's BidiGenerateContent
    // endpoint. Same dependency/reasoning as voice-conversation-app-android.
    implementation("com.squareup.okhttp3:okhttp:4.12.0")

    // Coroutines — Channel-based audio capture/playback pipeline.
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")

    testImplementation("junit:junit:4.13.2")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.6.1")
}
