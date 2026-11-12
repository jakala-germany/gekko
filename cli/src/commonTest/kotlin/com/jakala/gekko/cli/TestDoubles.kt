package com.jakala.gekko.cli

import com.jakala.gekko.protocol.Command
import com.jakala.gekko.protocol.Response

/** Answers every command with [answer] and remembers what it was asked. */
internal class FakeClient(private val answer: suspend (Command) -> Response) : CommandClient {
    val received = mutableListOf<Command>()

    /** Whether the client answers yet; evaluated on every check. */
    var reachable: () -> Boolean = { true }

    override suspend fun send(command: Command): Response {
        received += command
        return answer(command)
    }

    override suspend fun isReachable(): Boolean {
        return reachable()
    }

    override fun close() {}
}

internal class FakeDevice(
    override val supportsWaitFor: Boolean,
    private val appRunning: Boolean? = null,
    /** An emulator or simulator takes the location from its console; a physical device does not have one. */
    private val locationConsole: Boolean = true,
    override val isTv: Boolean = false,
) : Device {
    val locations = mutableListOf<Pair<Double, Double>>()
    var mockLocationAllowed = 0

    /** For every start and every end of a test: whether the state was kept / the session is kept for a next test. */
    val starts = mutableListOf<Boolean>()
    val ends = mutableListOf<Boolean>()

    override fun freshStart(
        config: GekkoConfig,
        settings: DeviceSettings,
        keepState: Boolean,
        beforeLaunch: () -> Unit,
    ): () -> Unit {
        starts += keepState
        return super.freshStart(config, settings, keepState, beforeLaunch)
    }

    /** Every handover of mocks to the app, in order; null is the end of a handover that was not picked up. */
    val startupMocks = mutableListOf<String?>()

    /** What happened to the app and its mocks, in order: `launch`, `mocks` (handed over), `mocks off`. */
    val events = mutableListOf<String>()

    override fun setStartupMocks(config: GekkoConfig, mocks: String?) {
        startupMocks += mocks
        events += if (mocks == null) "mocks off" else "mocks"
    }

    override fun endTest(config: GekkoConfig, keepSession: Boolean) {
        ends += keepSession
        super.endTest(config, keepSession)
    }

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

    override fun openLink(url: String) {}

    override fun agentPort(config: GekkoConfig): Int {
        return 1
    }

    override fun systemPort(): Int {
        return 2
    }

    override fun agentAuth(config: GekkoConfig): ClientAuth {
        return ClientAuth("test key".encodeToByteArray(), "1")
    }

    override fun systemAuth(): ClientAuth {
        return ClientAuth("test key".encodeToByteArray(), "2")
    }

    override fun clearData(config: GekkoConfig) {}

    override fun install(config: GekkoConfig, path: String?) {}

    override fun launch(config: GekkoConfig, arguments: Map<String, String>) {
        events += "launch"
    }

    override fun uninstall(config: GekkoConfig): List<String> {
        return emptyList()
    }

    override fun terminate(config: GekkoConfig) {}

    override fun startSystemRunner(config: GekkoConfig, log: (String) -> Unit) {}
}
