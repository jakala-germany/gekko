package com.jakala.gekko.cli

import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch

/** Hands every item to the first free worker; no item is repeated and all workers run concurrently. */
suspend fun <T> distribute(items: List<T>, workerCount: Int, work: suspend (worker: Int, item: T) -> Unit) {
    val queue = Channel<T>(Channel.UNLIMITED)
    items.forEach { queue.trySend(it) }
    queue.close()
    coroutineScope {
        repeat(workerCount) { worker ->
            launch {
                for (item in queue) {
                    work(worker, item)
                }
            }
        }
    }
}

/** Hands every item to every worker, in order; all workers run concurrently. */
suspend fun <T> replicate(items: List<T>, workerCount: Int, work: suspend (worker: Int, item: T) -> Unit) {
    coroutineScope {
        repeat(workerCount) { worker ->
            launch {
                for (item in items) {
                    work(worker, item)
                }
            }
        }
    }
}

class DeviceRef(val platform: Platform, val id: String) {
    fun create(): Device {
        return when (platform) {
            Platform.Android -> AdbDevice(id)
            Platform.Ios -> SimctlDevice(id)
        }
    }
}

object DeviceResolver {
    const val ALL = "all"

    /**
     * Maps adb serials and booted simulator UDIDs to devices; `all` stands for every attached device and booted
     * simulator, unknown ids are an error listing what is available.
     */
    fun resolve(ids: List<String>, androidSerials: List<String>, simulatorUdids: List<String>): List<DeviceRef> {
        if (ids == listOf(ALL)) {
            check(androidSerials.isNotEmpty() || simulatorUdids.isNotEmpty()) {
                "No device connected and no simulator booted"
            }
            return androidSerials.map { DeviceRef(Platform.Android, it) } +
                simulatorUdids.map { DeviceRef(Platform.Ios, it) }
        }
        return ids.map { id ->
            when (id) {
                in androidSerials -> DeviceRef(Platform.Android, id)
                in simulatorUdids -> DeviceRef(Platform.Ios, id)
                else ->
                    error(
                        "Unknown device '$id'. Android: ${androidSerials.joinToString().ifEmpty { "none" }}; " +
                            "iOS simulators: ${simulatorUdids.joinToString().ifEmpty { "none" }}"
                    )
            }
        }
    }
}
