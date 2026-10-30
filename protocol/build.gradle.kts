plugins {
    id("gekko-format")
    kotlin("multiplatform")
    kotlin("plugin.serialization")
}

kotlin {
    jvm()
    macosArm64()
    linuxX64()

    sourceSets {
        commonMain.dependencies { implementation(libs.kotlinx.serialization.json) }
        commonTest.dependencies { implementation(kotlin("test")) }
    }
}

mavenPublishing {
    coordinates(artifactId = "gekko-protocol")
    pom {
        name.set("gekko protocol")
        description.set("Command, response and UI-node types shared by the gekko CLI and agents")
    }
}

val generateVersion by tasks.registering {
    val version = providers.gradleProperty("VERSION_NAME")
    val output = layout.buildDirectory.dir("generated/version")
    inputs.property("version", version)
    outputs.dir(output)
    doLast {
        val file = output.get().file("com/jakala/gekko/protocol/GekkoVersion.kt").asFile
        file.parentFile.mkdirs()
        file.writeText(
            "package com.jakala.gekko.protocol\n\n/** The gekko release this build belongs to. */\n" +
                "object GekkoVersion {\n    const val NAME = \"${version.get()}\"\n}\n"
        )
    }
}

kotlin.sourceSets.commonMain { kotlin.srcDir(generateVersion.map { it.outputs.files.singleFile }) }
