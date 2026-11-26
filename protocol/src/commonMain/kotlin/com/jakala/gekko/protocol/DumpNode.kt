package com.jakala.gekko.protocol

import kotlinx.serialization.Serializable

/**
 * A node of the Android accessibility tree with what `adb shell uiautomator dump` writes of it, as Android reports it:
 * the full resource id, the text without the content description in its place, the flags no selector needs. Only the
 * Android system runner sends it, in answer to [Command.UiDump]; the CLI turns it into the dump's XML.
 *
 * Every child is here, also the ones the dump leaves out: [visible] tells them apart, and the dump still decides from
 * the texts of invisible children whether a node is accessible (its `NAF` attribute).
 */
@Serializable
data class DumpNode(
    /** The position among the children of the parent, counting the ones Android could not hand out. */
    val index: Int,
    /**
     * The text. A password field has its hint while it is empty and a dot (`•`) for every character after that, as
     * Android shows it; never what was typed.
     */
    val text: String? = null,
    /** The full resource id (`com.example:id/name`). */
    val resourceId: String? = null,
    val className: String? = null,
    val packageName: String? = null,
    val contentDescription: String? = null,
    val checkable: Boolean = false,
    val checked: Boolean = false,
    val clickable: Boolean = false,
    val enabled: Boolean = true,
    val focusable: Boolean = false,
    val focused: Boolean = false,
    val scrollable: Boolean = false,
    val longClickable: Boolean = false,
    val password: Boolean = false,
    val selected: Boolean = false,
    /** The bounds the dump writes: those on the screen, cut to the display (empty when nothing is on it). */
    val bounds: Bounds,
    val drawingOrder: Int = 0,
    val hint: String? = null,
    /** Visible to the user; the dump leaves out a child that is not, and everything below it. */
    val visible: Boolean = true,
    val children: List<DumpNode> = emptyList(),
)
