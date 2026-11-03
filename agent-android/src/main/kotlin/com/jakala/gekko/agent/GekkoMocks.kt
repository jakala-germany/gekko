package com.jakala.gekko.agent

import android.util.Log
import com.jakala.gekko.protocol.Command
import com.jakala.gekko.protocol.MockHit
import com.jakala.gekko.protocol.MockMatcher
import com.jakala.gekko.protocol.MockRule
import java.io.File
import kotlin.io.encoding.Base64
import kotlinx.serialization.json.Json

/**
 * The network mocks of the app: which requests are answered with a canned response instead of going to the backend. The
 * HTTP clients that the gekko Gradle plugin hooks ask this object for every request; without mocks it costs one read of
 * a field. The rules come from the CLI ([Command.SetMocks]) or from the file it leaves for the app start.
 */
internal object GekkoMocks {
    private const val TAG = "GekkoAgent"
    private const val MAX_HITS = 10_000

    @Volatile private var matcher: MockMatcher? = null
    private val hits = mutableListOf<MockHit>()

    /** The mocks that are set now; null when nothing is mocked. */
    fun current(): MockMatcher? {
        return matcher
    }

    /** Replaces every mock; throws when a rule has a pattern that cannot be compiled. */
    fun set(rules: List<MockRule>) {
        matcher = if (rules.isEmpty()) null else MockMatcher(rules)
    }

    /** Removes every mock and forgets the requests they answered. */
    fun clear() {
        matcher = null
        synchronized(hits) { hits.clear() }
    }

    /** Remembers an answered request; past [MAX_HITS] the oldest ones are forgotten, as on iOS and in the browser. */
    fun record(rule: MockRule, method: String, url: String) {
        synchronized(hits) {
            hits += MockHit(rule.id, method, url)
            if (hits.size > MAX_HITS) hits.subList(0, hits.size - MAX_HITS).clear()
        }
    }

    /** The requests that mocks answered since the last call, oldest first. */
    fun drainHits(): List<MockHit> {
        synchronized(hits) {
            val answered = hits.toList()
            hits.clear()
            return answered
        }
    }

    fun bodyOf(rule: MockRule): ByteArray {
        return if (rule.bodyBase64.isEmpty()) ByteArray(0) else Base64.decode(rule.bodyBase64)
    }

    fun contentTypeOf(rule: MockRule): String? {
        return rule.headers.entries.firstOrNull { it.key.equals("Content-Type", ignoreCase = true) }?.value
    }

    /**
     * Picks up the mocks the CLI left in the data directory for this start of the app and deletes the file, so that a
     * mock never outlives the run that set it. Never fails: a test agent must not take the app down.
     */
    fun loadStartupFile(file: File) {
        if (!file.exists()) return
        try {
            val command = JSON.decodeFromString(Command.serializer(), file.readText())
            if (command is Command.SetMocks) set(command.rules)
        } catch (error: Exception) {
            Log.w(TAG, "Gekko could not read the network mocks of this start: ${error.message}")
        } finally {
            file.delete()
        }
    }

    private val JSON = Json {
        classDiscriminator = "type"
        ignoreUnknownKeys = true
    }
}
