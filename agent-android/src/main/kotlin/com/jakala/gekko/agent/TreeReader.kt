package com.jakala.gekko.agent

import android.text.method.PasswordTransformationMethod
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.ProgressBar
import android.widget.TextView
import com.jakala.gekko.device.NodeFacts
import com.jakala.gekko.device.intersect
import com.jakala.gekko.protocol.Bounds
import com.jakala.gekko.protocol.UiNode

internal class TreeReader(private val rootViews: () -> List<View>) {
    private val composeAvailable = ComposeReader.isAvailable()
    private val tabClasses = mutableMapOf<Class<*>, Boolean>()

    /** The windows lookups and gestures work on: the visible ones from the topmost modal window up. */
    fun windows(): List<View> {
        return WindowRoots.reachable(rootViews().filter { it.visibility == View.VISIBLE && it.width > 0 })
    }

    fun read(): Snapshot {
        val flat = mutableListOf<Element>()
        val roots = windows().map { readView(it, null, it, boundsOf(it), flat) }
        return Snapshot(roots, flat)
    }

    private fun boundsOf(view: View): Bounds {
        val location = IntArray(2)
        view.getLocationOnScreen(location)
        return Bounds(location[0], location[1], location[0] + view.width, location[1] + view.height)
    }

    private fun readView(view: View, parent: Element?, root: View, clip: Bounds, sink: MutableList<Element>): Element {
        val bounds = boundsOf(view)
        val canScrollHorizontally = view.canScrollHorizontally(1) || view.canScrollHorizontally(-1)
        val canScrollVertically = view.canScrollVertically(1) || view.canScrollVertically(-1)
        val secure = view is TextView && isPassword(view)
        val value = (view as? TextView)?.text?.toString()
        val description = view.contentDescription?.toString()
        val element =
            Element(
                node =
                    UiNode(
                        id = resourceName(view),
                        text = if (secure) null else viewText(view),
                        description =
                            if (secure) NodeFacts.safeDescription(description, listOf(value)) else description,
                        role = view.javaClass.simpleName,
                        bounds = bounds,
                        actions = if (view.isClickable) listOf("click") else emptyList(),
                        enabled = view.isEnabled,
                        checked = (view as? android.widget.Checkable)?.isChecked,
                        selected = NodeFacts.selectedState(view.isSelected, isTabLike(view)),
                        secure = secure,
                        busy = view is ProgressBar && view.isIndeterminate,
                    ),
                parent = parent,
                rootView = root,
                click = if (view.isClickable) ({ view.performClick() }) else null,
                setText =
                    (view as? EditText)?.let { edit ->
                        { text: String ->
                            edit.setText(text)
                            true
                        }
                    },
                scrollBy =
                    if (canScrollHorizontally || canScrollVertically)
                        ({ dx: Float, dy: Float ->
                            if ((dx != 0f && !canScrollHorizontally) || (dy != 0f && !canScrollVertically)) {
                                false
                            } else {
                                view.scrollBy(dx.toInt(), dy.toInt())
                                true
                            }
                        })
                    else null,
                clip = clip,
                rtl = view.layoutDirection == View.LAYOUT_DIRECTION_RTL,
            )
        sink += element
        // Children are cut off at the bounds of their parent unless it lets them draw outside
        val childClip = if (view is ViewGroup && !view.clipChildren) clip else clip.intersect(bounds)
        if (composeAvailable) {
            ComposeReader.read(view, element, root, childClip, sink)?.let { element.children += it }
        }
        if (view is ViewGroup) {
            for (index in 0 until view.childCount) {
                val child = view.getChildAt(index)
                if (child.visibility == View.VISIBLE) {
                    element.children += readView(child, element, root, childClip, sink)
                }
            }
        }
        return element
    }

    private fun isPassword(view: TextView): Boolean {
        return NodeFacts.isPasswordInputType(view.inputType) ||
            view.transformationMethod is PasswordTransformationMethod
    }

    private fun isTabLike(view: View): Boolean {
        return tabClasses.getOrPut(view.javaClass) {
            generateSequence<Class<*>>(view.javaClass) { it.superclass }
                .takeWhile { it != View::class.java }
                .any { NodeFacts.isTabLikeClass(it.name) }
        }
    }

    private fun resourceName(view: View): String? {
        if (view.id == View.NO_ID) {
            return null
        }
        return runCatching { view.resources.getResourceEntryName(view.id) }.getOrNull()
    }

    private fun viewText(view: View): String? {
        return when (view) {
            is TextView -> view.text?.toString()
            else -> view.contentDescription?.toString()
        }
    }
}
