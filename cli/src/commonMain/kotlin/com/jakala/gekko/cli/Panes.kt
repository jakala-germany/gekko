package com.jakala.gekko.cli

import com.charleskorn.kaml.YamlList
import com.charleskorn.kaml.YamlMap
import com.charleskorn.kaml.YamlNode
import com.charleskorn.kaml.YamlScalar
import com.charleskorn.kaml.YamlTaggedNode
import com.jakala.gekko.protocol.Selector
import com.jakala.gekko.protocol.UiNode
import com.jakala.gekko.protocol.findAll
import com.jakala.gekko.protocol.isOnScreen

/** One element of one pane, e.g. pane `auth/login` and element `submit`. */
data class ElementRef(val pane: String, val element: String) {
    val qualified: String
        get() {
            return "$pane.$element"
        }
}

/** What the tests and panes of a project use; `gekko check` reports the rest as unused. */
class PaneUsage {
    val elements = mutableSetOf<String>()
    val actions = mutableSetOf<String>()
    val panes = mutableSetOf<String>()
}

/**
 * The panes of a project: every `*.yaml` below [dir]. A pane is read when it is first needed; a pane file that is
 * broken is reported with its path when something refers to it, and by `gekko check`.
 */
class Panes(
    private val dir: String,
    private val folder: String,
    /** The text of a pane file with `${env.X}` filled in and the parameters of [params] filled in, the rest kept. */
    private val text: (path: String, params: Map<String, String>) -> String,
) {
    /** When set, everything that resolves a reference records it here (`gekko check`). */
    var usage: PaneUsage? = null

    private class Loaded(val path: String, val file: PaneFile?, val problem: String?)

    private val loaded: Map<String, Loaded> by lazy { loadAll() }

    private val naming = mutableListOf<String>()

    /** The names of all panes, also of the ones that are broken. */
    val names: List<String>
        get() {
            return loaded.keys.sorted()
        }

    val isEmpty: Boolean
        get() {
            return loaded.isEmpty()
        }

    /** Pane files that cannot be read or have a name that cannot be referred to. */
    fun problems(): List<String> {
        val parse = loaded.values.mapNotNull { it.problem }
        return naming + parse
    }

    fun exists(name: String): Boolean {
        return name in loaded
    }

    fun pathOf(name: String): String {
        return loaded[name]?.path ?: "$dir/$name.yaml"
    }

    /** The pane [name]; an error with a hint when there is none, or with the problem of the file when it is broken. */
    fun pane(name: String): PaneFile {
        val entry = loaded[name] ?: throw IllegalArgumentException(unknownPane(name))
        return entry.file ?: throw IllegalArgumentException(entry.problem)
    }

    /** Every pane that can be read, by name. */
    fun readable(): Map<String, PaneFile> {
        return loaded.mapNotNull { (name, entry) -> entry.file?.let { name to it } }.toMap()
    }

    private fun loadAll(): Map<String, Loaded> {
        if (!fileExists(dir)) return emptyMap()
        val result = linkedMapOf<String, Loaded>()
        val files = listFilesRecursive(dir).filter { it.endsWith(".yaml") || it.endsWith(".yml") }.sorted()
        for (path in files) {
            val name = path.removePrefix("$dir/").removeSuffix(".yaml").removeSuffix(".yml")
            if (!PANE_NAME.matches(name)) {
                naming += "$path: a pane name is letters, digits, _ and - (and / for folders), not '$name'"
                continue
            }
            if (name in result) {
                naming += "$path: there is already a pane '$name' (${result.getValue(name).path})"
                continue
            }
            result[name] =
                try {
                    Loaded(path, read(path), null)
                } catch (error: Exception) {
                    Loaded(path, null, "$path: ${error.message?.lineSequence()?.firstOrNull() ?: error}")
                }
        }
        return result
    }

    private fun read(path: String): PaneFile {
        val source = text(path, emptyMap())
        val file = Yamls.parse(PaneFile.serializer(), source)
        checkPlaceholders(source, file)
        return file
    }

    /** Elements take no parameters, an action only the ones it declares. */
    private fun checkPlaceholders(source: String, file: PaneFile) {
        val root = Yamls.yaml.parseToYamlNode(source) as? YamlMap ?: return
        fun found(node: YamlNode?): Set<String> {
            val names = mutableSetOf<String>()
            collect(node, names)
            return names.filterNot { it.startsWith("var.") || it.startsWith("random.") }.toSet()
        }
        for (section in listOf("identity", "elements")) {
            val extra = found(root.get<YamlNode>(section))
            require(extra.isEmpty()) { "'\${${extra.first()}}' in $section: only actions take parameters" }
        }
        val actions = root.get<YamlMap>("actions") ?: return
        for ((key, node) in actions.entries) {
            val declared = file.actions[key.content]?.params.orEmpty()
            val extra = found(node).filterNot { it in declared }
            require(extra.isEmpty()) {
                "'\${${extra.first()}}' in action '${key.content}' is not one of its params (params: [${declared.joinToString()}])"
            }
        }
    }

    private fun collect(node: YamlNode?, into: MutableSet<String>) {
        when (node) {
            is YamlScalar -> PLACEHOLDER.findAll(node.content).forEach { into += it.groupValues[1] }
            is YamlMap ->
                node.entries.forEach { (key, value) ->
                    collect(key, into)
                    collect(value, into)
                }
            is YamlList -> node.items.forEach { collect(it, into) }
            is YamlTaggedNode -> collect(node.innerNode, into)
            else -> Unit
        }
    }

    /** `checkout/payment.card.number` is the pane `checkout/payment` and the element `card.number`. */
    fun splitRef(text: String): Pair<String, String>? {
        val slash = text.lastIndexOf('/')
        val dot = text.indexOf('.', slash + 1)
        if (dot <= slash + 1 || dot == text.length - 1) return null
        return text.substring(0, dot) to text.substring(dot + 1)
    }

    /**
     * The element a reference names. Inside a pane ([scope]) a name that is an element of that pane means it; a name
     * that is the element of another pane is written with the pane (`tabbar.home`). A name that could be both is an
     * error: nothing is resolved silently.
     */
    fun resolveElement(text: String, scope: String?): ElementRef {
        val own = scope?.takeIf { text in pane(it).elements }?.let { ElementRef(it, text) }
        val qualified =
            splitRef(text)
                ?.takeIf { (name, element) -> exists(name) && element in pane(name).elements }
                ?.let { ElementRef(it.first, it.second) }
        if (own != null && qualified != null) {
            error(
                "'$text' is ambiguous in pane '$scope': the element '${own.qualified}' and the element '${qualified.qualified}'; rename one of them"
            )
        }
        val found = own ?: qualified ?: throw IllegalArgumentException(unknownElement(text, scope))
        usage?.let {
            it.elements += found.qualified
            it.panes += found.pane
        }
        return found
    }

    private fun unknownElement(text: String, scope: String?): String {
        val split = splitRef(text)
        val own = scope?.let { pane(it).elements.keys }.orEmpty()
        if (split != null && exists(split.first)) {
            val elements = pane(split.first).elements.keys
            val hint = Yamls.closest(split.second, elements)?.let { " Did you mean '${split.first}.$it'?" }.orEmpty()
            return "pane '${split.first}' has no element '${split.second}'.$hint (elements: ${elements.joinToString()})"
        }
        if (scope != null && split == null) {
            val hint = Yamls.closest(text, own)?.let { " Did you mean '$it'?" }.orEmpty()
            return "pane '$scope' has no element '$text'.$hint (elements: ${own.joinToString()})"
        }
        if (split != null) {
            return unknownPane(split.first) + (scope?.let { " (and pane '$scope' has no element '$text')" } ?: "")
        }
        return "'$text' is not an element: write the pane and the element, like <pane>.<element>"
    }

    private fun unknownPane(name: String): String {
        if (loaded.isEmpty()) return "unknown pane '$name': there is no pane in $folder/"
        val hint = Yamls.closest(name, loaded.keys)?.let { " Did you mean '$it'?" }.orEmpty()
        return "unknown pane '$name'.$hint (panes: ${loaded.keys.sorted().joinToString()})"
    }

    /** True when [name] is an action of a pane that exists; `name` is written `<pane>.<action>`. */
    fun hasAction(pane: String, name: String): Boolean {
        return exists(pane) && name in pane(pane).actions
    }

    /** The message for a `use:` that names no flow: when it looks like a pane action, says what is missing. */
    fun unknownActionHint(name: String): String {
        val split = splitRef(name) ?: return ""
        if (exists(split.first)) {
            val actions = pane(split.first).actions.keys
            val hint = Yamls.closest(split.second, actions)?.let { " Did you mean '${split.first}.$it'?" }.orEmpty()
            return " and pane '${split.first}' has no action '${split.second}'.$hint (actions: ${actions.joinToString()})"
        }
        if (loaded.isEmpty()) return ""
        val hint = Yamls.closest(split.first, loaded.keys)?.let { " Did you mean the pane '$it'?" }.orEmpty()
        return " and there is no pane '${split.first}'.$hint"
    }

    /** The steps of the action [name] of [pane], with the values of [with] filled in for its params. */
    fun action(pane: String, name: String, with: Map<String, String>): List<Step> {
        val declared = pane(pane).actions[name] ?: error("pane '$pane' has no action '$name'")
        val missing = declared.params.filter { it !in with }
        require(missing.isEmpty()) { "Missing parameter '${missing.first()}' for the action '$pane.$name'" }
        val unknown = with.keys.filter { it !in declared.params }
        require(unknown.isEmpty()) {
            "'${unknown.first()}' is not a param of the action '$pane.$name' (params: [${declared.params.joinToString()}])"
        }
        usage?.let {
            it.actions += "$pane.$name"
            it.panes += pane
        }
        val filled = Yamls.parse(PaneFile.serializer(), text(pathOf(pane), with))
        return filled.actions.getValue(name).steps
    }

    /** The selectors that show [pane] on [platform]: all of them are visible when the pane is on screen. */
    fun identity(pane: String, platform: Platform): List<Selector> {
        val file = pane(pane)
        require(file.identity.isNotEmpty()) {
            "pane '$pane' has no identity; add identity: [...] with elements of the pane to use it as a screen check"
        }
        usage?.panes?.add(pane)
        val expander = PaneExpander(this, platform, pane)
        return file.identity.map { expander.elementSpec(ElementRef(pane, it)).toSelector(platform) }
    }

    /**
     * The panes whose identity is on the screen; a pane without identity, or with a gap for [platform], is left out.
     */
    fun current(windows: List<UiNode>, platform: Platform): List<String> {
        return readable()
            .filter { it.value.identity.isNotEmpty() }
            .keys
            .filter { name ->
                val selectors = runCatching { identity(name, platform) }.getOrNull() ?: return@filter false
                selectors.all { selector ->
                    windows.any { window -> window.findAll(selector).any { it.isOnScreen(window.bounds) } }
                }
            }
    }

    /** Every element of every pane that can be read, as the selector it is on [platform]. */
    fun selectors(platform: Platform): List<Pair<ElementRef, Selector>> {
        val result = mutableListOf<Pair<ElementRef, Selector>>()
        for ((name, file) in readable()) {
            val expander = PaneExpander(this, platform, name)
            for (element in file.elements.keys) {
                val ref = ElementRef(name, element)
                val selector = runCatching { expander.elementSpec(ref).toSelector(platform) }.getOrNull() ?: continue
                result += ref to selector
            }
        }
        return result
    }

    /**
     * Checks that every pane reference of [steps] resolves on [platforms] (an element without a selector for a platform
     * the step runs on is an error, like an id that exists for one platform only).
     */
    fun validate(steps: List<Step>, scope: String?, platforms: List<Platform>) {
        for (step in steps) {
            for (platform in platforms) {
                if (!step.runsOn(platform)) continue
                val expander = PaneExpander(this, platform, scope)
                if (step.action.runsOn(platform)) {
                    expander.action(step.action)
                    step.condition?.let { expander.condition(it) }
                }
                step.until?.takeIf { it.runsOn(platform) }?.let { expander.action(it) }
            }
            val repeat = step.action.repeat ?: continue
            validate(repeat.steps, scope, platforms)
            val repeating = repeat.whileCondition ?: continue
            platforms.forEach { PaneExpander(this, it, scope).condition(repeating) }
        }
    }

    private companion object {
        val PANE_NAME = Regex("[A-Za-z0-9_-]+(/[A-Za-z0-9_-]+)*")
        val PLACEHOLDER = Regex("""\$\{([A-Za-z0-9_.]+)}""")
    }
}

/** The selector keys of a spec, without the checks of its constructor: what a reference and the step add up to. */
private data class SelectorFields(
    val id: PlatformId?,
    val text: String?,
    val textContains: String?,
    val textMatches: String?,
    val action: String?,
    val within: PlatformId?,
    val index: Int,
    val enabled: Boolean?,
    val checked: Boolean?,
    val selected: Boolean?,
    val system: Boolean,
)

private fun SelectorSpec.fields(): SelectorFields {
    return SelectorFields(
        id,
        text,
        textContains,
        textMatches,
        action,
        within,
        index,
        enabled,
        checked,
        selected,
        system,
    )
}

private fun TypeSpec.fields(): SelectorFields {
    return SelectorFields(id, text, textContains, textMatches, action, within, index, enabled, checked, selected, false)
}

private fun ScrollSpec.fields(): SelectorFields {
    return SelectorFields(id, text, textContains, textMatches, action, within, index, enabled, checked, selected, false)
}

private fun ScrollUntilSpec.fields(): SelectorFields {
    return SelectorFields(id, text, textContains, textMatches, action, within, index, enabled, checked, selected, false)
}

private fun LongPressSpec.fields(): SelectorFields {
    return SelectorFields(
        id,
        text,
        textContains,
        textMatches,
        action,
        within,
        index,
        enabled,
        checked,
        selected,
        system,
    )
}

/**
 * Turns the pane references of steps into the selectors they stand for on one platform. A reference is the element's
 * selector with the keys of the step added; where both set a key the step wins (an `index` of 0 is the default, so the
 * element's own `index` stays then).
 */
internal class PaneExpander(private val panes: Panes, private val platform: Platform, private val scope: String?) {
    /** The selector of an element on this platform, with a `within` that names an element turned into its id. */
    fun elementSpec(ref: ElementRef): SelectorSpec {
        val file = panes.pane(ref.pane)
        val element = file.elements.getValue(ref.element)
        val spec =
            element.resolve(platform)
                ?: error(
                    "the element '${ref.qualified}' has no selector for ${platform.key}; add ${platform.key}: to it or limit the step with platform:"
                )
        val within = spec.within?.resolve(platform) ?: return spec
        val target = file.elements[within] ?: return spec
        val targetSpec =
            target.resolve(platform)
                ?: error(
                    "the element '${ref.qualified}' is within '$within', which has no selector for ${platform.key}"
                )
        require(targetSpec.isIdOnly()) {
            "the element '${ref.qualified}' is within '$within', which must be an element that has only an id"
        }
        panes.usage?.elements?.add("${ref.pane}.$within")
        return spec.copy(within = PlatformId(shared = targetSpec.id?.resolve(platform)))
    }

    private fun SelectorSpec.isIdOnly(): Boolean {
        return id != null &&
            text == null &&
            textContains == null &&
            textMatches == null &&
            action == null &&
            within == null &&
            index == 0 &&
            enabled == null &&
            checked == null &&
            selected == null &&
            !system
    }

    private fun merged(reference: String, over: SelectorFields): SelectorFields {
        require(over.id == null) { "'$reference' is an element: it takes no id of its own" }
        val base = elementSpec(panes.resolveElement(reference, scope))
        return SelectorFields(
            id = base.id,
            text = over.text ?: base.text,
            textContains = over.textContains ?: base.textContains,
            textMatches = over.textMatches ?: base.textMatches,
            action = over.action ?: base.action,
            within = over.within ?: base.within,
            index = if (over.index != 0) over.index else base.index,
            enabled = over.enabled ?: base.enabled,
            checked = over.checked ?: base.checked,
            selected = over.selected ?: base.selected,
            system = over.system || base.system,
        )
    }

    fun selector(spec: SelectorSpec): SelectorSpec {
        val reference = spec.element ?: return spec
        require(spec.point == null) { "'$reference' is an element: it cannot be combined with point" }
        val m = merged(reference, spec.fields())
        return spec.copy(
            id = m.id,
            text = m.text,
            textContains = m.textContains,
            textMatches = m.textMatches,
            action = m.action,
            within = m.within,
            index = m.index,
            enabled = m.enabled,
            checked = m.checked,
            selected = m.selected,
            system = m.system,
            element = null,
        )
    }

    private fun requireNotSystem(reference: String, fields: SelectorFields, action: String) {
        require(!fields.system) { "the element '$reference' needs the system runner, which $action does not take" }
    }

    fun type(spec: TypeSpec): TypeSpec {
        val reference = spec.element ?: return spec
        val m = merged(reference, spec.fields())
        requireNotSystem(reference, m, "type")
        return spec.copy(
            id = m.id,
            text = m.text,
            textContains = m.textContains,
            textMatches = m.textMatches,
            action = m.action,
            within = m.within,
            index = m.index,
            enabled = m.enabled,
            checked = m.checked,
            selected = m.selected,
            element = null,
        )
    }

    fun scroll(spec: ScrollSpec): ScrollSpec {
        val reference = spec.element ?: return spec
        val m = merged(reference, spec.fields())
        requireNotSystem(reference, m, "scroll")
        return spec.copy(
            id = m.id,
            text = m.text,
            textContains = m.textContains,
            textMatches = m.textMatches,
            action = m.action,
            within = m.within,
            index = m.index,
            enabled = m.enabled,
            checked = m.checked,
            selected = m.selected,
            element = null,
        )
    }

    fun scrollUntilVisible(spec: ScrollUntilSpec): ScrollUntilSpec {
        val reference = spec.element
        val container = spec.container?.let(::selector)
        if (reference == null) return spec.copy(container = container)
        val m = merged(reference, spec.fields())
        requireNotSystem(reference, m, "scrollUntilVisible")
        return spec.copy(
            id = m.id,
            text = m.text,
            textContains = m.textContains,
            textMatches = m.textMatches,
            action = m.action,
            within = m.within,
            index = m.index,
            enabled = m.enabled,
            checked = m.checked,
            selected = m.selected,
            container = container,
            element = null,
        )
    }

    fun longPress(spec: LongPressSpec): LongPressSpec {
        val reference = spec.element ?: return spec
        val m = merged(reference, spec.fields())
        return spec.copy(
            id = m.id,
            text = m.text,
            textContains = m.textContains,
            textMatches = m.textMatches,
            action = m.action,
            within = m.within,
            index = m.index,
            enabled = m.enabled,
            checked = m.checked,
            selected = m.selected,
            system = m.system,
            element = null,
        )
    }

    fun condition(condition: Condition): Condition {
        condition.pane?.let { panes.identity(it, platform) }
        return Condition(condition.visible?.let(::selector), condition.notVisible?.let(::selector), condition.pane)
    }

    fun action(action: Action): Action {
        action.assertPane?.let { panes.identity(it, platform) }
        return action.copy(
            tap = action.tap?.let(::selector),
            type = action.type?.let(::type),
            scroll = action.scroll?.let(::scroll),
            swipe = action.swipe?.let { swipe -> swipe.copy(from = swipe.from?.let(::selector)) },
            drag = action.drag?.let { drag -> drag.copy(from = drag.from?.let(::selector)) },
            assertVisible = action.assertVisible?.let(::selector),
            assertNotVisible = action.assertNotVisible?.let(::selector),
            doubleTap = action.doubleTap?.let(::selector),
            longPress = action.longPress?.let(::longPress),
            scrollUntilVisible = action.scrollUntilVisible?.let(::scrollUntilVisible),
            eraseText = action.eraseText?.let(::selector),
            copyText = action.copyText?.let { copy -> copy.copy(from = selector(copy.from)) },
        )
    }

    /** The step as it runs on this platform: its references are selectors. The `until` of another platform is left. */
    fun step(step: Step): Step {
        return step.copy(
            action = action(step.action),
            until = step.until?.let { if (it.runsOn(platform)) action(it) else it },
            condition = step.condition?.let(::condition),
        )
    }
}

/** The references a step carries, as written: `auth/login.submit`, for the log and the heal report. */
internal fun Step.elementReferences(): List<String> {
    return listOfNotNull(action, until).flatMap { it.elementReferences() } +
        listOfNotNull(condition?.visible?.element, condition?.notVisible?.element)
}

internal fun Action.elementReferences(): List<String> {
    return listOfNotNull(
        tap?.element,
        type?.element,
        scroll?.element,
        swipe?.from?.element,
        drag?.from?.element,
        assertVisible?.element,
        assertNotVisible?.element,
        doubleTap?.element,
        longPress?.element,
        scrollUntilVisible?.element,
        scrollUntilVisible?.container?.element,
        eraseText?.element,
        copyText?.from?.element,
    )
}
