package com.jakala.gekko.cli

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable
data class FailureInfo(
    val test: String,
    val path: String,
    val step: String?,
    val error: String?,
    /** `test` when a step failed, `infrastructure` when the device, the agent or the runner did. */
    val kind: String? = null,
) {
    companion object {
        private val json = Json {
            prettyPrint = true
            ignoreUnknownKeys = true
        }

        fun encode(info: FailureInfo): String {
            return json.encodeToString(serializer(), info)
        }

        fun decode(text: String): FailureInfo {
            return json.decodeFromString(serializer(), text)
        }
    }
}
