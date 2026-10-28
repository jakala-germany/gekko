package com.jakala.gekko.cli

/**
 * The environment variables tests may read with `${env.NAME}`: only the ones `gekko.yaml` declares in `env:`, taken
 * from the process environment first and from a `.env` file next to `gekko.yaml` second. Variables that hold the
 * secrets of gekko or of CI (`GEKKO_LLM_API_KEY`, `GITHUB_TOKEN`, ...) are refused even when declared.
 */
class EnvAccess(private val declared: Set<String>, private val dotEnv: Map<String, String>) {
    init {
        declared.forEach { name ->
            require(NAME.matches(name)) { "env: '$name' is not a variable name (letters, digits and _)" }
            require(REFUSED_PREFIXES.none { name.startsWith(it) }) {
                "env: $name belongs to gekko or to CI and cannot be used in tests"
            }
        }
    }

    /** Every declared variable that has a value; they are masked in everything gekko prints or writes. */
    fun values(): List<String> {
        return declared.mapNotNull { lookup(it) }.filter { it.isNotEmpty() }
    }

    fun value(name: String): String {
        require(name in declared) { "\${env.$name}: declare $name in the env: list of gekko.yaml" }
        return lookup(name)
            ?: throw ConfigurationException("Environment variable $name is not set (process environment or .env)")
    }

    private fun lookup(name: String): String? {
        return env(name) ?: dotEnv[name]
    }

    companion object {
        private val NAME = Regex("[A-Za-z_][A-Za-z0-9_]*")
        private val REFUSED_PREFIXES =
            listOf(
                "GEKKO_",
                "GITHUB_",
                "ACTIONS_",
                "RUNNER_",
                "OP_",
                "ANTHROPIC_",
                "OPENAI_",
                "AWS_",
                "SONATYPE_",
                "GPG_",
                "NPM_",
                "SSH_",
            )

        /** Tests that read no environment: a placeholder `${env.X}` is an error. */
        val NONE = EnvAccess(emptySet(), emptyMap())

        /** `KEY=value` lines; `#` starts a comment, values may be in single or double quotes. */
        fun parseDotEnv(text: String): Map<String, String> {
            val result = mutableMapOf<String, String>()
            for (raw in text.lines()) {
                val line = raw.trim().removePrefix("export ").trim()
                if (line.isEmpty() || line.startsWith("#")) continue
                val equals = line.indexOf('=')
                if (equals <= 0) continue
                val name = line.substring(0, equals).trim()
                var value = line.substring(equals + 1).trim()
                val quote = value.firstOrNull()?.takeIf { it == '"' || it == '\'' }
                val closing = if (quote != null) value.indexOf(quote, 1) else -1
                value =
                    if (closing > 0) {
                        value.substring(1, closing)
                    } else {
                        value.substringBefore(" #").trim()
                    }
                result[name] = value
            }
            return result
        }
    }
}

/** Replaces the values of secrets in everything gekko prints or writes; the values are known before the first test. */
object Masker {
    private var secrets: List<String> = emptyList()

    /** Called once while the project loads, before any test runs. */
    fun register(values: List<String>) {
        secrets = values.filter { it.length >= MIN_LENGTH }.distinct().sortedByDescending { it.length }
    }

    fun mask(text: String): String {
        var masked = text
        for (secret in secrets) {
            masked = masked.replace(secret, MASK)
        }
        return masked
    }

    private const val MASK = "***"
    private const val MIN_LENGTH = 3
}
