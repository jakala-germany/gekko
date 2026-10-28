package com.jakala.gekko.device

import com.jakala.gekko.protocol.Bounds
import com.jakala.gekko.protocol.Command
import com.jakala.gekko.protocol.ErrorKind
import com.jakala.gekko.protocol.Response
import com.jakala.gekko.protocol.Selector
import com.jakala.gekko.protocol.UiNode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class WaitingTest {
    private var clock = 0L
    private val window = Bounds(0, 0, 1000, 2000)

    private fun screen(vararg children: UiNode): Frame {
        return Frame(listOf(UiNode(id = "window", bounds = window, children = children.toList())))
    }

    private fun item(bounds: Bounds = Bounds(0, 0, 100, 100), busy: Boolean = false): UiNode {
        return UiNode(id = "item", bounds = bounds, busy = busy)
    }

    private fun wait(command: Command.WaitFor, read: () -> Frame): Response {
        return Waiting.visibility(command, now = { clock }, pause = { clock += it }, read = read)
    }

    @Test
    fun answersOkAsSoonAsTheElementIsOnScreen() {
        var reads = 0
        val response =
            wait(Command.WaitFor(Selector(id = "item"), timeoutMs = 1_000)) {
                reads++
                if (reads < 3) screen() else screen(item())
            }

        assertEquals(Response.Ok, response)
        assertEquals(3, reads)
    }

    @Test
    fun anElementOutsideTheWindowIsNotVisibleYetAndNotOnScreenAtTheEnd() {
        val response =
            wait(Command.WaitFor(Selector(id = "item"), timeoutMs = 200)) { screen(item(Bounds(0, 2100, 100, 2200))) }

        assertEquals(
            Response.Error("Expected visible: ${Selector(id = "item")}", ErrorKind.NOT_ON_SCREEN, null),
            response,
        )
    }

    @Test
    fun nothingMatchingIsNotFound() {
        val response = wait(Command.WaitFor(Selector(id = "item"), timeoutMs = 200)) { screen() }

        assertEquals(Response.Error("Expected visible: ${Selector(id = "item")}", ErrorKind.NOT_FOUND, null), response)
    }

    @Test
    fun aSettledScreenEndsTheWaitEarlyAndSaysSo() {
        val response = wait(Command.WaitFor(Selector(id = "item"), timeoutMs = 10_000, settleMs = 500)) { screen() }

        val error = assertIs<Response.Error>(response)
        assertEquals(ErrorKind.NOT_FOUND, error.kind)
        assertEquals(true, error.settled)
        assertEquals(true, clock < 10_000)
    }

    @Test
    fun aBusyScreenKeepsTheWaitGoingUntilTheTimeout() {
        val response =
            wait(Command.WaitFor(Selector(id = "missing"), timeoutMs = 2_000, settleMs = 500)) {
                screen(item(busy = true))
            }

        val error = assertIs<Response.Error>(response)
        assertEquals(false, error.settled)
        assertEquals(true, clock >= 2_000)
    }

    @Test
    fun waitingForAnElementToDisappearEndsWithATimeout() {
        val response = wait(Command.WaitFor(Selector(id = "item"), visible = false, timeoutMs = 200)) { screen(item()) }

        assertEquals(
            Response.Error("Expected not visible: ${Selector(id = "item")}", ErrorKind.TIMEOUT, null),
            response,
        )
    }

    @Test
    fun anElementOutsideTheWindowCountsAsGone() {
        val response =
            wait(Command.WaitFor(Selector(id = "item"), visible = false, timeoutMs = 200)) {
                screen(item(Bounds(0, 2100, 100, 2200)))
            }

        assertEquals(Response.Ok, response)
    }
}
