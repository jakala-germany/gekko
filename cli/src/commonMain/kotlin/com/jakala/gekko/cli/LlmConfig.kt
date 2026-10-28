package com.jakala.gekko.cli

/** The OpenAI-compatible endpoint of `--heal api`, read from the environment. */
class LlmConfig(val baseUrl: String, val apiKey: String?, val model: String) {
    companion object {
        fun fromEnv(): LlmConfig {
            val baseUrl = env("GEKKO_LLM_BASE_URL") ?: error("GEKKO_LLM_BASE_URL is not set")
            val model = env("GEKKO_LLM_MODEL") ?: error("GEKKO_LLM_MODEL is not set")
            // An empty key (an unset CI secret) means no key: no empty Authorization header is sent
            return LlmConfig(baseUrl.trimEnd('/'), env("GEKKO_LLM_API_KEY")?.ifBlank { null }, model)
        }
    }
}
