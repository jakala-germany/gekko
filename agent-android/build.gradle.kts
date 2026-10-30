plugins {
    id("com.android.library")
    id("gekko-format")
}

android {
    namespace = "com.jakala.gekko.agent"
    compileSdk = 37

    defaultConfig { minSdk = 21 }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    api(project(":protocol"))
    implementation(project(":transport-jvm"))
    implementation(libs.androidx.startup.runtime)
    implementation(libs.kotlinx.serialization.json)
    compileOnly(libs.androidx.compose.ui)

    testImplementation(libs.kotlin.test.junit)
    testImplementation(libs.junit)
    testImplementation(libs.androidx.compose.ui)
}

mavenPublishing {
    coordinates(artifactId = "gekko-agent-android")
    pom {
        name.set("gekko Android agent")
        description.set("In-app agent that exposes the Compose and View UI tree to the gekko CLI")
    }
}
