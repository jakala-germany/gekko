package com.jakala.gekko.agent

import android.view.View
import androidx.compose.ui.InternalComposeUiApi
import androidx.compose.ui.platform.ViewRootForTest
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.ScrollAxisRange
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
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

    fun read(view: View, parent: Element, root: View, clip: Bounds, sink: MutableList<Element>): Element? {
        val forTest = view as? ViewRootForTest ?: return null
        val origin = IntArray(2)
        view.getLocationOnScreen(origin)
        val owner = forTest.semanticsOwner
        val unmerged = mutableMapOf<Int, SemanticsNode>()
        indexById(owner.unmergedRootSemanticsNode, unmerged)
        return readNode(owner.rootSemanticsNode, parent, root, origin, clip, sink, unmerged)
    }

    private fun indexById(node: SemanticsNode, into: MutableMap<Int, SemanticsNode>) {
        into[node.id] = node
        node.children.forEach { indexById(it, into) }
    }

    private fun readNode(
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
            )
        sink += element
        node.children.forEach { child ->
            element.children += readNode(child, element, root, origin, clip, sink, unmerged)
        }
        // A node that merges its descendants (a button, a list row) hides the texts inside it in this tree;
        // accessibility
        // services and other UI-test tools see them as nodes of their own, so they are added below it
        val own = if (unmerged != null && node.config.isMergingSemanticsOfDescendants) unmerged[node.id] else null
        own?.children?.forEach { child -> element.children += readNode(child, element, root, origin, clip, sink, null) }
        return element
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
