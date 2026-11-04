import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "io.github.foadsf.microscope"
    // AndroidX core 1.19 / Compose 1.12 require compiling against API 37. targetSdk is what the
    // phone acts on.
    compileSdk {
        version = release(37) { minorApiLevel = 0 }
    }
    buildToolsVersion = "37.0.0"

    defaultConfig {
        applicationId = "io.github.foadsf.microscope"
        // API 29: SessionConfiguration, isSessionConfigurationSupported and scoped MediaStore
        // writes without a storage permission.
        minSdk = 29
        targetSdk = 35
        versionCode = 4
        versionName = "0.3.1"
    }

    // Optional shared debug key: if `debug.keystore` exists next to settings.gradle.kts (it is
    // gitignored), every machine that has it signs debug builds identically.
    val sharedDebugKey = rootProject.file("debug.keystore")
    if (sharedDebugKey.exists()) {
        signingConfigs {
            getByName("debug") {
                storeFile = sharedDebugKey
                storePassword = "android"
                keyAlias = "androiddebugkey"
                keyPassword = "android"
            }
        }
    }

    // Release signing: point -Pmicroscope.signing=<file> (or a gitignored keystore.properties next
    // to settings.gradle.kts) at a properties file with storeFile, storePassword, keyAlias and
    // keyPassword. Without one, release builds stay unsigned, which is what CI and F-Droid want.
    val signingFile = providers.gradleProperty("microscope.signing").orNull?.let(::file)
        ?: rootProject.file("keystore.properties")
    if (signingFile.exists()) {
        val p = Properties().apply { signingFile.inputStream().use { load(it) } }
        signingConfigs {
            create("release") {
                storeFile = file(p.getProperty("storeFile"))
                storePassword = p.getProperty("storePassword")
                keyAlias = p.getProperty("keyAlias")
                keyPassword = p.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        release {
            if (signingFile.exists()) signingConfig = signingConfigs.getByName("release")
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"))
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
    }

    // No Google-encrypted dependency blob in the APK.
    dependenciesInfo {
        includeInApk = false
        includeInBundle = false
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.exifinterface)
    implementation(libs.kotlinx.coroutines.android)
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.foundation)
    implementation(libs.compose.material3)

    testImplementation(libs.junit)
}

tasks.withType<Test>().configureEach {
    // `./gradlew testDebugUnitTest -Pbench=1` also runs the speed benchmark.
    systemProperty("microscope.bench", providers.gradleProperty("bench").getOrElse(""))
    maxHeapSize = "3g"
    providers.gradleProperty("jfr").orNull?.let { jvmArgs("-XX:StartFlightRecording=filename=$it,settings=profile") }
}
