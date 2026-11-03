package com.jakala.gekko.protocol

import kotlinx.serialization.Serializable

/**
 * One network mock: a request that matches [urlRegex], [method] and [bodyContains] is answered with [status], [headers]
 * and the body in [bodyBase64] after [delayMs] instead of going to the backend.
 *
 * The CLI keeps the list in the order the mocks were added and sends all of it every time it changes; when several
 * rules match a request the **last** one wins ([MockMatcher.match]). [id] names the rule in [MockHit].
 *
 * [urlRegex] is a portable regular expression (anchored, only escaped literals and `.*`, see [MockRules.globToRegex]),
 * so every agent can compile it with its own engine.
 */
@Serializable
data class MockRule(
    val id: String,
    val urlRegex: String,
    /** Upper case HTTP method; null matches every method. */
    val method: String? = null,
    /** A part of the request body the request has to contain, e.g. the name of a GraphQL operation. */
    val bodyContains: String? = null,
    val status: Int = 200,
    val headers: Map<String, String> = emptyMap(),
    val delayMs: Long = 0,
    val bodyBase64: String = "",
)

/** A request that was answered by the mock [id]. */
@Serializable data class MockHit(val id: String, val method: String, val url: String)

/** The mock rules an agent holds, with their patterns compiled once. */
class MockMatcher(val rules: List<MockRule>) {
    private val patterns = rules.map { Regex(it.urlRegex) }

    /**
     * The rule that answers a request: the last one that matches. [body] is only read for rules with a
     * [MockRule.bodyContains] (null when the request has no body).
     */
    fun match(method: String, url: String, body: () -> String?): MockRule? {
        for (index in rules.indices.reversed()) {
            val rule = rules[index]
            if (!matchesRequestLine(index, method, url)) continue
            val needle = rule.bodyContains
            if (needle != null && body()?.contains(needle) != true) continue
            return rule
        }
        return null
    }

    /** True when a rule matches the URL, whatever its method and body filter say. */
    fun matchesUrl(url: String): Boolean {
        return patterns.any { it.matches(url) }
    }

    /** True when a rule matches by method and URL, whatever its body filter says. */
    fun matchesIgnoringBody(method: String, url: String): Boolean {
        return rules.indices.any { matchesRequestLine(it, method, url) }
    }

    /** True when a rule that matches by method and URL also wants to look at the body of the request. */
    fun needsBody(method: String, url: String): Boolean {
        return rules.indices.any { matchesRequestLine(it, method, url) && rules[it].bodyContains != null }
    }

    private fun matchesRequestLine(index: Int, method: String, url: String): Boolean {
        val rule = rules[index]
        if (rule.method != null && !rule.method.equals(method, ignoreCase = true)) return false
        return patterns[index].matches(url)
    }
}

/** Facts about mock rules that the CLI and the JVM agents share. */
object MockRules {
    /** The largest command the CLI sends (the agents refuse more than 4 MB), so it checks the size before it sends. */
    const val MAX_BYTES = 3_000_000

    /**
     * The most of a request body an agent looks at for a [MockRule.bodyContains]; a larger body is never matched, so
     * that an upload does not have to fit in memory.
     */
    const val MAX_INSPECTED_BODY_BYTES = 1_048_576

    /** Mock rules of a run, written by the CLI before the app starts (Android: file in the data directory). */
    const val STARTUP_FILE = "gekko-mocks.json"

    /**
     * A glob over the whole URL: `*` stands for any characters (also `/`, `?` and `&`), everything else is literal.
     * Written with escaped literals only, so Kotlin, Swift and JavaScript read it the same way.
     */
    fun globToRegex(glob: String): String {
        val builder = StringBuilder("^")
        for (char in glob) {
            when {
                char == '*' -> builder.append(".*")
                char in REGEX_SPECIALS -> builder.append('\\').append(char)
                else -> builder.append(char)
            }
        }
        return builder.append('$').toString()
    }

    /** The content type of a mock file, from its extension. */
    fun contentTypeOf(file: String): String {
        return when (file.substringAfterLast('.', "").lowercase()) {
            "json" -> "application/json"
            "xml" -> "application/xml"
            "html",
            "htm" -> "text/html"
            "csv" -> "text/csv"
            "txt" -> "text/plain"
            else -> "application/octet-stream"
        }
    }

    private const val REGEX_SPECIALS = "\\.[]{}()+?^\$|"
}
