plugins {
    `kotlin-dsl`
    id("gekko-format")
    id("com.vanniktech.maven.publish") version "0.36.0"
}

group = providers.gradleProperty("GROUP").get()

version = providers.gradleProperty("VERSION_NAME").get()

dependencies {
    compileOnly("com.android.tools.build:gradle:9.4.0")
    testImplementation(kotlin("test"))
}

// The agent the plugin adds is the one of the same release: the version is written into the plugin at build time
val generatePluginVersion by tasks.registering {
    val pluginVersion = providers.gradleProperty("VERSION_NAME")
    val output = layout.buildDirectory.dir("generated/version")
    inputs.property("version", pluginVersion)
    outputs.dir(output)
    doLast {
        val file = output.get().file("com/jakala/gekko/gradle/PluginVersion.kt").asFile
        file.parentFile.mkdirs()
        file.writeText(
            "package com.jakala.gekko.gradle\n\n/** The release of this plugin. */\n" +
                "internal object PluginVersion {\n    const val NAME = \"${pluginVersion.get()}\"\n}\n"
        )
    }
}

sourceSets.main { kotlin.srcDir(generatePluginVersion.map { it.outputs.files.singleFile }) }

// Apps that apply the plugin run on JDK 17
java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

kotlin { compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) } }

tasks.test { useJUnitPlatform() }

gradlePlugin {
    plugins {
        create("gekko") {
            id = "io.github.jakala-germany.gekko"
            implementationClass = "com.jakala.gekko.gradle.GekkoPlugin"
        }
    }
}

mavenPublishing {
    coordinates(artifactId = "gekko-gradle-plugin")
    pom {
        name.set("gekko Gradle plugin")
        description.set("Adds the gekko in-app agent to the chosen build variants of an Android app")
    }
}
