package com.jakala.gekko.cli

import kotlinx.serialization.SerializationException

/** Something the user has to change: a flag, a test file, gekko.yaml, a version. */
open class ConfigurationException(message: String) : IllegalStateException(message)

/** The agent, the system runner or a device does not behave, or the app crashed; not the fault of a test. */
class InfrastructureException(message: String) : IllegalStateException(message)

/** The exit codes of the CLI: they are part of the 1.0 contract. */
object ExitCode {
    /** Every test passed. */
    const val OK = 0

    /** A test failed. */
    const val FAILED = 1

    /** The command line, a test file, a flow or gekko.yaml is wrong, or the versions do not match. */
    const val USAGE = 2

    /** A device, the agent or the system runner failed, or the app crashed. */
    const val INFRASTRUCTURE = 3

    fun of(error: Throwable): Int {
        return when (error) {
            is InfrastructureException -> INFRASTRUCTURE
            is ConfigurationException,
            is IllegalArgumentException,
            is SerializationException -> USAGE
            is StepFailure -> FAILED
            else -> INFRASTRUCTURE
        }
    }
}
