package com.jakala.gekko.cli

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class CheckTest {
    private val root = "/tmp/gekko-check-test"

    private fun project(files: Map<String, String>): Project {
        files.forEach { (name, text) ->
            makeDirs("$root/${name.substringBeforeLast('/')}")
            writeTextFile("$root/$name", text.trimIndent())
        }
        return Project(root, GekkoConfig())
    }

    @Test
    fun acceptsTestsWhoseFlowsExistAndGetTheirParameters() {
        val project =
            project(
                mapOf(
                    "tests/ok.yaml" to
                        """
                        name: Ok
                        steps:
                          - say: Log in
                            do: { use: functions/login, with: { USER: "a@b.c" } }
                        """,
                    "flows/functions/login.yaml" to
                        """
                        name: login
                        params: [USER]
                        steps:
                          - say: Type
                            do: { type: { action: setText, value: "${'$'}{USER}" } }
                        """,
                )
            )

        assertEquals(emptyList(), Checker.check(project, listOf("$root/tests/ok.yaml")))
    }

    @Test
    fun reportsMissingFlowsMissingParametersAndUnknownKeys() {
        val project =
            project(
                mapOf(
                    "tests/broken.yaml" to
                        """
                        name: Broken
                        steps:
                          - say: Missing flow
                            do: { use: functions/nothing }
                          - say: Missing parameter
                            do: { use: functions/login }
                        """,
                    "tests/typo.yaml" to
                        """
                        name: Typo
                        steps:
                          - say: Typo
                            do: { tapp: { id: x } }
                        """,
                    "flows/functions/login.yaml" to
                        """
                        name: login
                        params: [USER]
                        steps:
                          - say: Type
                            do: { type: { action: setText, value: "${'$'}{USER}" } }
                        """,
                )
            )

        val problems = Checker.check(project, listOf("$root/tests/broken.yaml", "$root/tests/typo.yaml"))

        assertEquals(3, problems.size)
        assertTrue(problems.any { it.startsWith("$root/tests/typo.yaml") })
        assertTrue(problems.any { it.contains("flow 'functions/nothing'") })
        assertTrue(problems.any { it.contains("Missing parameter 'USER'") })
    }

    @Test
    fun reportsAFlowThatCallsItself() {
        val project =
            project(
                mapOf(
                    "tests/loop.yaml" to
                        """
                        name: Loop
                        steps:
                          - say: Loop
                            do: { use: a }
                        """,
                    "flows/a.yaml" to
                        """
                        name: a
                        steps:
                          - say: Again
                            do: { use: a }
                        """,
                )
            )

        val problems = Checker.check(project, listOf("$root/tests/loop.yaml"))

        assertEquals(1, problems.size)
        assertTrue(problems.single().contains("calls itself"))
    }
}
