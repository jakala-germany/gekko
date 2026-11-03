package com.jakala.gekko.gradle

import com.android.build.api.instrumentation.AsmClassVisitorFactory
import com.android.build.api.instrumentation.ClassContext
import com.android.build.api.instrumentation.ClassData
import com.android.build.api.instrumentation.InstrumentationParameters
import org.objectweb.asm.ClassVisitor
import org.objectweb.asm.MethodVisitor
import org.objectweb.asm.Opcodes

/**
 * Lets the in-app agent answer network requests for the tests: every `OkHttpClient` gets one more interceptor, and
 * every `URL.openConnection()` and `URL.openStream()` of the app and its libraries goes through the agent first.
 * Nothing changes while no test has set a mock: the agent hands the request on at once.
 */
abstract class MockClassVisitorFactory : AsmClassVisitorFactory<InstrumentationParameters.None> {
    override fun createClassVisitor(classContext: ClassContext, nextClassVisitor: ClassVisitor): ClassVisitor {
        return MockClassVisitor(instrumentationContext.apiVersion.get(), nextClassVisitor)
    }

    override fun isInstrumentable(classData: ClassData): Boolean {
        return MockInstrumentation.isInstrumentable(classData.className)
    }
}

internal object MockInstrumentation {
    const val AGENT = "com/jakala/gekko/agent/"
    const val OKHTTP_CLIENT = "okhttp3/OkHttpClient"
    const val OKHTTP_CLIENT_CONSTRUCTOR = "(Lokhttp3/OkHttpClient\$Builder;)V"
    const val OKHTTP_HOOK = "com/jakala/gekko/agent/GekkoOkHttp"
    const val URL_HOOK = "com/jakala/gekko/agent/GekkoUrlConnection"

    /** The packages of the agent and of what it brings; the agent opens the real connections itself. */
    private val AGENT_PACKAGES =
        listOf(
            "com/jakala/gekko/agent/",
            "com/jakala/gekko/protocol/",
            "com/jakala/gekko/transport/",
            "com/jakala/gekko/device/",
        )

    /**
     * Not the agent itself and not the runtime of Kotlin: nothing there makes requests of the app. Except `kotlin.io`:
     * `URL.readBytes()` and `URL.readText()` open the stream there, for the app.
     */
    fun isInstrumentable(className: String): Boolean {
        val internalName = className.replace('.', '/')
        val kotlinRuntime =
            (internalName.startsWith("kotlin/") && !internalName.startsWith("kotlin/io/")) ||
                internalName.startsWith("kotlinx/")
        return AGENT_PACKAGES.none { internalName.startsWith(it) } && !kotlinRuntime
    }
}

internal class MockClassVisitor(api: Int, next: ClassVisitor) : ClassVisitor(api, next) {
    private var className = ""

    override fun visit(
        version: Int,
        access: Int,
        name: String,
        signature: String?,
        superName: String?,
        interfaces: Array<out String>?,
    ) {
        className = name
        super.visit(version, access, name, signature, superName, interfaces)
    }

    override fun visitMethod(
        access: Int,
        name: String,
        descriptor: String,
        signature: String?,
        exceptions: Array<out String>?,
    ): MethodVisitor? {
        val next = super.visitMethod(access, name, descriptor, signature, exceptions) ?: return null
        if (
            className == MockInstrumentation.OKHTTP_CLIENT &&
                name == "<init>" &&
                descriptor == MockInstrumentation.OKHTTP_CLIENT_CONSTRUCTOR
        ) {
            return OkHttpConstructorVisitor(api, next)
        }
        return UrlCallVisitor(api, next)
    }
}

/**
 * The constructor every `OkHttpClient` is made with (`OkHttpClient()`, `Builder.build()` and `newBuilder()` all end
 * there): the agent gets the builder before the client copies its interceptors. A static call that only uses the
 * parameter may stand in front of `super()`.
 */
internal class OkHttpConstructorVisitor(api: Int, next: MethodVisitor) : MethodVisitor(api, next) {
    override fun visitCode() {
        super.visitCode()
        super.visitVarInsn(Opcodes.ALOAD, 1)
        super.visitMethodInsn(
            Opcodes.INVOKESTATIC,
            MockInstrumentation.OKHTTP_HOOK,
            "install",
            MockInstrumentation.OKHTTP_CLIENT_CONSTRUCTOR,
            false,
        )
    }
}

/** `URL.openConnection()`, `URL.openConnection(Proxy)` and `URL.openStream()` become static calls to the agent. */
internal class UrlCallVisitor(api: Int, next: MethodVisitor) : MethodVisitor(api, next) {
    override fun visitMethodInsn(opcode: Int, owner: String, name: String, descriptor: String, isInterface: Boolean) {
        if (opcode == Opcodes.INVOKEVIRTUAL && owner == "java/net/URL") {
            val replacement =
                when {
                    name == "openConnection" && descriptor == "()Ljava/net/URLConnection;" ->
                        "open" to "(Ljava/net/URL;)Ljava/net/URLConnection;"
                    name == "openConnection" && descriptor == "(Ljava/net/Proxy;)Ljava/net/URLConnection;" ->
                        "open" to "(Ljava/net/URL;Ljava/net/Proxy;)Ljava/net/URLConnection;"
                    name == "openStream" && descriptor == "()Ljava/io/InputStream;" ->
                        "openStream" to "(Ljava/net/URL;)Ljava/io/InputStream;"
                    else -> null
                }
            if (replacement != null) {
                super.visitMethodInsn(
                    Opcodes.INVOKESTATIC,
                    MockInstrumentation.URL_HOOK,
                    replacement.first,
                    replacement.second,
                    false,
                )
                return
            }
        }
        super.visitMethodInsn(opcode, owner, name, descriptor, isInterface)
    }
}
