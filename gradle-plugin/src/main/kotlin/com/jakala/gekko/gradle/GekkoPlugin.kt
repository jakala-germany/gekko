package com.jakala.gekko.gradle

import com.android.build.api.instrumentation.FramesComputationMode
import com.android.build.api.instrumentation.InstrumentationScope
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

    /**
     * Lets tests answer the app's network requests from files (`mock` steps): OkHttp and `HttpURLConnection` are hooked
     * in the variants that get the agent. Does nothing while no test sets a mock. On by default with the agent of this
     * plugin's release; off by default when [version] or [agent] picks another agent, because the hooks call classes
     * that only the agent of the same release has. `false` leaves the bytecode of the app as it is.
     */
    abstract val networkMocks: Property<Boolean>
}

class GekkoPlugin : Plugin<Project> {
    override fun apply(project: Project) {
        val extension = project.extensions.create("gekko", GekkoExtension::class.java)
        extension.variants.convention(listOf("debug"))
        extension.version.convention(PluginVersion.NAME)
        extension.networkMocks.convention(
            project.provider { !extension.agent.isPresent && extension.version.get() == PluginVersion.NAME }
        )
        project.pluginManager.withPlugin("com.android.application") {
            val components = project.extensions.getByType(AndroidComponentsExtension::class.java)
            components.onVariants { variant ->
                val selected = extension.variants.get()
                val matches = variant.name in selected || variant.buildType in selected
                if (matches) {
                    // An AGP that does not know `debuggable` yet cannot tell, so it is not warned about
                    if (runCatching { variant.debuggable }.getOrNull() == false) {
                        project.logger.warn(notDebuggableWarning(variant.name))
                    }
                    val notation = extension.agent.orNull ?: agentNotation(extension.version.get())
                    project.dependencies.add("${variant.name}Implementation", notation)
                    if (extension.networkMocks.get()) {
                        variant.instrumentation.transformClassesWith(
                            MockClassVisitorFactory::class.java,
                            InstrumentationScope.ALL,
                        ) {}
                        variant.instrumentation.setAsmFramesComputationMode(
                            FramesComputationMode.COMPUTE_FRAMES_FOR_INSTRUMENTED_METHODS
                        )
                    }
                }
            }
        }
    }

    internal companion object {
        /** The dependency that puts the in-app agent into a variant. */
        fun agentNotation(version: String): String {
            return "io.github.jakala-germany:gekko-agent-android:$version"
        }

        /**
         * The agent only answers a CLI that holds its key, and the CLI hands the key over with `run-as`, which only a
         * debuggable build allows. In any other build the agent is in the app and refuses every command.
         */
        fun notDebuggableWarning(variant: String): String {
            return "gekko: the variant '$variant' is not debuggable, so the gekko agent in it cannot get its key " +
                "(the CLI hands it over with run-as) and refuses every command. List only debuggable variants in " +
                "gekko { variants }; a build for users must never contain the agent."
        }
    }
}
