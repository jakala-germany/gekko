package com.jakala.gekko.cli

/**
 * Applies [resolve] to every text a step can carry, so `${var.x}` and `${date(...)}` are filled in just before it runs.
 */
internal fun SelectorSpec.resolved(resolve: (String) -> String): SelectorSpec {
    return copy(
        text = text?.let(resolve),
        textContains = textContains?.let(resolve),
        textMatches = textMatches?.let(resolve),
    )
}

internal fun Condition.resolved(resolve: (String) -> String): Condition {
    return Condition(visible?.resolved(resolve), notVisible?.resolved(resolve))
}

internal fun Action.resolved(resolve: (String) -> String): Action {
    return copy(
        tap = tap?.resolved(resolve),
        type =
            type?.copy(
                text = type.text?.let(resolve),
                textContains = type.textContains?.let(resolve),
                textMatches = type.textMatches?.let(resolve),
                value = resolve(type.value),
            ),
        scroll =
            scroll?.copy(
                text = scroll.text?.let(resolve),
                textContains = scroll.textContains?.let(resolve),
                textMatches = scroll.textMatches?.let(resolve),
            ),
        swipe = swipe?.copy(from = swipe.from?.resolved(resolve)),
        assertVisible = assertVisible?.resolved(resolve),
        assertNotVisible = assertNotVisible?.resolved(resolve),
        openLink = openLink?.let(resolve),
        with = with.mapValues { resolve(it.value) },
        doubleTap = doubleTap?.resolved(resolve),
        longPress =
            longPress?.copy(
                text = longPress.text?.let(resolve),
                textContains = longPress.textContains?.let(resolve),
                textMatches = longPress.textMatches?.let(resolve),
            ),
        scrollUntilVisible =
            scrollUntilVisible?.copy(
                text = scrollUntilVisible.text?.let(resolve),
                textContains = scrollUntilVisible.textContains?.let(resolve),
                textMatches = scrollUntilVisible.textMatches?.let(resolve),
                container = scrollUntilVisible.container?.resolved(resolve),
            ),
        eraseText = eraseText?.resolved(resolve),
        copyText = copyText?.copy(from = copyText.from.resolved(resolve)),
    )
}
