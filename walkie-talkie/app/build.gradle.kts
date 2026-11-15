plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "app.pttradio"
    compileSdk = 36

    defaultConfig {
        applicationId = "app.pttradio"
        minSdk = 26
        // Google Play requires new apps to target Android 16 (API 36)
        targetSdk = 36
        // Every CI build gets a higher number so a newer APK always installs over an older one.
        versionCode = (System.getenv("GITHUB_RUN_NUMBER") ?: "1").toInt()
        versionName = "1.0." + (System.getenv("GITHUB_RUN_NUMBER") ?: "0")
        buildConfigField("String", "LOGIN_URL", "\"${(project.findProperty("loginUrl") ?: "").toString().trim()}\"")
        buildConfigField("String", "LOGIN_KEY", "\"${(project.findProperty("loginKey") ?: "").toString().trim()}\"")
    }
    signingConfigs {
        // Fixed key kept in the repo so every build has the same signature and installs as an update.
        // It only identifies side-loaded builds of this app; use a private key for any store release.
        create("sideload") {
            storeFile = file("sideload.keystore")
            storePassword = "pttradio"
            keyAlias = "pttradio"
            keyPassword = "pttradio"
        }
    }
    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("sideload")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    buildFeatures { compose = true; buildConfig = true }
    packaging { resources.excludes += setOf("META-INF/*.kotlin_module", "META-INF/DEPENDENCIES", "META-INF/LICENSE*", "META-INF/NOTICE*") }
    testOptions { unitTests.isReturnDefaultValues = true }
}

dependencies {
    implementation(platform("androidx.compose:compose-bom:2024.09.02"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-core")
    implementation("androidx.activity:activity-compose:1.9.2")
    implementation("androidx.core:core-ktx:1.13.1")
    // Play services pulls in an old fragment library; the activity-result APIs need a current one.
    implementation("androidx.fragment:fragment:1.8.3")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
    implementation("org.eclipse.paho:org.eclipse.paho.client.mqttv3:1.2.5")
    implementation("com.google.android.gms:play-services-nearby:19.3.0")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20240303")
}
