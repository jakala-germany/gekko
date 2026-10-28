package com.jakala.gekko.cli

import com.jakala.gekko.protocol.Selector
import com.jakala.gekko.protocol.UiNode
import com.jakala.gekko.protocol.normalizedSpaces

/**
 * Without a model, the closest elements on screen are the best help for a step whose id is missing: a renamed id
 * usually still looks like the old one, and a button usually still carries the word from the step's `say`.
 */
object Suggester {
    /** Up to [limit] descriptions of elements on screen that look like [wanted] or fit the step's [say]. */
    fun closest(wanted: Selector, say: String, windows: List<UiNode>, limit: Int = 3): List<String> {
        val sayWords = words(say)
        return windows
            .flatMap { flatten(it) }
            .filter { !it.id.isNullOrBlank() || !it.text.isNullOrBlank() }
            .map { it to score(wanted, sayWords, it) }
            .filter { (_, score) -> score >= MIN_SCORE }
            .sortedByDescending { (_, score) -> score }
            .map { (node, _) -> describe(node) }
            .distinct()
            .take(limit)
    }

    private fun score(wanted: Selector, sayWords: Set<String>, node: UiNode): Double {
        var best = 0.0
        val wantedId = wanted.id
        val id = node.id
        if (wantedId != null && id != null) {
            val shared = sharedPrefixSegments(wantedId, id)
            best = maxOf(best, similarity(wantedId.lowercase(), id.lowercase()) + SEGMENT_BONUS * shared)
        }
        val text = node.text?.normalizedSpaces()
        if (text != null && text.isNotEmpty()) {
            wanted.text?.normalizedSpaces()?.let { best = maxOf(best, similarity(it.lowercase(), text.lowercase())) }
            wanted.textContains?.normalizedSpaces()?.let {
                if (text.contains(it, ignoreCase = true)) best = maxOf(best, CONTAINS_SCORE)
            }
            // a clickable element whose text is a word of the step's intent: "Continue on the welcome page"
            if (node.actions.isNotEmpty() && words(text).any { it in sayWords }) {
                best = maxOf(best, SAY_SCORE)
            }
        }
        return best
    }

    private fun sharedPrefixSegments(a: String, b: String): Int {
        val left = a.split('.')
        val right = b.split('.')
        return left.zip(right).takeWhile { (x, y) -> x.equals(y, ignoreCase = true) }.size
    }

    private fun words(text: String): Set<String> {
        return Regex("""[\p{L}\p{N}]{4,}""").findAll(text.lowercase()).map { it.value }.toSet()
    }

    /** 1.0 for equal strings, towards 0.0 the more edits it takes to turn one into the other. */
    internal fun similarity(a: String, b: String): Double {
        if (a == b) return 1.0
        val longest = maxOf(a.length, b.length)
        return if (longest == 0) 1.0 else 1.0 - editDistance(a, b).toDouble() / longest
    }

    private fun editDistance(a: String, b: String): Int {
        var previous = IntArray(b.length + 1) { it }
        for (i in 1..a.length) {
            val current = IntArray(b.length + 1)
            current[0] = i
            for (j in 1..b.length) {
                val substitution = previous[j - 1] + if (a[i - 1] == b[j - 1]) 0 else 1
                current[j] = minOf(previous[j] + 1, current[j - 1] + 1, substitution)
            }
            previous = current
        }
        return previous[b.length]
    }

    private fun flatten(node: UiNode): List<UiNode> {
        return listOf(node) + node.children.flatMap { flatten(it) }
    }

    private fun describe(node: UiNode): String {
        val parts =
            listOfNotNull(node.id?.let { "id=$it" }, node.text?.takeIf { it.isNotBlank() }?.let { "text=\"$it\"" })
        return parts.joinToString(" ")
    }

    private const val MIN_SCORE = 0.6
    private const val SEGMENT_BONUS = 0.1
    private const val CONTAINS_SCORE = 0.8
    private const val SAY_SCORE = 0.65
}
