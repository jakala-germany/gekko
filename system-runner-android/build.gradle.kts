plugins {
    id("com.android.application")
    id("gekko-format")
}

android {
    namespace = "com.jakala.gekko.runner"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.jakala.gekko.runner"
        minSdk = 24
        targetSdk = 37
        versionCode = 1
        versionName = "0.1.0"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    implementation(project(":transport-jvm"))
    implementation("androidx.test:runner:1.7.0")
    implementation("androidx.test.ext:junit:1.3.0")
    implementation("androidx.test.uiautomator:uiautomator:2.3.0")
    implementation("junit:junit:4.13.2")
}
