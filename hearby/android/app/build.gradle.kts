import java.util.Properties
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

// Release signing is read from android/keystore.properties (git-ignored):
//   storeFile=/absolute/or/relative/path/to/upload-keystore.jks
//   storePassword=...
//   keyAlias=...
//   keyPassword=...
val keystoreProps = Properties().apply {
    val f = rootProject.file("keystore.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}
val hasReleaseKey = keystoreProps.getProperty("storeFile") != null

android {
    namespace = "io.github.ugoyel.hearby"
    compileSdk = 36

    defaultConfig {
        applicationId = "io.github.ugoyel.hearby"
        minSdk = 29          // AudioPlaybackCapture + MediaCodec Opus encoder both arrived in Android 10
        targetSdk = 36
        versionCode = 2
        versionName = "1.1.0"
    }

    signingConfigs {
        if (hasReleaseKey) {
            create("release") {
                storeFile = rootProject.file(keystoreProps.getProperty("storeFile"))
                storePassword = keystoreProps.getProperty("storePassword")
                keyAlias = keystoreProps.getProperty("keyAlias")
                keyPassword = keystoreProps.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            if (hasReleaseKey) signingConfig = signingConfigs.getByName("release")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
    }

    testOptions {
        unitTests {
            isReturnDefaultValues = true
            // Robolectric needs the merged resources to render real screens (see ScreenshotTest).
            isIncludeAndroidResources = true
            all {
                // Write the rendered screens to app/build/outputs/roborazzi/ on every test run.
                it.systemProperty("roborazzi.test.record", "true")
                it.maxHeapSize = "2g"
            }
        }
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

dependencies {
    implementation(platform("androidx.compose:compose-bom:2025.10.01"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.activity:activity-compose:1.11.0")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.9.4")
    implementation("androidx.core:core-ktx:1.17.0")
    debugImplementation("androidx.compose.ui:ui-tooling")

    testImplementation("junit:junit:4.13.2")
    // Screens are rendered on the JVM (no emulator) so the design can be reviewed as PNGs.
    testImplementation("org.robolectric:robolectric:4.16.1")
    testImplementation("io.github.takahirom.roborazzi:roborazzi:1.52.0")
    testImplementation("io.github.takahirom.roborazzi:roborazzi-compose:1.52.0")
    testImplementation(platform("androidx.compose:compose-bom:2025.10.01"))
    testImplementation("androidx.compose.ui:ui-test-junit4")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
}
