import org.gradle.api.attributes.Usage
import org.gradle.api.attributes.java.TargetJvmEnvironment

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
    // The apps bring their own OkHttp (3.12 up to 5.x); the agent only uses calls that all of them have. The agent is
    // compiled against this one, so what the other versions lack shows up in testOkHttpVersions (see below)
    compileOnly(libs.okhttp)

    testImplementation(libs.kotlin.test.junit)
    testImplementation(libs.junit)
    testImplementation(libs.androidx.compose.ui)
    testImplementation(libs.okhttp)
    testImplementation(libs.asm)
}

// The OkHttp of an app is not the one the agent is compiled against, and the compiler cannot tell which calls an older
// or a newer OkHttp lacks. So the tests that touch OkHttp run again against the last patch of every minor since 3.12,
// each with the okio that OkHttp brings. The versions are written out here and not in the version catalog on purpose:
// Dependabot must leave the old ones alone.
val okHttpVersions =
    listOf(
            "3.12.13",
            "3.13.1",
            "3.14.9",
            "4.0.1",
            "4.1.1",
            "4.2.2",
            "4.3.1",
            "4.4.1",
            "4.5.0",
            "4.6.0",
            "4.7.2",
            "4.8.1",
            "4.9.3",
            "4.10.0",
            "4.11.0",
            "4.12.1",
            "5.0.0",
            "5.1.0",
            "5.2.3",
            "5.3.2",
            "5.4.0",
            "5.5.1",
            libs.versions.okhttp.get(),
        )
        .distinct()

val okHttpTests = okHttpVersions.map { version ->
    val id = version.replace('.', '_')
    val okHttp =
        configurations.create("okHttp$id") {
            isCanBeConsumed = false
            isCanBeResolved = true
            attributes {
                attribute(Usage.USAGE_ATTRIBUTE, objects.named(Usage.JAVA_RUNTIME))
                attribute(
                    TargetJvmEnvironment.TARGET_JVM_ENVIRONMENT_ATTRIBUTE,
                    objects.named(TargetJvmEnvironment.STANDARD_JVM),
                )
            }
            // The test classpath has the Kotlin standard library already, a newer one than an old OkHttp asks for
            exclude(group = "org.jetbrains.kotlin")
        }
    dependencies.add(okHttp.name, "com.squareup.okhttp3:okhttp:$version")

    tasks.register<Test>("testOkHttp$id") {
        group = "verification"
        description = "Runs the OkHttp tests of the agent against OkHttp $version"
        val debugTests = tasks.named<Test>("testDebugUnitTest")
        testClassesDirs = files(debugTests.map { it.testClassesDirs })
        classpath =
            files(
                debugTests.map { tests ->
                    tests.classpath.filter { !it.name.startsWith("okhttp") && !it.name.startsWith("okio") }
                }
            ) + okHttp
        systemProperty("gekko.okhttp.version", version)
        filter {
            includeTestsMatching("com.jakala.gekko.agent.MockInterceptorTest")
            includeTestsMatching("com.jakala.gekko.agent.OkHttpCompatibilityTest")
        }
    }
}

val testOkHttpVersions =
    tasks.register("testOkHttpVersions") {
        group = "verification"
        description = "Runs the OkHttp tests of the agent against every OkHttp minor from 3.12 to 5.x"
        dependsOn(okHttpTests)
    }

tasks.named("check") { dependsOn(testOkHttpVersions) }

mavenPublishing {
    coordinates(artifactId = "gekko-agent-android")
    pom {
        name.set("gekko Android agent")
        description.set("In-app agent that exposes the Compose and View UI tree to the gekko CLI")
    }
}
