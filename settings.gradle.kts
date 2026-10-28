rootProject.name = "gekko"

pluginManagement {
    includeBuild("build-logic")
    includeBuild("gradle-plugin")
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositories {
        google()
        mavenCentral()
    }
}

include(":protocol")

include(":transport-jvm")

include(":cli")

include(":agent-android")

include(":sample-android")

include(":system-runner-android")
