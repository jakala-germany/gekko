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
            implementation(libs.kotlinx.serialization.json)
            implementation(libs.kotlinx.coroutines.core)
            implementation(libs.ktor.client.core)
            implementation(libs.ktor.client.cio)
            implementation(libs.clikt)
            implementation(libs.kaml)
        }
        nativeMain.dependencies { implementation(libs.okio) }
        commonTest.dependencies {
            implementation(kotlin("test"))
            implementation(libs.kotlinx.coroutines.test)
        }
    }
}

tasks.register<JavaExec>("gekkoJvm") {
    group = "application"
    mainClass.set("com.jakala.gekko.cli.MainKt")
    val jvmMain = kotlin.jvm().compilations.getByName("main")
    classpath(jvmMain.output.allOutputs, jvmMain.runtimeDependencyFiles)
}
