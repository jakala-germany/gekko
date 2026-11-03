package com.jakala.gekko.gradle

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.objectweb.asm.ClassReader
import org.objectweb.asm.ClassVisitor
import org.objectweb.asm.ClassWriter
import org.objectweb.asm.MethodVisitor
import org.objectweb.asm.Opcodes

class MockInstrumentationTest {
    /** One call: `owner.name descriptor` with the opcode that makes it. */
    private data class Call(val opcode: Int, val owner: String, val name: String, val descriptor: String)

    private val urlConnection = "()Ljava/net/URLConnection;"

    /** A class with one method that makes [calls] in a row, as the compiler would write the calls of an app. */
    private fun classWith(name: String, methodName: String, descriptor: String, calls: List<Call>): ByteArray {
        val writer = ClassWriter(ClassWriter.COMPUTE_MAXS)
        writer.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, name, null, "java/lang/Object", null)
        val method = writer.visitMethod(Opcodes.ACC_PUBLIC, methodName, descriptor, null, null)
        method.visitCode()
        method.visitVarInsn(Opcodes.ALOAD, 0)
        method.visitVarInsn(Opcodes.ALOAD, 1)
        calls.forEach { method.visitMethodInsn(it.opcode, it.owner, it.name, it.descriptor, false) }
        method.visitInsn(Opcodes.POP)
        method.visitInsn(Opcodes.RETURN)
        method.visitMaxs(0, 0)
        method.visitEnd()
        writer.visitEnd()
        return writer.toByteArray()
    }

    private fun instrumented(original: ByteArray): ByteArray {
        val writer = ClassWriter(0)
        ClassReader(original).accept(MockClassVisitor(Opcodes.ASM9, writer), 0)
        return writer.toByteArray()
    }

    /** Every call the methods of a class make, in order. */
    private fun callsOf(bytes: ByteArray): List<Call> {
        val calls = mutableListOf<Call>()
        ClassReader(bytes)
            .accept(
                object : ClassVisitor(Opcodes.ASM9) {
                    override fun visitMethod(
                        access: Int,
                        name: String,
                        descriptor: String,
                        signature: String?,
                        exceptions: Array<out String>?,
                    ): MethodVisitor {
                        return object : MethodVisitor(Opcodes.ASM9) {
                            override fun visitMethodInsn(
                                opcode: Int,
                                owner: String,
                                name: String,
                                descriptor: String,
                                isInterface: Boolean,
                            ) {
                                calls += Call(opcode, owner, name, descriptor)
                            }
                        }
                    }
                },
                0,
            )
        return calls
    }

    @Test
    fun openConnectionOfAnUrlGoesThroughTheAgent() {
        val original =
            classWith(
                "app/Fetcher",
                "fetch",
                "(Ljava/net/URL;)V",
                listOf(Call(Opcodes.INVOKEVIRTUAL, "java/net/URL", "openConnection", urlConnection)),
            )

        val calls = callsOf(instrumented(original))

        assertEquals(
            listOf(
                Call(
                    Opcodes.INVOKESTATIC,
                    "com/jakala/gekko/agent/GekkoUrlConnection",
                    "open",
                    "(Ljava/net/URL;)Ljava/net/URLConnection;",
                )
            ),
            calls,
        )
    }

    @Test
    fun openConnectionWithAProxyAndOpenStreamAreHookedToo() {
        val withProxy =
            classWith(
                "app/Proxied",
                "fetch",
                "(Ljava/net/URL;)V",
                listOf(
                    Call(
                        Opcodes.INVOKEVIRTUAL,
                        "java/net/URL",
                        "openConnection",
                        "(Ljava/net/Proxy;)Ljava/net/URLConnection;",
                    )
                ),
            )
        val stream =
            classWith(
                "app/Streamed",
                "fetch",
                "(Ljava/net/URL;)V",
                listOf(Call(Opcodes.INVOKEVIRTUAL, "java/net/URL", "openStream", "()Ljava/io/InputStream;")),
            )

        assertEquals(
            "(Ljava/net/URL;Ljava/net/Proxy;)Ljava/net/URLConnection;",
            callsOf(instrumented(withProxy)).single().descriptor,
        )
        assertEquals("openStream", callsOf(instrumented(stream)).single().name)
    }

    @Test
    fun otherCallsAreLeftAlone() {
        val original =
            classWith(
                "app/Other",
                "fetch",
                "(Ljava/lang/Object;)V",
                listOf(
                    Call(Opcodes.INVOKEVIRTUAL, "java/net/URL", "toString", "()Ljava/lang/String;"),
                    Call(Opcodes.INVOKEVIRTUAL, "java/lang/Object", "hashCode", "()I"),
                ),
            )

        assertEquals(callsOf(original), callsOf(instrumented(original)))
    }

    @Test
    fun theOkHttpClientConstructorHandsItsBuilderToTheAgentFirst() {
        val writer = ClassWriter(ClassWriter.COMPUTE_MAXS)
        writer.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, "okhttp3/OkHttpClient", null, "java/lang/Object", null)
        val constructor =
            writer.visitMethod(Opcodes.ACC_PUBLIC, "<init>", "(Lokhttp3/OkHttpClient\$Builder;)V", null, null)
        constructor.visitCode()
        constructor.visitVarInsn(Opcodes.ALOAD, 0)
        constructor.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false)
        constructor.visitInsn(Opcodes.RETURN)
        constructor.visitMaxs(0, 0)
        constructor.visitEnd()
        writer.visitEnd()

        val calls = callsOf(instrumented(writer.toByteArray()))

        assertEquals(
            listOf(
                Call(
                    Opcodes.INVOKESTATIC,
                    "com/jakala/gekko/agent/GekkoOkHttp",
                    "install",
                    "(Lokhttp3/OkHttpClient\$Builder;)V",
                ),
                Call(Opcodes.INVOKESPECIAL, "java/lang/Object", "<init>", "()V"),
            ),
            calls,
        )
    }

    @Test
    fun anotherConstructorOfAnotherClassIsNotHooked() {
        val writer = ClassWriter(ClassWriter.COMPUTE_MAXS)
        writer.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, "app/Client", null, "java/lang/Object", null)
        val constructor =
            writer.visitMethod(Opcodes.ACC_PUBLIC, "<init>", "(Lokhttp3/OkHttpClient\$Builder;)V", null, null)
        constructor.visitCode()
        constructor.visitVarInsn(Opcodes.ALOAD, 0)
        constructor.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false)
        constructor.visitInsn(Opcodes.RETURN)
        constructor.visitMaxs(0, 0)
        constructor.visitEnd()
        writer.visitEnd()

        assertEquals(1, callsOf(instrumented(writer.toByteArray())).size)
    }

    @Test
    fun theAgentAndTheRuntimeOfKotlinAreNotInstrumented() {
        assertFalse(MockInstrumentation.isInstrumentable("com.jakala.gekko.agent.MockableConnection"))
        assertFalse(MockInstrumentation.isInstrumentable("com.jakala.gekko.transport.CommandServer"))
        // An app of the same company is not the agent
        assertTrue(MockInstrumentation.isInstrumentable("com.jakala.gekko.sample.MainActivityKt"))
        assertFalse(MockInstrumentation.isInstrumentable("kotlin.collections.CollectionsKt"))
        assertFalse(MockInstrumentation.isInstrumentable("kotlinx.coroutines.DelayKt"))
        assertTrue(MockInstrumentation.isInstrumentable("com.example.app.Api"))
        assertTrue(MockInstrumentation.isInstrumentable("okhttp3.OkHttpClient"))
        assertTrue(MockInstrumentation.isInstrumentable("com.android.volley.toolbox.HurlStack"))
    }

    @Test
    fun theUrlHelpersOfKotlinAreInstrumented() {
        // URL.readBytes() and URL.readText() open the stream inside kotlin.io, for the app
        assertTrue(MockInstrumentation.isInstrumentable("kotlin.io.TextStreamsKt"))
    }
}
