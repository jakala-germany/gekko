import com.ncorti.ktfmt.gradle.tasks.KtfmtCheckTask
import com.ncorti.ktfmt.gradle.tasks.KtfmtFormatTask

plugins {
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.kotlin.multiplatform) apply false
    alias(libs.plugins.kotlin.serialization) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.vanniktech.maven.publish) apply false
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
