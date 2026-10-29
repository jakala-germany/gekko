import com.ncorti.ktfmt.gradle.tasks.KtfmtCheckTask
import com.ncorti.ktfmt.gradle.tasks.KtfmtFormatTask

plugins {
    kotlin("jvm") version "2.4.20" apply false
    kotlin("multiplatform") version "2.4.20" apply false
    kotlin("plugin.serialization") version "2.4.21" apply false
    kotlin("plugin.compose") version "2.4.20" apply false
    id("com.android.library") version "9.4.0" apply false
    id("com.android.application") version "9.5.0" apply false
    id("com.vanniktech.maven.publish") version "0.36.0" apply false
    id("gekko-format")
}

val publishedModules = setOf("protocol", "transport-jvm", "agent-android")

subprojects {
    group = providers.gradleProperty("GROUP").get()
    version = providers.gradleProperty("VERSION_NAME").get()

    if (name in publishedModules) {
        apply(plugin = "com.vanniktech.maven.publish")
    }
}

// build-logic cannot apply its own convention plugin, so the root build formats and checks its files with the same
// settings
val buildLogicSources =
    fileTree("build-logic") {
        include("**/*.kt", "**/*.kts")
        exclude("**/build/**", "**/.gradle/**")
    }

val ktfmtCheckBuildLogic by tasks.registering(KtfmtCheckTask::class) { source = buildLogicSources }

val ktfmtFormatBuildLogic by tasks.registering(KtfmtFormatTask::class) { source = buildLogicSources }

tasks.named("ktfmtCheck") { dependsOn(ktfmtCheckBuildLogic) }

tasks.named("ktfmtFormat") { dependsOn(ktfmtFormatBuildLogic) }
