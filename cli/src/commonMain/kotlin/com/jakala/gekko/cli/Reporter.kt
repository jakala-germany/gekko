package com.jakala.gekko.cli

object Reporter {
    fun junit(suite: String, results: List<TestResult>): String {
        val failures = results.count { it.failure != null && !it.infrastructure }
        val errors = results.count { it.failure != null && it.infrastructure }
        val totalSeconds = results.sumOf { it.durationMs } / MILLIS
        val builder = StringBuilder()
        builder.append("""<?xml version="1.0" encoding="UTF-8"?>""").append('\n')
        builder
            .append(
                """<testsuite name="${escape(suite)}" tests="${results.size}" failures="$failures" errors="$errors" time="$totalSeconds">"""
            )
            .append('\n')
        results.forEach { result ->
            val device = result.device?.let { """ classname="${escape(it)}"""" }.orEmpty()
            builder.append("""  <testcase name="${escape(result.name)}"$device time="${result.durationMs / MILLIS}"""")
            if (result.failure == null) {
                builder.append("/>\n")
            } else {
                builder.append(">\n")
                val tag = if (result.infrastructure) "error" else "failure"
                builder.append("""    <$tag message="${escape(result.failure)}">""")
                builder.append(escape("Step: ${result.failedStep}\nArtifacts: ${result.artifacts.joinToString()}"))
                builder.append("</$tag>\n  </testcase>\n")
            }
        }
        builder.append("</testsuite>\n")
        return builder.toString()
    }

    /** Only the steps a model had to heal, each as a failed testcase; the main report keeps them as passes. */
    fun junitHealed(suite: String, results: List<TestResult>): String {
        val healed = results.flatMap { result -> result.healed.map { record -> result to record } }
        val builder = StringBuilder()
        builder.append("""<?xml version="1.0" encoding="UTF-8"?>""").append('\n')
        builder
            .append("""<testsuite name="${escape(suite)}" tests="${healed.size}" failures="${healed.size}" time="0">""")
            .append('\n')
        healed.forEach { (result, record) ->
            val device = result.device?.let { """ classname="${escape(it)}"""" }.orEmpty()
            builder.append("""  <testcase name="${escape("${record.test} › ${record.say}")}"$device time="0">""")
            builder.append('\n')
            builder.append("""    <failure message="${escape("${record.missing} missing; used ${record.applied}")}">""")
            builder.append(escape(record.reason))
            builder.append("</failure>\n  </testcase>\n")
        }
        builder.append("</testsuite>\n")
        return builder.toString()
    }

    private fun escape(value: String): String {
        return Masker.mask(value)
            .replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")
            .replace("\"", "&quot;")
    }

    private const val MILLIS = 1000.0
}
