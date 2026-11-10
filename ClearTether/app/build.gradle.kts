import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

// Optional release signing, supplied via keystore.properties (never committed) or CI env vars.
val keystoreProps = Properties().apply {
    val f = rootProject.file("keystore.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}
fun secret(key: String): String? = keystoreProps.getProperty(key) ?: System.getenv(key)

val robolectricSdk: Configuration = configurations.create("robolectricSdk") { isTransitive = false }
val robolectricSdkDir = layout.buildDirectory.dir("robolectric-sdk")
val syncRobolectricSdk = tasks.register<Sync>("syncRobolectricSdk") {
    from(robolectricSdk)
    into(robolectricSdkDir)
}

android {
    namespace = "com.cleartether.app"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.cleartether.app"
        minSdk = 29
        targetSdk = 36
        versionCode = 1
        versionName = "1.0.0"

        val licenseKey = (project.findProperty("CLEARTETHER_PLAY_LICENSE_KEY") as String?).orEmpty()
        buildConfigField("String", "PLAY_LICENSE_KEY", "\"$licenseKey\"")
        buildConfigField("String", "PREMIUM_PRODUCT_ID", "\"cleartether_premium_lifetime\"")
    }

    signingConfigs {
        create("release") {
            val storePath = secret("CLEARTETHER_KEYSTORE")
            if (storePath != null) {
                storeFile = file(storePath)
                storePassword = secret("CLEARTETHER_KEYSTORE_PASSWORD")
                keyAlias = secret("CLEARTETHER_KEY_ALIAS")
                keyPassword = secret("CLEARTETHER_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            if (secret("CLEARTETHER_KEYSTORE") != null) signingConfig = signingConfigs.getByName("release")
        }
        debug {
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
        }
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    lint {
        lintConfig = file("lint.xml")
        warningsAsErrors = false
        abortOnError = true
    }

    testOptions {
        unitTests.isReturnDefaultValues = true
        unitTests.isIncludeAndroidResources = true
        unitTests.all { test ->
            // Screenshot tests (Robolectric + Roborazzi) only run when explicitly requested:
            //   ./gradlew :app:testDebugUnitTest -Pscreenshots --tests '*ScreenshotTest*'
            test.systemProperty("roborazzi.test.record", "true")
            test.systemProperty("ct.screenshots", project.hasProperty("screenshots").toString())
            test.systemProperty("ct.screenshot.dir", rootProject.file("docs/screenshots").absolutePath)
            test.maxHeapSize = "2g"
            // Robolectric's own downloader doesn't work behind every proxy, so Gradle resolves the
            // Android framework jar and Robolectric runs offline against it.
            if (project.hasProperty("screenshots")) test.dependsOn(syncRobolectricSdk) else test.exclude("**/ScreenshotTest*")
            test.systemProperty("robolectric.offline", "true")
            test.jvmArgs(
                "--add-exports=java.base/jdk.internal.access=ALL-UNNAMED",
                "--add-opens=java.base/java.io=ALL-UNNAMED",
                "--add-opens=java.base/jdk.internal.access=ALL-UNNAMED",
            )
            test.systemProperty("robolectric.dependency.dir", robolectricSdkDir.get().asFile.absolutePath)
        }
    }

    packaging {
        resources.excludes += setOf("/META-INF/{AL2.0,LGPL2.1}", "/META-INF/*.kotlin_module")
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.core.splashscreen)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.service)
    implementation(libs.androidx.datastore.preferences)
    // ActivityResult APIs need fragment >= 1.3; the code scanner pulls in an older one transitively.
    implementation(libs.androidx.fragment.ktx)

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.foundation)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons.extended)
    debugImplementation(libs.androidx.compose.ui.tooling)

    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.billing.ktx)
    implementation(libs.zxing.core)
    implementation(libs.play.services.code.scanner)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.robolectric)
    testImplementation(libs.roborazzi)
    testImplementation(libs.roborazzi.compose)
    testImplementation(libs.androidx.test.ext.junit)
    testImplementation(platform(libs.androidx.compose.bom))
    testImplementation(libs.androidx.compose.ui.test.junit4)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
    robolectricSdk(libs.robolectric.android.all)
}
