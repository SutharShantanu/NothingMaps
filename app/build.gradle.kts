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
        versionCode = 1
        versionName = "1.0.0"

        // Map problem reports go to OpenStreetMap; build with -PosmApi=https://master.apis.dev.openstreetmap.org to test against the sandbox.
        buildConfigField("String", "OSM_API", "\"${project.findProperty("osmApi") ?: "https://api.openstreetmap.org"}\"")
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
