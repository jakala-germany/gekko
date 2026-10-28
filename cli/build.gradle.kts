import org.jetbrains.kotlin.gradle.plugin.mpp.KotlinNativeTarget
import org.jetbrains.kotlin.konan.target.Family

plugins {
    id("gekko-format")
    kotlin("multiplatform")
    kotlin("plugin.serialization")
}

group = providers.gradleProperty("GROUP").get()

version = providers.gradleProperty("VERSION_NAME").get()

kotlin {
    jvm()
    macosArm64()
    linuxX64()

    targets.withType<KotlinNativeTarget>().configureEach {
        binaries.executable {
            baseName = "gekko"
            entryPoint = "com.jakala.gekko.cli.main"
        }
        // clikt and clikt-mordant both define Context.selfAndAncestors(); lld on Linux refuses the duplicate, the
        // macOS linker does not care
        if (konanTarget.family == Family.LINUX) {
            binaries.all { linkerOpts("--allow-multiple-definition") }
        }
    }

    sourceSets {
        all { languageSettings.optIn("kotlinx.cinterop.ExperimentalForeignApi") }
        commonMain.dependencies {
            implementation(project(":protocol"))
            implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.9.0")
            implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.11.0")
            implementation("io.ktor:ktor-client-core:3.6.0")
            implementation("io.ktor:ktor-client-cio:3.6.0")
            implementation("com.github.ajalt.clikt:clikt:5.1.0")
            implementation("com.charleskorn.kaml:kaml:0.104.0")
        }
        nativeMain.dependencies { implementation("com.squareup.okio:okio:3.18.2") }
        commonTest.dependencies {
            implementation(kotlin("test"))
            implementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.11.0")
        }
    }
}

tasks.register<JavaExec>("gekkoJvm") {
    group = "application"
    mainClass.set("com.jakala.gekko.cli.MainKt")
    val jvmMain = kotlin.jvm().compilations.getByName("main")
    classpath(jvmMain.output.allOutputs, jvmMain.runtimeDependencyFiles)
}
