package com.jakala.gekko.cli

import com.jakala.gekko.protocol.Command
import com.jakala.gekko.protocol.Response

/** Answers every command with [answer] and remembers what it was asked. */
internal class FakeClient(private val answer: suspend (Command) -> Response) : CommandClient {
    val received = mutableListOf<Command>()

    override suspend fun send(command: Command): Response {
        received += command
        return answer(command)
    }

    override suspend fun isReachable(): Boolean {
        return true
    }

    override fun close() {}
}

internal class FakeDevice(
    override val supportsWaitFor: Boolean,
    private val appRunning: Boolean? = null,
    /** An emulator or simulator takes the location from its console; a physical device does not have one. */
    private val locationConsole: Boolean = true,
) : Device {
    val locations = mutableListOf<Pair<Double, Double>>()
    var mockLocationAllowed = 0

    override fun setLocation(latitude: Double, longitude: Double) {
        check(locationConsole) { "setLocation works on Android emulators only" }
        locations += latitude to longitude
    }

    override fun allowMockLocation() {
        mockLocationAllowed++
    }

    override fun isAppRunning(config: GekkoConfig): Boolean? {
        return appRunning
    }

    override fun screenshot(): ByteArray {
        return ByteArray(0)
    }

    override fun pressKey(key: String) {}

    override fun openLink(url: String) {}

    override fun agentPort(config: GekkoConfig): Int {
        return 1
    }

    override fun systemPort(): Int {
        return 2
    }

    override fun clearData(config: GekkoConfig) {}

    override fun install(config: GekkoConfig, path: String?) {}

    override fun launch(config: GekkoConfig, arguments: Map<String, String>) {}

    override fun uninstall(config: GekkoConfig): List<String> {
        return emptyList()
    }

    override fun terminate(config: GekkoConfig) {}

    override fun startSystemRunner(config: GekkoConfig, log: (String) -> Unit) {}
}
