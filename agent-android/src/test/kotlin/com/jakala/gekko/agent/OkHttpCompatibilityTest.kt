package com.jakala.gekko.agent

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import okhttp3.OkHttpClient
import org.junit.Assume.assumeTrue
import org.objectweb.asm.ClassReader
import org.objectweb.asm.ClassVisitor
import org.objectweb.asm.MethodVisitor
import org.objectweb.asm.Opcodes
import org.objectweb.asm.Type

/**
 * What the agent relies on in every OkHttp an app may bring (see `testOkHttpVersions` in build.gradle.kts). The Gradle
 * plugin hooks one constructor of `OkHttpClient`; this checks that no client is made past it.
 */
class OkHttpCompatibilityTest {
    private val clientName = Type.getInternalName(OkHttpClient::class.java)
    private val builderName = Type.getInternalName(OkHttpClient.Builder::class.java)

    /** The constructor the plugin hooks: it takes the builder, as the `install` of the agent does. */
    private val hookedDescriptor =
        Type.getMethodDescriptor(GekkoOkHttp::class.java.getMethod("install", OkHttpClient.Builder::class.java))

    /**
     * The class file of [name] in the OkHttp on the classpath: method name plus descriptor to the constructors it
     * calls.
     */
    private fun constructorCallsOf(name: String): Map<String, Set<String>> {
        val bytes = OkHttpClient::class.java.getResourceAsStream("/$name.class")!!.use { it.readBytes() }
        val calls = mutableMapOf<String, MutableSet<String>>()
        ClassReader(bytes)
            .accept(
                object : ClassVisitor(Opcodes.ASM9) {
                    override fun visitMethod(
                        access: Int,
                        methodName: String,
                        descriptor: String,
                        signature: String?,
                        exceptions: Array<out String>?,
                    ): MethodVisitor {
                        val made = calls.getOrPut(methodName + descriptor) { mutableSetOf() }
                        return object : MethodVisitor(Opcodes.ASM9) {
                            override fun visitMethodInsn(
                                opcode: Int,
                                owner: String,
                                calledName: String,
                                calledDescriptor: String,
                                isInterface: Boolean,
                            ) {
                                if (opcode == Opcodes.INVOKESPECIAL && owner == clientName && calledName == "<init>") {
                                    made += calledDescriptor
                                }
                            }
                        }
                    }
                },
                ClassReader.SKIP_DEBUG or ClassReader.SKIP_FRAMES,
            )
        return calls
    }

    @Test
    fun theOkHttpUnderTestIsTheOneAskedFor() {
        val asked = System.getProperty("gekko.okhttp.version")
        assumeTrue("not run for one OkHttp version in particular", asked != null)

        val jar = OkHttpClient::class.java.protectionDomain!!.codeSource.location.path

        assertTrue(jar.endsWith("-$asked.jar"), "asked for OkHttp $asked, the classpath has $jar")
    }

    @Test
    fun everyClientIsBuiltThroughTheConstructorThePluginHooks() {
        val client = constructorCallsOf(clientName)
        val builder = constructorCallsOf(builderName)

        val hooked = "<init>$hookedDescriptor"
        assertTrue(hooked in client, "OkHttpClient has no constructor $hookedDescriptor")
        val others = client.filterKeys { it.startsWith("<init>") && it != hooked }
        assertTrue(others.isNotEmpty(), "OkHttpClient has no public constructor")
        others.forEach { (constructor, made) ->
            assertEquals(setOf(hookedDescriptor), made, "$constructor does not go through the hooked constructor")
        }
        val build = builder.entries.single { it.key.startsWith("build()") }
        assertEquals(setOf(hookedDescriptor), build.value, "Builder.build() does not go through the hooked constructor")
    }
}
