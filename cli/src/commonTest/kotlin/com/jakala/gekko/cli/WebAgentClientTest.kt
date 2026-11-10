package com.jakala.gekko.cli

import com.jakala.gekko.protocol.Command
import com.jakala.gekko.protocol.ErrorKind
import com.jakala.gekko.protocol.Response
import com.jakala.gekko.protocol.Selector
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonPrimitive

private fun box(left: Int, top: Int, right: Int, bottom: Int): JsonObject {
    return buildJsonObject {
        put("left", JsonPrimitive(left))
        put("top", JsonPrimitive(top))
        put("right", JsonPrimitive(right))
        put("bottom", JsonPrimitive(bottom))
    }
}

private fun node(
    ref: String,
    id: String? = null,
    text: String? = null,
    bounds: JsonObject = box(0, 0, 100, 20),
    actions: List<String> = emptyList(),
    clip: JsonObject? = null,
    focused: Boolean = false,
    children: List<JsonObject> = emptyList(),
): JsonObject {
    return buildJsonObject {
        put("ref", JsonPrimitive(ref))
        put("focused", JsonPrimitive(focused))
        put("id", id?.let { JsonPrimitive(it) } ?: JsonNull)
        put("text", text?.let { JsonPrimitive(it) } ?: JsonNull)
        put("bounds", bounds)
        put("actions", JsonArray(actions.map { JsonPrimitive(it) }))
        clip?.let { put("clip", it) }
        put("children", JsonArray(children))
    }
}

private fun page(vararg children: JsonObject, dialog: JsonObject? = null): JsonObject {
    return buildJsonObject {
        put("ok", JsonPrimitive(true))
        put(
            "windows",
            JsonArray(
                listOf(
                    node("0:0", bounds = box(0, 0, 1280, 800), actions = listOf("scroll"), children = children.toList())
                )
            ),
        )
        put(
            "viewport",
            buildJsonObject {
                put("w", JsonPrimitive(1280))
                put("h", JsonPrimitive(800))
            },
        )
        dialog?.let { put("dialog", it) }
    }
}

private class FakeBridge(var answer: () -> JsonObject) : WebRpc {
    val calls = mutableListOf<Pair<String, JsonObject>>()
    var failure: WebBridgeException? = null

    /** What the page answers to `focus`: by default nothing has the input focus. */
    var focus: () -> JsonObject = { buildJsonObject { put("ok", JsonPrimitive(true)) } }

    override suspend fun call(operation: String, fields: JsonObject): JsonObject {
        calls += operation to fields
        if (operation == "snapshot") return answer()
        if (operation == "focus") return focus()
        failure?.let { throw it }
        return buildJsonObject { put("ok", JsonPrimitive(true)) }
    }

    override suspend fun isUp(): Boolean {
        return true
    }

    override fun close() {}

    fun called(operation: String): List<JsonObject> {
        return calls.filter { it.first == operation }.map { it.second }
    }
}

@OptIn(ExperimentalCoroutinesApi::class)
class WebAgentClientTest {
    private fun TestScope.client(bridge: FakeBridge): WebAgentClient {
        return WebAgentClient(
            bridge,
            GekkoConfig(),
            clock = { testScheduler.currentTime },
            pause = { kotlinx.coroutines.delay(it) },
        )
    }

    @Test
    fun aTapClicksTheMiddleOfWhatCanBeSeenOfTheElementOnceItStoppedMoving() = runTest {
        val bridge =
            FakeBridge({
                page(
                    node(
                        "0:1",
                        id = "list",
                        bounds = box(0, 100, 400, 300),
                        actions = listOf("scroll"),
                        children =
                            listOf(
                                node(
                                    "0:2",
                                    id = "row",
                                    text = "Row",
                                    bounds = box(0, 250, 400, 350),
                                    clip = box(0, 100, 400, 300),
                                )
                            ),
                    )
                )
            })

        val response = client(bridge).send(Command.Tap(Selector(id = "row"), timeoutMs = 5_000, settleMs = 2_500))

        assertEquals(Response.Ok, response)
        val click = bridge.called("click").single()
        // The row reaches from 250 to 350, the list cuts it off at 300: the visible middle is 275
        assertEquals(200, click["x"]!!.jsonPrimitive.int)
        assertEquals(275, click["y"]!!.jsonPrimitive.int)
        assertTrue(bridge.called("snapshot").size >= 2, "the element has to be seen twice at the same place")
    }

    @Test
    fun anElementBelowTheFoldOfThePageIsScrolledToBeforeItIsTapped() = runTest {
        var scrolled = false
        val bridge =
            FakeBridge({
                val top = if (scrolled) 300 else 1200
                page(node("0:1", id = "footer", text = "Footer", bounds = box(0, top, 200, top + 40)))
            })
        val web =
            WebAgentClient(
                object : WebRpc {
                    override suspend fun call(operation: String, fields: JsonObject): JsonObject {
                        if (operation == "scrollIntoView") scrolled = true
                        return bridge.call(operation, fields)
                    }

                    override suspend fun isUp(): Boolean {
                        return true
                    }

                    override fun close() {}
                },
                GekkoConfig(),
                clock = { testScheduler.currentTime },
                pause = { kotlinx.coroutines.delay(it) },
            )

        val response = web.send(Command.Tap(Selector(id = "footer"), timeoutMs = 5_000))

        assertEquals(Response.Ok, response)
        assertEquals("0:1", bridge.called("scrollIntoView").single()["ref"]!!.jsonPrimitive.content)
        assertEquals(320, bridge.called("click").single()["y"]!!.jsonPrimitive.int)
    }

    @Test
    fun aRowThatItsListCutsOffIsNotScrolledTo() = runTest {
        val bridge =
            FakeBridge({
                page(node("0:1", id = "row", bounds = box(0, 1200, 400, 1220), clip = box(0, 100, 400, 300)))
            })

        val response = client(bridge).send(Command.Tap(Selector(id = "row"), timeoutMs = 5_000, settleMs = 300))

        assertIs<Response.Error>(response)
        assertTrue(bridge.called("scrollIntoView").isEmpty())
    }

    @Test
    fun anElementOutsideWhatItsContainerShowsIsNotThere() = runTest {
        val bridge =
            FakeBridge({ page(node("0:1", id = "row", bounds = box(0, 500, 400, 520), clip = box(0, 100, 400, 300))) })

        val response = client(bridge).send(Command.Tap(Selector(id = "row"), timeoutMs = 10_000, settleMs = 300))

        assertIs<Response.Error>(response)
        assertEquals("Element not found: Selector(id=row) (screen settled)", response.message)
        assertEquals(ErrorKind.NOT_ON_SCREEN, response.kind)
        assertEquals(true, response.settled)
        assertTrue(bridge.called("click").isEmpty())
    }

    @Test
    fun aPageThatIsStillChangingGetsTheWholeTimeout() = runTest {
        var round = 0
        val bridge = FakeBridge({ page(node("0:1", id = "other", text = "Round ${round++}")) })

        val response = client(bridge).send(Command.Tap(Selector(id = "row"), timeoutMs = 1_000, settleMs = 300))

        assertIs<Response.Error>(response)
        assertNull(response.settled.takeIf { it == true })
        assertTrue(testScheduler.currentTime >= 1_000)
    }

    @Test
    fun typingFillsTheFirstEditableElementInside() = runTest {
        val bridge =
            FakeBridge({
                page(
                    node(
                        "0:5",
                        id = "field",
                        children = listOf(node("0:6", text = "Name", actions = listOf("click", "setText"))),
                    )
                )
            })

        val response = client(bridge).send(Command.Type(Selector(id = "field"), "Ada", timeoutMs = 2_000))

        assertEquals(Response.Ok, response)
        val fill = bridge.called("fill").single()
        assertEquals("0:6", fill["ref"]!!.jsonPrimitive.content)
        assertEquals("Ada", fill["text"]!!.jsonPrimitive.content)
    }

    private fun focusOn(text: String?, editable: Boolean = true): JsonObject {
        return buildJsonObject {
            put("ok", JsonPrimitive(true))
            put("focused", JsonPrimitive(true))
            put("editable", JsonPrimitive(editable))
            put("text", text?.let { JsonPrimitive(it) } ?: JsonNull)
        }
    }

    @Test
    fun typingAtTheFocusInsertsTextAndWaitsUntilTheFieldHoldsIt() = runTest {
        val bridge = FakeBridge({ page() })
        bridge.focus = { focusOn(if (bridge.called("insertText").isEmpty()) "Ada" else "Ada Lovelace") }

        val response = client(bridge).send(Command.TypeFocused(" Lovelace", timeoutMs = 2_000))

        assertEquals(Response.Ok, response)
        assertEquals(" Lovelace", bridge.called("insertText").single()["text"]!!.jsonPrimitive.content)
        assertTrue(bridge.called("fill").isEmpty())
    }

    @Test
    fun typingAtTheFocusWaitsForAFieldToGetIt() = runTest {
        val bridge = FakeBridge({ page() })
        var asked = 0
        bridge.focus = {
            asked++
            if (asked < 3) buildJsonObject { put("ok", JsonPrimitive(true)) }
            else focusOn(if (bridge.called("insertText").isEmpty()) "" else "Ada")
        }

        assertEquals(Response.Ok, client(bridge).send(Command.TypeFocused("Ada", timeoutMs = 2_000)))
    }

    @Test
    fun typingAtTheFocusIsRejectedWhenNothingGetsIt() = runTest {
        val bridge = FakeBridge({ page() })

        val response = client(bridge).send(Command.TypeFocused("Ada", timeoutMs = 100))

        assertIs<Response.Error>(response)
        assertEquals(ErrorKind.REJECTED, response.kind)
        assertTrue(bridge.called("insertText").isEmpty())
    }

    @Test
    fun typingAtTheFocusIsRejectedForAnElementThatTakesNoText() = runTest {
        val bridge = FakeBridge({ page() })
        bridge.focus = { focusOn(null, editable = false) }

        val response = client(bridge).send(Command.TypeFocused("Ada", timeoutMs = 100))

        assertIs<Response.Error>(response)
        assertEquals(ErrorKind.REJECTED, response.kind)
        assertTrue(bridge.called("insertText").isEmpty())
    }

    @Test
    fun typingAtTheFocusIsRejectedWhenTheFieldDoesNotChange() = runTest {
        val bridge = FakeBridge({ page() })
        bridge.focus = { focusOn("12345") }

        val response = client(bridge).send(Command.TypeFocused("6", timeoutMs = 100))

        assertIs<Response.Error>(response)
        assertEquals(ErrorKind.REJECTED, response.kind)
    }

    @Test
    fun theTreeMarksTheElementThatHasTheFocus() = runTest {
        val bridge = FakeBridge({ page(node("0:1", id = "name", actions = listOf("setText"), focused = true)) })

        val tree = client(bridge).send(Command.Tree)

        assertIs<Response.TreeResult>(tree)
        val field = tree.windows.single().children.single()
        assertTrue(field.focused)
        assertTrue(!tree.windows.single().focused)
    }

    @Test
    fun helloListsTheCommandsTheWebServes() = runTest {
        val hello = client(FakeBridge({ page() })).send(Command.Hello)

        assertIs<Response.HelloResult>(hello)
        assertTrue("typeFocused" in hello.commands)
        assertTrue("tap" in hello.commands)
        assertTrue("screenshot" !in hello.commands)
    }

    @Test
    fun typingIntoSomethingThatCannotBeEditedIsRejected() = runTest {
        val bridge = FakeBridge({ page(node("0:5", id = "label", text = "Name")) })

        val response = client(bridge).send(Command.Type(Selector(id = "label"), "Ada", timeoutMs = 2_000))

        assertIs<Response.Error>(response)
        assertEquals(ErrorKind.REJECTED, response.kind)
    }

    @Test
    fun scrollingTurnsTheWheelOverTheNearestContainerThatScrolls() = runTest {
        val bridge =
            FakeBridge({
                page(
                    node(
                        "0:1",
                        id = "list",
                        bounds = box(100, 100, 300, 300),
                        actions = listOf("scroll"),
                        children =
                            listOf(
                                node(
                                    "0:2",
                                    id = "row",
                                    bounds = box(100, 120, 300, 140),
                                    clip = box(100, 100, 300, 300),
                                )
                            ),
                    )
                )
            })

        val response = client(bridge).send(Command.Scroll(Selector(id = "row"), "down", 400, timeoutMs = 2_000))

        assertEquals(Response.Ok, response)
        val wheel = bridge.called("wheel").single()
        assertEquals(200, wheel["x"]!!.jsonPrimitive.int)
        assertEquals(200, wheel["y"]!!.jsonPrimitive.int)
        assertEquals(0, wheel["dx"]!!.jsonPrimitive.int)
        assertEquals(400, wheel["dy"]!!.jsonPrimitive.int)
    }

    @Test
    fun scrollingToTheStartOfAContentThatIsRightToLeftGoesTheOtherWay() = runTest {
        val bridge =
            FakeBridge({
                page(node("0:1", id = "list", bounds = box(100, 100, 300, 300), actions = listOf("scroll"))).let {
                    JsonObject(it + ("rtl" to JsonPrimitive(true)))
                }
            })

        client(bridge).send(Command.Scroll(Selector(id = "list"), "end", 100, timeoutMs = 2_000))

        assertEquals(-100, bridge.called("wheel").single()["dx"]!!.jsonPrimitive.int)
    }

    @Test
    fun waitingForAnElementThatNeverComesFailsOnceTheScreenIsDone() = runTest {
        val bridge = FakeBridge({ page(node("0:1", id = "other")) })

        val response =
            client(bridge)
                .send(Command.WaitFor(Selector(id = "row"), visible = true, timeoutMs = 10_000, settleMs = 200))

        assertIs<Response.Error>(response)
        assertEquals("Expected visible: Selector(id=row)", response.message)
        assertEquals(true, response.settled)
    }

    @Test
    fun waitingForAnElementToGoSeesItLeave() = runTest {
        var round = 0
        val bridge = FakeBridge({ if (round++ < 3) page(node("0:1", id = "spinner")) else page() })

        val response =
            client(bridge).send(Command.WaitFor(Selector(id = "spinner"), visible = false, timeoutMs = 5_000))

        assertEquals(Response.Ok, response)
    }

    @Test
    fun aDialogIsTheTopmostWindowAndOKAnswersIt() = runTest {
        val dialog = buildJsonObject {
            put("type", JsonPrimitive("confirm"))
            put("message", JsonPrimitive("Delete the item?"))
            put("defaultValue", JsonPrimitive(""))
        }
        val bridge =
            FakeBridge({ page(dialog = dialog).let { JsonObject(it + ("windows" to JsonArray(emptyList()))) } })
        val web = client(bridge)

        val tree = web.send(Command.Tree)
        assertIs<Response.TreeResult>(tree)
        assertEquals("Delete the item?", tree.windows.single().text)
        assertEquals(listOf("OK", "Cancel"), tree.windows.single().children.map { it.text })

        assertEquals(Response.Ok, web.send(Command.Tap(Selector(text = "OK"), timeoutMs = 2_000)))
        assertEquals(true, bridge.called("dialog").single()["accept"]!!.jsonPrimitive.content.toBoolean())
        assertTrue(bridge.called("click").isEmpty())
    }

    @Test
    fun aPromptTakesTheTypedTextWhenItIsAccepted() = runTest {
        val dialog = buildJsonObject {
            put("type", JsonPrimitive("prompt"))
            put("message", JsonPrimitive("Your name?"))
            put("defaultValue", JsonPrimitive("nobody"))
        }
        val bridge =
            FakeBridge({ page(dialog = dialog).let { JsonObject(it + ("windows" to JsonArray(emptyList()))) } })
        val web = client(bridge)

        web.send(Command.Type(Selector(id = "gekko.dialog.field"), "Ada", timeoutMs = 2_000))
        web.send(Command.Tap(Selector(text = "OK"), timeoutMs = 2_000))

        assertEquals("Ada", bridge.called("dialog").single()["text"]!!.jsonPrimitive.content)
    }

    @Test
    fun theNotFoundMessageNamesTheOpenDialog() = runTest {
        val dialog = buildJsonObject {
            put("type", JsonPrimitive("alert"))
            put("message", JsonPrimitive("Saved"))
            put("defaultValue", JsonPrimitive(""))
        }
        val bridge =
            FakeBridge({ page(dialog = dialog).let { JsonObject(it + ("windows" to JsonArray(emptyList()))) } })

        val response = client(bridge).send(Command.Tap(Selector(id = "save"), timeoutMs = 10_000, settleMs = 200))

        assertIs<Response.Error>(response)
        assertTrue(response.message.contains("alert dialog (\"Saved\")"), response.message)
    }

    @Test
    fun aRefusalOfTheBridgeIsAnErrorWithItsKind() = runTest {
        val bridge = FakeBridge({ page(node("0:1", id = "field", actions = listOf("setText"))) })
        bridge.failure = WebBridgeException("Malformed value", ErrorKind.REJECTED)

        val response = client(bridge).send(Command.Type(Selector(id = "field"), "x", timeoutMs = 2_000))

        assertIs<Response.Error>(response)
        assertEquals("Malformed value", response.message)
        assertEquals(ErrorKind.REJECTED, response.kind)
    }

    @Test
    fun aPageThatIsBeingReplacedCannotBeReadForAMoment() = runTest {
        var round = 0
        val bridge =
            FakeBridge({
                if (round++ < 2)
                    buildJsonObject {
                        put("ok", JsonPrimitive(true))
                        put("transient", JsonPrimitive(true))
                    }
                else page(node("0:1", id = "row", text = "Row"))
            })

        val response = client(bridge).send(Command.Tap(Selector(id = "row"), timeoutMs = 5_000))

        assertEquals(Response.Ok, response)
        assertNotNull(bridge.called("click").singleOrNull())
    }

    @Test
    fun rotatingTheDeviceSwapsTheSidesOfTheWindow() = runTest {
        val bridge = FakeBridge({ page() })

        client(bridge).send(Command.SetOrientation(landscape = false))

        val viewport = bridge.called("emulate").single()["viewport"] as JsonObject
        assertEquals(800, viewport["w"]!!.jsonPrimitive.int)
        assertEquals(1280, viewport["h"]!!.jsonPrimitive.int)
    }
}
