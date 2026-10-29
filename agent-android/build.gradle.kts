plugins {
    id("com.android.library")
    id("gekko-format")
}

android {
    namespace = "com.jakala.gekko.agent"
    compileSdk = 37

    defaultConfig { minSdk = 26 }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    api(project(":protocol"))
    implementation(project(":transport-jvm"))
    implementation("androidx.startup:startup-runtime:1.2.0")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.9.0")
    compileOnly("androidx.compose.ui:ui:1.11.4")

    testImplementation("org.jetbrains.kotlin:kotlin-test-junit:2.4.21")
    testImplementation("junit:junit:4.13.2")
    testImplementation("androidx.compose.ui:ui:1.11.4")
}

mavenPublishing {
    coordinates(artifactId = "gekko-agent-android")
    pom {
        name.set("gekko Android agent")
        description.set("In-app agent that exposes the Compose and View UI tree to the gekko CLI")
    }
}
