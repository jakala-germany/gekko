package com.jakala.gekko.cli

import com.jakala.gekko.protocol.Command
import com.jakala.gekko.protocol.ErrorKind
import com.jakala.gekko.protocol.Response
import com.jakala.gekko.protocol.Selector
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest

class DriversTest {
    private val tap = Command.Tap(Selector(id = "go"))

    private fun unsupported(): Response {
        return Response.Error("not here", ErrorKind.UNSUPPORTED)
    }

    private fun drivers(device: Device, agent: FakeClient, system: FakeClient): Drivers {
        return Drivers(device, GekkoConfig(), agent = agent, system = system)
    }

    @Test
    fun androidSendsACommandToTheAgentWithoutAskingForTheForegroundFirst() {
        runTest {
            val agent = FakeClient { Response.Ok }
            val system = FakeClient { Response.Ok }

            val response = drivers(FakeDevice(supportsWaitFor = true), agent, system).send(tap)

            assertEquals(Response.Ok, response)
            assertEquals(listOf<Command>(tap), agent.received)
            assertTrue(system.received.isEmpty())
        }
    }

    @Test
    fun androidStopsWhenSomethingAnswersInPlaceOfTheAgentInsteadOfAskingTheSystemRunner() {
        runTest {
            val agent = FakeClient { throw UntrustedServerException("not the agent") }
            val system = FakeClient { Response.Ok }

            assertFailsWith<UntrustedServerException> {
                drivers(FakeDevice(supportsWaitFor = true), agent, system).send(tap)
            }
            assertTrue(system.received.isEmpty())
        }
    }

    @Test
    fun iosStopsWhenSomethingAnswersInPlaceOfTheAgentInsteadOfAskingTheSystemRunner() {
        runTest {
            val agent = FakeClient { throw UntrustedServerException("not the agent") }
            val system = FakeClient { Response.Ok }

            assertFailsWith<UntrustedServerException> {
                drivers(FakeDevice(supportsWaitFor = false), agent, system).send(tap)
            }
            assertTrue(system.received.isEmpty())
        }
    }

    @Test
    fun aKeyTheAgentCannotPressGoesToTheSystemRunnerOnAndroid() {
        runTest {
            val press = Command.PressKey("home")
            val agent = FakeClient { Response.Error("not here", ErrorKind.UNSUPPORTED) }
            val system = FakeClient { Response.Ok }

            val response = drivers(FakeDevice(supportsWaitFor = true), agent, system).sendWithRunnerFallback(press)

            assertEquals(Response.Ok, response)
            assertEquals(listOf<Command>(press), agent.received)
            assertEquals(listOf<Command>(press), system.received)
        }
    }

    @Test
    fun aRemoteButtonTheAgentCannotInjectGoesToTheSystemRunnerOnAndroid() {
        runTest {
            val press = Command.PressKey("down", times = 3)
            val agent = FakeClient { Response.Error("The app did not take the key", ErrorKind.UNSUPPORTED) }
            val system = FakeClient { Response.Ok }

            val response = drivers(FakeDevice(supportsWaitFor = true), agent, system).sendWithRunnerFallback(press)

            assertEquals(Response.Ok, response)
            assertEquals(listOf<Command>(press), agent.received)
            assertEquals(listOf<Command>(press), system.received)
        }
    }

    @Test
    fun aTvRefusesEveryTouchCommandWithoutAskingAnyone() {
        runTest {
            val agent = FakeClient { Response.Ok }
            val system = FakeClient { Response.Ok }
            val tv = drivers(FakeDevice(supportsWaitFor = true, isTv = true), agent, system)
            val touches =
                listOf(
                    tap,
                    Command.TapAt(1, 2),
                    Command.DoubleTap(Selector(id = "go")),
                    Command.LongPress(Selector(id = "go")),
                    Command.LongPressAt(1, 2),
                    Command.Scroll(Selector(id = "go")),
                    Command.Swipe(1, 2, 3, 4),
                    Command.Drag(1, 2, 3, 4),
                    Command.Wheel(1, 2, 3, 4),
                )

            for (command in touches) {
                val response = tv.sendWithRunnerFallback(command)

                assertTrue(response is Response.Error && response.kind == ErrorKind.REJECTED, "$command: $response")
                assertTrue((response as Response.Error).message.contains("pressKey"), response.message)
            }
            assertTrue(agent.received.isEmpty())
            assertTrue(system.received.isEmpty())
        }
    }

    @Test
    fun aTvStillTakesKeysTextAndReads() {
        runTest {
            val agent = FakeClient { Response.Ok }
            val tv = drivers(FakeDevice(supportsWaitFor = true, isTv = true), agent, FakeClient { Response.Ok })
            val commands =
                listOf(
                    Command.PressKey("down"),
                    Command.Tree,
                    Command.TypeFocused("a"),
                    Command.Type(Selector(id = "go"), "a"),
                )

            commands.forEach { assertEquals(Response.Ok, tv.send(it)) }

            assertEquals(commands, agent.received)
        }
    }

    @Test
    fun aKeyTheAgentCannotPressGoesToTheSystemRunnerOnIos() {
        runTest {
            val press = Command.PressKey("home")
            val agent = FakeClient { if (it is Command.Foreground) Response.ForegroundResult(true) else unsupported() }
            val system = FakeClient { Response.Ok }

            val response = drivers(FakeDevice(supportsWaitFor = false), agent, system).sendWithRunnerFallback(press)

            assertEquals(Response.Ok, response)
            assertEquals(listOf<Command>(press), system.received)
        }
    }

    @Test
    fun aTvRunnerIsToldThatTheAppHadTheScreenWhenItIsStarted() {
        runTest {
            val press = Command.PressKey("right")
            val agent = FakeClient { if (it is Command.Foreground) Response.ForegroundResult(true) else unsupported() }
            val device = FakeDevice(supportsWaitFor = false, isTv = true)
            val system = FakeClient { Response.Ok }
            system.reachable = { device.runnerStarts.isNotEmpty() }

            val response = drivers(device, agent, system).sendWithRunnerFallback(press)

            assertEquals(Response.Ok, response)
            assertEquals(listOf(true), device.runnerStarts)
        }
    }

    @Test
    fun aTvRunnerIsToldThatTheAppDidNotHaveTheScreenWhenItWasInTheBackground() {
        runTest {
            val agent = FakeClient { if (it is Command.Foreground) Response.ForegroundResult(false) else unsupported() }
            val device = FakeDevice(supportsWaitFor = false, isTv = true)
            val system = FakeClient { Response.Ok }
            system.reachable = { device.runnerStarts.isNotEmpty() }

            drivers(device, agent, system).sendWithRunnerFallback(Command.PressKey("home"))

            assertEquals(listOf(false), device.runnerStarts)
        }
    }

    @Test
    fun aRunnerOffTheTvDoesNotAskWhetherTheAppHasTheScreen() {
        runTest {
            val press = Command.PressKey("home")
            val agent = FakeClient { if (it is Command.Foreground) Response.ForegroundResult(true) else unsupported() }
            val device = FakeDevice(supportsWaitFor = false)
            val system = FakeClient { Response.Ok }
            system.reachable = { device.runnerStarts.isNotEmpty() }

            drivers(device, agent, system).sendWithRunnerFallback(press)

            assertEquals(listOf(false), device.runnerStarts)
        }
    }

    @Test
    fun aKeyTheAgentPressesStaysWithTheAgent() {
        runTest {
            val press = Command.PressKey("enter")
            val agent = FakeClient { Response.Ok }
            val system = FakeClient { Response.Ok }

            drivers(FakeDevice(supportsWaitFor = true), agent, system).sendWithRunnerFallback(press)

            assertEquals(listOf<Command>(press), agent.received)
            assertTrue(system.received.isEmpty())
        }
    }

    @Test
    fun aKeyForTheSystemRunnerIsNotAskedTwiceWhenItRefusesIt() {
        runTest {
            val press = Command.PressKey("back")
            val agent = FakeClient { Response.Ok }
            val refusal = Response.Error("iOS has no back key", ErrorKind.UNSUPPORTED)
            val system = FakeClient { refusal }

            val response =
                drivers(FakeDevice(supportsWaitFor = true), agent, system).sendWithRunnerFallback(press, true)

            assertEquals(refusal, response)
            assertEquals(listOf<Command>(press), system.received)
            assertTrue(agent.received.isEmpty())
        }
    }

    @Test
    fun androidAsksTheSystemRunnerWhenTheAgentSaysTheAppIsNotInFront() {
        runTest {
            val agent = FakeClient { Response.NotForeground }
            val system = FakeClient { Response.Ok }

            val response = drivers(FakeDevice(supportsWaitFor = true), agent, system).send(tap)

            assertEquals(Response.Ok, response)
            assertEquals(listOf<Command>(tap), system.received)
        }
    }

    @Test
    fun androidGivesTheAppAMomentToComeBackBeforeStartingTheSystemRunner() {
        runTest {
            var answers = 0
            val agent = FakeClient { if (++answers <= 2) Response.NotForeground else Response.Ok }
            val system = FakeClient { Response.Ok }

            val response = drivers(FakeDevice(supportsWaitFor = true), agent, system).send(tap)

            assertEquals(Response.Ok, response)
            assertEquals(3, agent.received.size)
            assertTrue(system.received.isEmpty())
        }
    }

    @Test
    fun androidAsksTheSystemRunnerWhenNothingListensInTheApp() {
        runTest {
            val agent = FakeClient { throw IllegalStateException("connection refused") }
            val system = FakeClient { Response.Ok }

            val response = drivers(FakeDevice(supportsWaitFor = true), agent, system).send(Command.Tree)

            assertEquals(Response.Ok, response)
            assertEquals(listOf<Command>(Command.Tree), system.received)
        }
    }

    @Test
    fun androidReportsAnAppThatDoesNotAnswerInsteadOfWaitingForever() {
        runTest {
            val agent = FakeClient {
                delay(Long.MAX_VALUE)
                Response.Ok
            }
            val system = FakeClient { Response.Ok }

            val response = drivers(FakeDevice(supportsWaitFor = true), agent, system).send(Command.Tree)

            assertTrue(response is Response.Error && response.message.contains("did not answer"))
            assertTrue(system.received.isEmpty())
        }
    }

    @Test
    fun androidWaitsForAnElementWithinItsOwnTimeoutPlusAMargin() {
        runTest {
            val agent = FakeClient {
                delay(12_000)
                Response.Ok
            }
            val system = FakeClient { Response.Ok }
            val wait = Command.WaitFor(Selector(id = "go"), timeoutMs = 10_000)

            val response = drivers(FakeDevice(supportsWaitFor = true), agent, system).send(wait)

            assertEquals(Response.Ok, response)
        }
    }

    @Test
    fun iosKeepsAskingForTheForegroundBeforeEveryCommand() {
        runTest {
            val agent = FakeClient { command ->
                if (command == Command.Foreground) Response.ForegroundResult(true) else Response.Ok
            }
            val system = FakeClient { Response.Ok }

            val response = drivers(FakeDevice(supportsWaitFor = false), agent, system).send(tap)

            assertEquals(Response.Ok, response)
            assertEquals(listOf(Command.Foreground, tap), agent.received)
        }
    }

    @Test
    fun iosUsesTheSystemRunnerWhenTheAppIsNotInFront() {
        runTest {
            val agent = FakeClient { Response.ForegroundResult(false) }
            val system = FakeClient { Response.Ok }

            val response = drivers(FakeDevice(supportsWaitFor = false), agent, system).send(tap)

            assertEquals(Response.Ok, response)
            assertEquals(listOf<Command>(tap), system.received)
        }
    }

    @Test
    fun forcingTheSystemRunnerSkipsTheAgent() {
        runTest {
            val agent = FakeClient { Response.Ok }
            val system = FakeClient { Response.Ok }

            drivers(FakeDevice(supportsWaitFor = true), agent, system).send(tap, forceSystem = true)

            assertTrue(agent.received.isEmpty())
            assertEquals(listOf<Command>(tap), system.received)
        }
    }
}
