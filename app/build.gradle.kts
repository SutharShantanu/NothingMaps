plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "com.shantanu.nmaps"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.shantanu.nmaps"
        minSdk = 26
        targetSdk = 36
        // CI passes -PbuildNumber=<run number>; the in-app updater compares it against the latest release.
        val buildNumber = (project.findProperty("buildNumber") as String?)?.toIntOrNull() ?: 0
        versionCode = maxOf(1, buildNumber)
        versionName = if (buildNumber > 0) "1.0.$buildNumber" else "1.0.0"
        buildConfigField("int", "BUILD_NUMBER", "$buildNumber")

        // Map problem reports go to OpenStreetMap; build with -PosmApi=https://master.apis.dev.openstreetmap.org to test against the sandbox.
        buildConfigField("String", "OSM_API", "\"${project.findProperty("osmApi") ?: "https://api.openstreetmap.org"}\"")
    }

    // One key for every build, so in-app updates install over what's already there.
    // CI gets it from secrets; locally the usual ~/.android/debug.keystore is that key.
    val keystore = System.getenv("NMAPS_KEYSTORE")
    if (!keystore.isNullOrBlank()) {
        signingConfigs.getByName("debug") {
            storeFile = file(keystore)
            storePassword = System.getenv("NMAPS_KEYSTORE_PASSWORD") ?: "android"
            keyAlias = System.getenv("NMAPS_KEY_ALIAS") ?: "androiddebugkey"
            keyPassword = System.getenv("NMAPS_KEY_PASSWORD") ?: "android"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("debug")
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

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.activity.compose)

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.foundation)
    debugImplementation(libs.androidx.compose.ui.tooling)

    implementation(libs.maplibre.android)
    implementation(libs.androidx.health.connect)
    implementation(libs.androidx.exifinterface)
    implementation(libs.kotlinx.coroutines.android)

    testImplementation(libs.junit)
    // Android's org.json is a stub on the JVM; tests use the real implementation.
    testImplementation(libs.org.json)
}
