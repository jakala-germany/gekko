package com.jakala.gekko.transport

import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/** HMAC-SHA256 and randomness for the authentication of the wire format (see `Protocol`). */
object GekkoAuth {
    private const val ALGORITHM = "HmacSHA256"
    private const val HEX = "0123456789abcdef"
    private val random = SecureRandom()

    fun hmacHex(key: ByteArray, message: ByteArray): String {
        val mac = Mac.getInstance(ALGORITHM)
        mac.init(SecretKeySpec(key, ALGORITHM))
        return hex(mac.doFinal(message))
    }

    /** Compares two MACs without leaking at which character they differ. */
    fun matches(expected: String, given: String): Boolean {
        return MessageDigest.isEqual(expected.toByteArray(Charsets.UTF_8), given.toByteArray(Charsets.UTF_8))
    }

    fun randomHex(byteCount: Int): String {
        val bytes = ByteArray(byteCount)
        random.nextBytes(bytes)
        return hex(bytes)
    }

    private fun hex(bytes: ByteArray): String {
        val builder = StringBuilder(bytes.size * 2)
        for (byte in bytes) {
            val value = byte.toInt() and 0xff
            builder.append(HEX[value shr 4]).append(HEX[value and 0x0f])
        }
        return builder.toString()
    }
}

/**
 * How a [CommandServer] proves itself and checks its clients: with HMACs over a key that only this user's CLI knows.
 * [endpoint] is what the server knows about itself (the socket name on Android, the port elsewhere). [key] is asked for
 * with every request and may return null while the server has none yet, which makes it refuse everything. The [session]
 * is picked when the server starts, so a request that was recorded earlier cannot be replayed to it.
 */
class ServerAuth
private constructor(
    val endpoint: String,
    private val keyProvider: () -> ByteArray?,
    internal val enforced: Boolean,
) {
    constructor(endpoint: String, key: () -> ByteArray?) : this(endpoint, key, true)

    val session: String = GekkoAuth.randomHex(SESSION_BYTES)

    fun key(): ByteArray? {
        return keyProvider()?.takeIf { it.isNotEmpty() }
    }

    companion object {
        private const val SESSION_BYTES = 16

        /** No authentication, for tests of the HTTP framing; never reachable from a release build. */
        internal fun open(): ServerAuth {
            return ServerAuth("", { null }, false)
        }
    }
}
