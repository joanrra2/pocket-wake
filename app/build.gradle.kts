plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.roborazzi)
}

// CI gives every build a higher versionCode, and tag builds (v1.2.3) their version name.
val ciRunNumber: Int? = providers.environmentVariable("GITHUB_RUN_NUMBER").orNull?.toIntOrNull()
val ciTag: String? = providers.environmentVariable("GITHUB_REF_TYPE").orNull
    ?.takeIf { it == "tag" }
    ?.let { providers.environmentVariable("GITHUB_REF_NAME").orNull?.removePrefix("v") }

// Release signing comes only from environment variables (set by CI from repository
// secrets, or by you locally). Nothing secret is stored in the repository.
val releaseKeystore: String? = providers.environmentVariable("POCKETWAKE_KEYSTORE_FILE").orNull

android {
    namespace = "io.github.joanrra2.pocketwake"
    compileSdk {
        version = release(37)
    }

    defaultConfig {
        applicationId = "io.github.joanrra2.pocketwake"
        minSdk = 26
        targetSdk = 36
        versionCode = ciRunNumber ?: 1
        versionName = ciTag ?: "0.3.1-beta"
    }

    signingConfigs {
        if (!releaseKeystore.isNullOrBlank()) {
            create("release") {
                storeFile = file(releaseKeystore)
                storePassword = providers.environmentVariable("POCKETWAKE_KEYSTORE_PASSWORD").orNull
                keyAlias = providers.environmentVariable("POCKETWAKE_KEY_ALIAS").orNull
                keyPassword = providers.environmentVariable("POCKETWAKE_KEY_PASSWORD").orNull
            }
        }
    }

    buildTypes {
        debug {
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
            manifestPlaceholders["appLabel"] = "Pocket Wake (debug)"
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.findByName("release")
            manifestPlaceholders["appLabel"] = "Pocket Wake"
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
        // Robolectric renders the Compose screens for the README screenshots.
        unitTests.isIncludeAndroidResources = true
    }

    packaging {
        resources {
            excludes += setOf(
                "META-INF/INDEX.LIST",
                "META-INF/io.netty.versions.properties",
                "META-INF/native-image/**",
            )
        }
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.process)
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.compose.material3)
    implementation(libs.compose.material.icons.core)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.hivemq.mqtt.client) {
        // Linux-only native transport; Android uses the NIO transport.
        exclude(group = "io.netty", module = "netty-transport-native-epoll")
    }
    // Board QR scanning: decoded on the phone, no Google services or network.
    implementation(libs.zxing.core)
    implementation(libs.camerax.camera2)
    implementation(libs.camerax.lifecycle)
    implementation(libs.camerax.view)
    debugImplementation(libs.compose.ui.tooling)
    debugImplementation(libs.compose.ui.test.manifest)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(platform(libs.compose.bom))
    testImplementation(libs.compose.ui.test.junit4)
    testImplementation(libs.robolectric)
    testImplementation(libs.roborazzi)
    testImplementation(libs.roborazzi.compose)
}

// Screenshot tests need Robolectric (a large download); run them only when recording
// them with ./gradlew recordRoborazziDebug.
val recordingScreenshots = gradle.startParameter.taskNames.any { "Roborazzi" in it }
tasks.withType<Test>().configureEach {
    if (!recordingScreenshots) exclude("**/ScreenshotTest*")
}

roborazzi {
    outputDir.set(rootProject.layout.projectDirectory.dir("docs/screenshots"))
}
