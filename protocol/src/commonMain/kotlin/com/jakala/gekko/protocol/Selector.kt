package com.jakala.gekko.protocol

import kotlinx.serialization.Serializable

@Serializable
data class Selector(
    val id: String? = null,
    val text: String? = null,
    /** Substring match on the visible text (whitespace-normalized); for text with changing parts. */
    val textContains: String? = null,
    /**
     * The whole visible text matches this regular expression (case-insensitive, `.` also matches line breaks) or equals
     * it literally, so a pattern like `Wohin möchtest du?` still finds that very text.
     */
    val textMatches: String? = null,
    /** The node must offer this action (click, setText, scroll). */
    val action: String? = null,
    /** Only nodes below a node with this id match, e.g. the Nth input inside a dialog. */
    val within: String? = null,
    val index: Int = 0,
    /** Only enabled (true) or only disabled (false) nodes match. */
    val enabled: Boolean? = null,
    /** Only checked (true) or unchecked (false) switches, checkboxes and radio buttons match. */
    val checked: Boolean? = null,
    /** Only selected (true) or unselected (false) tabs, chips and segments match. */
    val selected: Boolean? = null,
) {
    private val textRegex: Regex? by
        lazy(LazyThreadSafetyMode.NONE) { textMatches?.let { Regex("(?s)$it", RegexOption.IGNORE_CASE) } }

    /** Only the parts that are set: `Selector(id=Search.Field, index=1)`, as it appears in messages and reports. */
    override fun toString(): String {
        val parts =
            listOfNotNull(
                id?.let { "id=$it" },
                text?.let { "text=$it" },
                textContains?.let { "textContains=$it" },
                textMatches?.let { "textMatches=$it" },
                action?.let { "action=$it" },
                within?.let { "within=$it" },
                index.takeIf { it != 0 }?.let { "index=$it" },
                enabled?.let { "enabled=$it" },
                checked?.let { "checked=$it" },
                selected?.let { "selected=$it" },
            )
        return parts.joinToString(", ", "Selector(", ")")
    }

    val isEmpty: Boolean
        get() = id == null && text == null && textContains == null && textMatches == null && action == null

    /** Node-local checks; `within` needs the tree and is handled by [findAll] and the agents. */
    fun matches(node: UiNode): Boolean {
        val idMatches = id == null || node.id == id
        val texts = listOfNotNull(node.text, node.description).map { it.normalizedSpaces() }
        val exactMatches = text == null || texts.any { it == text.normalizedSpaces() }
        val containsMatches = textContains == null || texts.any { it.contains(textContains.normalizedSpaces()) }
        val regex = textRegex
        val regexMatches = regex == null || texts.any { regex.matches(it) || it == textMatches?.normalizedSpaces() }
        val actionMatches = action == null || action in node.actions
        val enabledMatches = enabled == null || node.enabled == enabled
        val checkedMatches = checked == null || node.checked == checked
        val selectedMatches = selected == null || node.selected == selected
        return !isEmpty &&
            idMatches &&
            exactMatches &&
            containsMatches &&
            regexMatches &&
            actionMatches &&
            enabledMatches &&
            checkedMatches &&
            selectedMatches
    }
}

fun UiNode.find(selector: Selector): UiNode? {
    return findAll(selector).getOrNull(selector.index)
}

fun UiNode.findAll(selector: Selector): List<UiNode> {
    val scope = selector.within
    if (scope != null) {
        val containers = findAll(Selector(id = scope))
        return containers.flatMap { container ->
            container.children.flatMap { it.findAll(selector.copy(within = null)) }
        }
    }
    val own = if (selector.matches(this)) listOf(this) else emptyList()
    return own + children.flatMap { it.findAll(selector) }
}

/** UI strings often contain non-breaking spaces ("20 Std."); tests are written with plain spaces. */
fun String.normalizedSpaces(): String {
    return map { if (it.isWhitespace() || it == ' ' || it == ' ') ' ' else it }.joinToString("").trim()
}
