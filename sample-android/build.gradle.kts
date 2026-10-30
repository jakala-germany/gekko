plugins {
    id("gekko-format")
    id("com.android.application")
    kotlin("plugin.compose")
    id("io.github.jakala-germany.gekko")
}

gekko { agent.set(project(":agent-android")) }

android {
    namespace = "com.jakala.gekko.sample"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.jakala.gekko.sample"
        minSdk = 23
        targetSdk = 37
        versionCode = 1
        versionName = "1.0"
    }

    buildFeatures { compose = true }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    implementation(platform(libs.androidx.compose.bom))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")
    implementation(libs.androidx.activity.compose)
}
