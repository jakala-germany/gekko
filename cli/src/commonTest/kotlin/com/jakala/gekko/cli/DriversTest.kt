package com.jakala.gekko.cli

import com.jakala.gekko.protocol.Command
import com.jakala.gekko.protocol.Response
import com.jakala.gekko.protocol.Selector
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest

class DriversTest {
    private val tap = Command.Tap(Selector(id = "go"))

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
