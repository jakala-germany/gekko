package com.jakala.gekko.cli

import com.charleskorn.kaml.YamlInput
import com.charleskorn.kaml.YamlMap
import com.charleskorn.kaml.YamlNode
import kotlinx.serialization.InternalSerializationApi
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.descriptors.SerialKind
import kotlinx.serialization.descriptors.buildSerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder

/**
 * A pane: one screen of the app, written once in `panes/<name>.yaml` and used by every test. The name of a pane is its
 * path below the panes folder (`panes/checkout/payment.yaml` is `checkout/payment`).
 */
@Serializable
data class PaneFile(
    /** The elements that show the pane is on screen; all of them must be visible. */
    val identity: List<String> = emptyList(),
    val elements: Map<String, PaneElement> = emptyMap(),
    val actions: Map<String, PaneAction> = emptyMap(),
) {
    init {
        elements.keys.forEach { name ->
            require(ELEMENT_NAME.matches(name)) {
                "element name '$name' must be letters, digits, _, - and . (no leading, trailing or double dot)"
            }
        }
        actions.keys.forEach { name ->
            require(ACTION_NAME.matches(name)) { "action name '$name' must be letters, digits and _" }
        }
        identity.forEach { name ->
            require(name in elements) { "identity names '$name', which is not an element of this pane" }
        }
        require(identity.distinct().size == identity.size) { "identity lists an element twice" }
    }
}

private val ELEMENT_NAME = Regex("[A-Za-z0-9_-]+(\\.[A-Za-z0-9_-]+)*")
private val ACTION_NAME = Regex("[A-Za-z0-9_]+")

/** A named list of steps of a pane, called with `use: <pane>.<name>`; [params] are filled in by `with:`. */
@Serializable
data class PaneAction(val params: List<String> = emptyList(), val steps: List<Step>) {
    init {
        require(steps.isNotEmpty()) { "an action needs at least one step" }
        params.forEach { name -> require(PARAM_NAME.matches(name)) { "param '$name' must be letters, digits and _" } }
        require(params.distinct().size == params.size) { "params lists a name twice" }
    }
}

private val PARAM_NAME = Regex("[A-Za-z0-9_]+")

/**
 * One element of a pane: a selector that is the same everywhere (`{ id: Login.Email }`) or one per platform (`{
 * android: { id: a }, ios: { text: Sign in } }`).
 */
@Serializable(with = PaneElementSerializer::class)
data class PaneElement(
    val shared: SelectorSpec? = null,
    val android: SelectorSpec? = null,
    val ios: SelectorSpec? = null,
    val web: SelectorSpec? = null,
) {
    init {
        require(shared != null || android != null || ios != null || web != null) {
            "an element needs a selector or android, ios, web"
        }
        listOfNotNull(shared, android, ios, web).forEach { spec ->
            require(spec.element == null) { "an element cannot refer to another element" }
            require(spec.point == null) { "an element cannot be a point of the screen" }
        }
    }

    /** The selector for [platform], or null when the element has none there. */
    fun resolve(platform: Platform): SelectorSpec? {
        return when (platform) {
            Platform.Android -> android ?: shared
            Platform.Ios -> ios ?: shared
            Platform.Web -> web ?: shared
        }
    }
}

object PaneElementSerializer : KSerializer<PaneElement> {
    @OptIn(InternalSerializationApi::class)
    override val descriptor: SerialDescriptor = buildSerialDescriptor("PaneElement", SerialKind.CONTEXTUAL)

    override fun deserialize(decoder: Decoder): PaneElement {
        val node = (decoder as YamlInput).node
        val map = node as? YamlMap ?: error("an element is a selector or { android, ios, web } at ${node.where()}")
        val keys = map.entries.keys.map { it.content }
        val platformKeys = keys.filter { it in PLATFORM_KEYS }
        if (platformKeys.isEmpty()) {
            return PaneElement(shared = selector(map))
        }
        require(platformKeys.size == keys.size) {
            "an element is either a selector or one selector per platform, not both, at ${node.where()}"
        }
        fun forPlatform(key: String): SelectorSpec? {
            val entry = map.entries.entries.firstOrNull { it.key.content == key }?.value ?: return null
            return selector(entry)
        }
        return PaneElement(android = forPlatform("android"), ios = forPlatform("ios"), web = forPlatform("web"))
    }

    private fun selector(node: YamlNode): SelectorSpec {
        require(node is YamlMap) { "a selector is a map such as { id: Login.Email } at ${node.where()}" }
        return Yamls.yaml.decodeFromYamlNode(SelectorSpec.serializer(), node)
    }

    private fun YamlNode.where(): String {
        return path.toHumanReadableString()
    }

    override fun serialize(encoder: Encoder, value: PaneElement) {
        encoder.encodeString(value.toString())
    }
}

/** The platform names a pane element may be written for. */
private val PLATFORM_KEYS = Platform.entries.map { it.key }
