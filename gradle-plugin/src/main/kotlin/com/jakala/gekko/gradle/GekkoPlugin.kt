package com.jakala.gekko.gradle

import com.android.build.api.variant.AndroidComponentsExtension
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.provider.ListProperty
import org.gradle.api.provider.Property

abstract class GekkoExtension {
    /** Build types or full variant names that receive the in-process agent. Defaults to `debug`. */
    abstract val variants: ListProperty<String>

    /** Version of `io.github.jakala-germany:gekko-agent-android`; the version of this plugin by default. */
    abstract val version: Property<String>

    /** Overrides the agent dependency notation, e.g. a `project(":agent-android")`. */
    abstract val agent: Property<Any>
}

class GekkoPlugin : Plugin<Project> {
    override fun apply(project: Project) {
        val extension = project.extensions.create("gekko", GekkoExtension::class.java)
        extension.variants.convention(listOf("debug"))
        extension.version.convention(PluginVersion.NAME)
        project.pluginManager.withPlugin("com.android.application") {
            val components = project.extensions.getByType(AndroidComponentsExtension::class.java)
            components.onVariants { variant ->
                val selected = extension.variants.get()
                val matches = variant.name in selected || variant.buildType in selected
                if (matches) {
                    val notation = extension.agent.orNull ?: agentNotation(extension.version.get())
                    project.dependencies.add("${variant.name}Implementation", notation)
                }
            }
        }
    }

    internal companion object {
        /** The dependency that puts the in-app agent into a variant. */
        fun agentNotation(version: String): String {
            return "io.github.jakala-germany:gekko-agent-android:$version"
        }
    }
}
