package com.jakala.gekko.cli

import com.jakala.gekko.protocol.Protocol
import io.ktor.client.HttpClient
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsBytes
import io.ktor.http.ContentType
import io.ktor.http.contentType
import okio.ByteString.Companion.toByteString

/**
 * What the CLI knows about the server it talks to. [key] is the secret that only this user's CLI and the servers it
 * started share; [endpoint] is what the server knows about itself (its port, or the name of its socket on Android).
 * Neither is ever sent: they only go into the HMACs of [Protocol].
 */
class ClientAuth(val key: ByteArray, val endpoint: String) {
    companion object {
        /** The servers that get the user's token as it is: the iOS simulators and the web bridge. */
        fun ofUser(endpoint: String): ClientAuth {
            return ClientAuth(UserToken.value.encodeToByteArray(), endpoint)
        }

        /**
         * The key of an Android server. A device is not as private as this computer (it can be shared, lost or rooted),
         * so it gets a key derived for one socket instead of the token that also guards the simulators.
         */
        fun ofAndroid(socketName: String): ClientAuth {
            return ClientAuth(androidKey(UserToken.value, socketName).encodeToByteArray(), socketName)
        }

        /** The key as the text that goes onto the device (a file of the app, an argument of `am instrument`). */
        fun androidKey(userToken: String, socketName: String): String {
            return GekkoAuth.hmacHex(userToken.encodeToByteArray(), "android\n$socketName".encodeToByteArray())
        }
    }
}

/** HMAC-SHA256 as the servers do it (see [Protocol]). */
object GekkoAuth {
    fun hmacHex(key: ByteArray, message: ByteArray): String {
        return message.toByteString().hmacSha256(key.toByteString()).hex()
    }

    /** Compares two MACs without leaking at which character they differ. */
    fun matches(expected: String, given: String): Boolean {
        val left = expected.encodeToByteArray()
        val right = given.encodeToByteArray()
        var difference = left.size xor right.size
        for (index in 0 until maxOf(left.size, right.size)) {
            val a = if (index < left.size) left[index].toInt() else 0
            val b = if (index < right.size) right[index].toInt() else 0
            difference = difference or (a xor b)
        }
        return difference == 0
    }
}

/** Something answers where a gekko server should, but it does not prove that it is one (see [SignedPeer]). */
class UntrustedServerException(message: String) : IncompatibleVersionException(message)

/**
 * One server (the in-app agent, the system runner or the web bridge) that has to prove with every answer that it holds
 * the key of this user. Whoever listens on a port or a socket first can answer the CLI, so an answer that is not signed
 * for the request it belongs to is never believed: the program that sent it can be an app on the device or any process
 * of this computer.
 *
 * [problem] says what to do when the answer is not signed, e.g. to update the agent: a server that is older than the
 * authentication cannot sign.
 */
internal class SignedPeer(
    private val http: HttpClient,
    private val port: Int,
    private val auth: ClientAuth,
    private val peer: String,
    private val problem: String,
) {
    /** What the server answered, once its signature is checked. */
    class Reply(val status: Int, val body: ByteArray, val session: String)

    /** The session of the server, learned from a verified hello; null until then and after the server was restarted. */
    private var session: String? = null

    /**
     * Sends [body] and returns the verified answer. [hello] says that this is the request that opens a session; any
     * other request is sent in the session that a hello of this peer opened, which [helloBody] does first when there is
     * none. [isStale] tells that the server refused a request because it was restarted since: the request has not run
     * then, so it is sent once more in the new session.
     */
    suspend fun send(body: ByteArray, hello: Boolean, helloBody: ByteArray, isStale: (Reply) -> Boolean): Reply {
        if (hello) {
            return open(body)
        }
        val first = post(body, session ?: open(helloBody).session)
        if (!isStale(first)) {
            return first
        }
        // The refusal is signed, so the session it names is the real server's new one
        session = first.session
        return post(body, first.session)
    }

    private suspend fun open(helloBody: ByteArray): Reply {
        val reply = post(helloBody, "")
        session = reply.session
        return reply
    }

    private suspend fun post(body: ByteArray, session: String): Reply {
        val nonce = randomHex(NONCE_BYTES)
        val mac = GekkoAuth.hmacHex(auth.key, Protocol.requestMessage(auth.endpoint, session, nonce, body))
        val response =
            http.post("http://127.0.0.1:$port/") {
                header(Protocol.NONCE_HEADER, nonce)
                header(Protocol.SESSION_HEADER, session)
                header(Protocol.AUTH_HEADER, mac)
                contentType(ContentType.Application.Json)
                setBody(body)
            }
        val answer = response.bodyAsBytes()
        val answeredSession = response.headers[Protocol.SESSION_HEADER]
        val given = response.headers[Protocol.AUTH_HEADER]
        if (answeredSession == null || given == null) {
            throw untrusted("its answer is not signed")
        }
        val status = response.status.value
        val expected =
            GekkoAuth.hmacHex(
                auth.key,
                Protocol.responseMessage(auth.endpoint, answeredSession, nonce, status, answer),
            )
        if (!GekkoAuth.matches(expected, given)) {
            throw untrusted("its signature is wrong")
        }
        return Reply(status, answer, answeredSession)
    }

    private fun untrusted(why: String): UntrustedServerException {
        return UntrustedServerException(
            "Something answers on port $port that did not prove to be the $peer of this gekko ($why). " +
                "It is either an older gekko that cannot sign its answers or another program that holds the port. " +
                problem
        )
    }

    private companion object {
        const val NONCE_BYTES = 16
    }
}
