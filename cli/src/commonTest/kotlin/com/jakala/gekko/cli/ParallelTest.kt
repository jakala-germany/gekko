package com.jakala.gekko.cli

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest

class ParallelTest {
    @Test
    fun everyItemIsProcessedOnceAndWorkersShareTheLoad() = runTest {
        val processed = mutableListOf<Pair<Int, Int>>()

        distribute((1..6).toList(), workerCount = 3) { worker, item ->
            delay(if (worker == 0) 100L else 10L)
            processed += worker to item
        }

        assertEquals((1..6).toList(), processed.map { it.second }.sorted())
        assertTrue(processed.map { it.first }.toSet().size > 1)
    }

    @Test
    fun slowWorkerDoesNotHoldBackTheQueue() = runTest {
        val perWorker = mutableMapOf<Int, Int>()

        distribute((1..8).toList(), workerCount = 2) { worker, _ ->
            delay(if (worker == 0) 1_000L else 10L)
            perWorker[worker] = (perWorker[worker] ?: 0) + 1
        }

        assertTrue((perWorker[1] ?: 0) > (perWorker[0] ?: 0))
    }

    @Test
    fun replicateRunsEveryItemOnEveryWorkerInOrder() = runTest {
        val perWorker = mutableMapOf<Int, MutableList<Int>>()

        replicate((1..4).toList(), workerCount = 3) { worker, item ->
            perWorker.getOrPut(worker) { mutableListOf() } += item
        }

        assertEquals(setOf(0, 1, 2), perWorker.keys)
        perWorker.values.forEach { assertEquals(listOf(1, 2, 3, 4), it) }
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun replicateRunsWorkersConcurrently() = runTest {
        replicate((1..3).toList(), workerCount = 4) { _, _ -> delay(100L) }

        assertEquals(300L, testScheduler.currentTime)
    }

    @Test
    fun allResolvesEveryDeviceAndSimulator() {
        val refs = DeviceResolver.resolve(listOf("all"), listOf("emulator-5554", "R5CT"), listOf("AAAA-1111"))

        assertEquals(listOf(Platform.Android, Platform.Android, Platform.Ios), refs.map { it.platform })
        assertEquals(listOf("emulator-5554", "R5CT", "AAAA-1111"), refs.map { it.id })
    }

    @Test
    fun allWithoutDevicesIsAnError() {
        assertFailsWith<IllegalStateException> { DeviceResolver.resolve(listOf("all"), emptyList(), emptyList()) }
    }

    @Test
    fun resolvesDevicesByPlatform() {
        val refs =
            DeviceResolver.resolve(listOf("emulator-5554", "AAAA-1111"), listOf("emulator-5554"), listOf("AAAA-1111"))

        assertEquals(listOf(Platform.Android, Platform.Ios), refs.map { it.platform })
    }

    @Test
    fun unknownDeviceListsWhatIsAvailable() {
        val error =
            assertFailsWith<IllegalStateException> {
                DeviceResolver.resolve(listOf("nope"), listOf("emulator-5554"), emptyList())
            }

        assertTrue(error.message!!.contains("emulator-5554"))
    }

    @Test
    fun simulatorPortsAreStableAndEvenPerUdid() {
        val first = SimulatorPorts.agent("041E26E4-7CE3-4640-8703-26812BD934BC")

        assertEquals(first, SimulatorPorts.agent("041E26E4-7CE3-4640-8703-26812BD934BC"))
        assertEquals(0, first % 2)
        assertTrue(first in 20_000..27_999)
    }
}
