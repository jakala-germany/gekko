package com.jakala.gekko.agent

import android.view.View
import androidx.compose.ui.InternalComposeUiApi
import androidx.compose.ui.platform.ViewRootForTest
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.ScrollAxisRange
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsConfiguration
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsOwner
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.LayoutDirection
import com.jakala.gekko.device.NodeFacts
import com.jakala.gekko.protocol.Bounds
import com.jakala.gekko.protocol.UiNode

@OptIn(InternalComposeUiApi::class)
internal object ComposeReader {
    fun isAvailable(): Boolean {
        return runCatching { ViewRootForTest::class.java }.isSuccess
    }

    /** The view that hosts a composition: it has the view focus whenever something inside it has the Compose focus. */
    fun isHost(view: View): Boolean {
        return view is ViewRootForTest
    }

    fun read(view: View, parent: Element, root: View, clip: Bounds, sink: MutableList<Element>): Element? {
        val forTest = view as? ViewRootForTest ?: return null
        val origin = IntArray(2)
        view.getLocationOnScreen(origin)
        val owner = forTest.semanticsOwner
        val unmerged = mutableMapOf<Int, SemanticsNode>()
        indexById(owner.unmergedRootSemanticsNode, unmerged)
        return readNode(owner, owner.rootSemanticsNode, parent, root, origin, clip, sink, unmerged)
    }

    private fun indexById(node: SemanticsNode, into: MutableMap<Int, SemanticsNode>) {
        into[node.id] = node
        node.children.forEach { indexById(it, into) }
    }

    private fun readNode(
        owner: SemanticsOwner,
        node: SemanticsNode,
        parent: Element,
        root: View,
        origin: IntArray,
        clip: Bounds,
        sink: MutableList<Element>,
        unmerged: Map<Int, SemanticsNode>?,
    ): Element {
        val config = node.config
        val rect = node.boundsInRoot
        val clickAction = config.getOrNull(SemanticsActions.OnClick)?.action
        val setTextAction = config.getOrNull(SemanticsActions.SetText)?.action
        val scrollAction = config.getOrNull(SemanticsActions.ScrollBy)?.action
        val focusAction = config.getOrNull(SemanticsActions.RequestFocus)?.action
        val secure = config.contains(SemanticsProperties.Password)
        val focused = root.hasWindowFocus() && config.getOrNull(SemanticsProperties.Focused) == true
        val editable = config.getOrNull(SemanticsProperties.EditableText)?.text
        val texts = config.getOrNull(SemanticsProperties.Text)?.map { it.text }
        val description = config.getOrNull(SemanticsProperties.ContentDescription)?.joinToString(" ")
        // A password field never hands out what was typed, neither as text nor through its description
        val text = if (secure) null else editable ?: texts?.joinToString(" ") ?: description
        val rtl = node.layoutInfo.layoutDirection == LayoutDirection.Rtl
        val actions = buildList {
            if (clickAction != null) add("click")
            if (setTextAction != null) add("setText")
            if (scrollAction != null) add("scroll")
        }
        val element =
            Element(
                node =
                    UiNode(
                        id = config.getOrNull(SemanticsProperties.TestTag),
                        text = text,
                        description =
                            if (secure) NodeFacts.safeDescription(description, listOf(editable) + texts.orEmpty())
                            else description,
                        role = config.getOrNull(SemanticsProperties.Role)?.toString(),
                        bounds =
                            Bounds(
                                origin[0] + rect.left.toInt(),
                                origin[1] + rect.top.toInt(),
                                origin[0] + rect.right.toInt(),
                                origin[1] + rect.bottom.toInt(),
                            ),
                        actions = actions,
                        enabled = config.getOrNull(SemanticsProperties.Disabled) == null,
                        checked =
                            config.getOrNull(SemanticsProperties.ToggleableState)?.let { it == ToggleableState.On },
                        selected = config.getOrNull(SemanticsProperties.Selected),
                        secure = secure,
                        busy = isBusy(config.getOrNull(SemanticsProperties.ProgressBarRangeInfo)),
                        focused = focused,
                    ),
                parent = parent,
                rootView = root,
                // Editable fields need real focus first: apps often route typed text by the focused field.
                click =
                    clickAction?.let { action ->
                        {
                            if (setTextAction != null) focusAction?.invoke()
                            action()
                        }
                    } ?: if (setTextAction != null && focusAction != null) ({ focusAction() }) else null,
                setText =
                    setTextAction?.let { action ->
                        { value: String ->
                            focusAction?.invoke()
                            action(AnnotatedString(value))
                        }
                    },
                scrollBy =
                    scrollAction?.let { action ->
                        scroller(
                            action,
                            config.getOrNull(SemanticsProperties.HorizontalScrollAxisRange),
                            config.getOrNull(SemanticsProperties.VerticalScrollAxisRange),
                            rtl,
                        )
                    },
                clip = clip,
                rtl = rtl,
                textInput = if (focused) textInputOf(owner, node, config) else null,
            )
        sink += element
        // A lazy list keeps rows that scrolled out of view, but does not place them: their bounds are still the ones
        // from when they were shown, so they would count as on screen. Compose's own accessibility skips them too.
        node.children
            .filter { it.layoutInfo.isPlaced }
            .forEach { child ->
                element.children += readNode(owner, child, element, root, origin, clip, sink, unmerged)
            }
        // A node that merges its descendants (a button, a list row) hides the texts inside it in this tree;
        // accessibility
        // services and other UI-test tools see them as nodes of their own, so they are added below it
        val own = if (unmerged != null && node.config.isMergingSemanticsOfDescendants) unmerged[node.id] else null
        own?.children
            ?.filter { it.layoutInfo.isPlaced }
            ?.forEach { child -> element.children += readNode(owner, child, element, root, origin, clip, sink, null) }
        return element
    }

    private fun textInputOf(owner: SemanticsOwner, node: SemanticsNode, config: SemanticsConfiguration): TextInput? {
        val insertAction = insertAction(config)
        val setText = config.getOrNull(SemanticsActions.SetText)?.action
        if (insertAction == null && setText == null) {
            return null
        }
        val id = node.id
        return TextInput(
            insert = { text ->
                when {
                    insertAction != null -> insertAction(AnnotatedString(text))
                    setText != null -> setText(AnnotatedString(splice(config, text)))
                    else -> false
                }
            },
            current = { currentText(owner, id) },
        )
    }

    /** Compose is `compileOnly`: an app on a Compose that has no such action gets the fallback of [splice]. */
    private fun insertAction(config: SemanticsConfiguration): ((AnnotatedString) -> Boolean)? {
        return try {
            config.getOrNull(SemanticsActions.InsertTextAtCursor)?.action
        } catch (error: LinkageError) {
            null
        }
    }

    /** The text of the field with [text] in place of its selection, or at its end when it does not report one. */
    private fun splice(config: SemanticsConfiguration, text: String): String {
        val current = config.getOrNull(SemanticsProperties.EditableText)?.text.orEmpty()
        val selection = config.getOrNull(SemanticsProperties.TextSelectionRange)
        val start = (selection?.min ?: current.length).coerceIn(0, current.length)
        val end = (selection?.max ?: current.length).coerceIn(start, current.length)
        return current.replaceRange(start, end, text)
    }

    /** What the field holds now; a node of an earlier read keeps the text it had then, so it is looked up again. */
    private fun currentText(owner: SemanticsOwner, id: Int): String? {
        val nodes = mutableMapOf<Int, SemanticsNode>()
        indexById(owner.unmergedRootSemanticsNode, nodes)
        indexById(owner.rootSemanticsNode, nodes)
        return nodes[id]?.config?.getOrNull(SemanticsProperties.EditableText)?.text
    }

    /**
     * Only a progress indicator without a value keeps the screen busy; a bar that shows a value or a slider does not.
     */
    internal fun isBusy(range: ProgressBarRangeInfo?): Boolean {
        return range == ProgressBarRangeInfo.Indeterminate
    }

    /**
     * Compose scrolls by the amount of the scroll position: positive x moves towards the end of the content in layout
     * direction, which is on the left in a right-to-left layout, and a reversed layout turns each axis around. The
     * agent thinks in screen directions, so those are converted here; an axis the container does not scroll fails.
     */
    private fun scroller(
        action: (Float, Float) -> Boolean,
        horizontal: ScrollAxisRange?,
        vertical: ScrollAxisRange?,
        rtl: Boolean,
    ): (Float, Float) -> Boolean {
        return { dx, dy ->
            val axesKnown = horizontal != null || vertical != null
            if (axesKnown && ((dx != 0f && horizontal == null) || (dy != 0f && vertical == null))) {
                false
            } else {
                val x = if (rtl != (horizontal?.reverseScrolling == true)) -dx else dx
                val y = if (vertical?.reverseScrolling == true) -dy else dy
                action(x, y)
            }
        }
    }
}
