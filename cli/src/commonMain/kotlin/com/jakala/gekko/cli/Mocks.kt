package com.jakala.gekko.cli

import com.jakala.gekko.protocol.MockHit
import com.jakala.gekko.protocol.MockRule
import com.jakala.gekko.protocol.MockRules
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi

/** A string as it has to be written between the quotes of a JSON string. */
fun jsonEscape(value: String): String {
    val builder = StringBuilder()
    for (char in value) {
        when {
            char == '"' -> builder.append("\\\"")
            char == '\\' -> builder.append("\\\\")
            char == '\n' -> builder.append("\\n")
            char == '\r' -> builder.append("\\r")
            char == '\t' -> builder.append("\\t")
            char.code < 0x20 -> builder.append("\\u").append(char.code.toString(16).padStart(4, '0'))
            else -> builder.append(char)
        }
    }
    return builder.toString()
}

/** A mock that was set during the test and how often a request was answered by it. */
class MockEntry(val rule: MockRule, private val glob: String, private val file: String?) {
    var hits = 0
        private set

    /** The mock that replaced this one while a request it answered was still to be reported. */
    var successor: MockEntry? = null

    /** What identifies the mock: setting the same again replaces it. */
    val key: List<String?> = listOf(rule.urlRegex, rule.method, rule.bodyContains)

    /** The mock as the log shows it: `GET https://api.example.com/routes*`. */
    val label: String
        get() = listOfNotNull(rule.method, glob).joinToString(" ")

    fun answer(): String {
        return file ?: "status ${rule.status}"
    }

    fun hit() {
        hits++
    }
}

/**
 * The network mocks of one test as the CLI knows them: in the order they were set (the last matching one answers a
 * request), plus a history of everything that was set, to report mocks that no request ever used. A test starts and
 * ends with an empty set.
 */
class MockSet {
    private var counter = 0
    private val active = mutableListOf<MockEntry>()
    private val history = mutableListOf<MockEntry>()

    /** Replaced mocks that nobody had used yet: a request they answered may still be reported. */
    private val retired = mutableListOf<MockEntry>()

    val isActive: Boolean
        get() = active.isNotEmpty()

    /** True when a mock was set during this test, also one that is removed again. */
    val hasBeenSet: Boolean
        get() = history.isNotEmpty() || counter > 0

    /** The mocks to hand to the app, oldest first. */
    fun rules(): List<MockRule> {
        return active.map { it.rule }
    }

    /**
     * Adds a mock built from [spec] answering with [body]. A mock with the same URL, method and body filter replaces
     * the earlier one, which is only reported as unused when it was set with nobody asking for it.
     */
    @OptIn(ExperimentalEncodingApi::class)
    fun add(spec: MockSpec, body: String): MockEntry {
        counter++
        val headers = spec.headers.toMutableMap()
        if (spec.file != null && headers.keys.none { it.equals("Content-Type", ignoreCase = true) }) {
            headers["Content-Type"] = MockRules.contentTypeOf(spec.file)
        }
        val rule =
            MockRule(
                id = "m$counter",
                urlRegex = MockRules.globToRegex(spec.url),
                method = spec.method?.uppercase(),
                bodyContains = spec.bodyContains,
                status = spec.status,
                headers = headers,
                delayMs = spec.delayMs,
                bodyBase64 = Base64.encode(body.encodeToByteArray()),
            )
        val entry = MockEntry(rule, spec.url, spec.file)
        val replaced = active.filter { it.key == entry.key }
        // Checked before anything changes: a mock that is too big leaves the set as the app still has it
        val size =
            (active - replaced.toSet() + entry).sumOf {
                it.rule.bodyBase64.length + it.rule.urlRegex.length + SIZE_OVERHEAD
            }
        if (size > MockRules.MAX_BYTES) {
            throw StepFailure(
                "The mock files are too big: all mocks together may have about 2 MB, this would be ~${size / 1000} KB"
            )
        }
        active.removeAll(replaced)
        val unused = replaced.filter { it.hits == 0 }
        history.removeAll(unused)
        unused.forEach { it.successor = entry }
        retired += unused
        active += entry
        history += entry
        return entry
    }

    fun clear() {
        active.clear()
    }

    /** Forgets everything: the start and the end of a test. */
    fun reset() {
        active.clear()
        history.clear()
        retired.clear()
        counter = 0
    }

    /** Counts the requests the agent reports; returns one log line each. */
    fun record(hits: List<MockHit>): List<String> {
        return hits.map { hit ->
            val entry = (history + retired).firstOrNull { it.rule.id == hit.id }
            entry?.hit()
            // The mock that stands in for a replaced one has been used too
            var current = entry
            while (current?.successor != null) current = current.successor
            if (current !== entry) current?.hit()
            "mocked ${hit.method} ${hit.url} → ${entry?.answer() ?: hit.id}"
        }
    }

    /** The mocks that no request used, as they were written in the test. */
    fun unused(): List<String> {
        return history.filter { it.hits == 0 }.map { "${it.label} → ${it.answer()}" }
    }

    private companion object {
        /** Headers, ids and JSON punctuation of a rule, counted on top of the body when the size is checked. */
        const val SIZE_OVERHEAD = 400
    }
}
