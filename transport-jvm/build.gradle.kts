plugins {
    kotlin("jvm")
    id("gekko-format")
}

kotlin { compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) } }

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

dependencies {
    api(project(":protocol"))
    api(libs.kotlinx.serialization.json)
    testImplementation(kotlin("test"))
}

mavenPublishing {
    coordinates(artifactId = "gekko-transport-jvm")
    pom {
        name.set("gekko JVM transport")
        description.set("Minimal HTTP command server shared by the gekko Android agent and system runner")
    }
}
