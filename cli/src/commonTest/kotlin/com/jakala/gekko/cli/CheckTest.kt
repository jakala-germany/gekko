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

    private val mockTest =
        """
        name: Mocked
        steps:
          - say: Mock the routes
            do: { mock: { url: "https://api.example.com/*", file: routes.json } }
        """

    @Test
    fun acceptsAMockWhoseFileIsThere() {
        val project =
            project(
                mapOf(
                    "tests/mocked.yaml" to mockTest,
                    "mocks/routes.json" to "{\"departure\":\"\${now(+10m)}\",\"id\":\"\${random.uuid}\"}",
                )
            )

        assertEquals(emptyList(), Checker.check(project, listOf("$root/tests/mocked.yaml")))
    }

    @Test
    fun reportsAMockFileThatIsMissing() {
        val project = project(mapOf("tests/mocked-missing.yaml" to mockTest))
        deleteIfThere("$root/mocks/routes.json")

        val problems = Checker.check(project, listOf("$root/tests/mocked-missing.yaml"))

        assertEquals(1, problems.size)
        assertTrue(problems.single().contains("mock file mocks/routes.json does not exist"), problems.single())
    }

    @Test
    fun reportsAPlaceholderOfAMockFileThatIsWrittenWrong() {
        val project =
            project(
                mapOf(
                    "tests/mocked-wrong.yaml" to mockTest,
                    "mocks/routes.json" to "{\"departure\":\"\${now(+10x)}\"}",
                )
            )

        val problems = Checker.check(project, listOf("$root/tests/mocked-wrong.yaml"))

        assertEquals(1, problems.size)
        assertTrue(problems.single().contains("mocks/routes.json: now(+10x)"), problems.single())
    }

    @Test
    fun aMockFileMayUseATextThatCopyTextKeeps() {
        val project =
            project(
                mapOf(
                    "tests/mocked-var.yaml" to mockTest,
                    "mocks/routes.json" to "{\"name\":\"\${var.name}\"}",
                )
            )

        assertEquals(emptyList(), Checker.check(project, listOf("$root/tests/mocked-var.yaml")))
    }

    @Test
    fun reportsAMockFileThatIsNoUtf8Text() {
        val project = project(mapOf("tests/mocked-binary.yaml" to mockTest, "mocks/routes.json" to "��\u0000PNG"))

        val problems = Checker.check(project, listOf("$root/tests/mocked-binary.yaml"))

        assertEquals(1, problems.size)
        assertTrue(problems.single().contains("mocks/routes.json: is not a UTF-8 text file"), problems.single())
    }

    @Test
    fun aMocksFolderCanBeRenamed() {
        val project =
            Project(
                root,
                GekkoConfig(mocksDir = "fixtures"),
            )
        makeDirs("$root/tests")
        writeTextFile("$root/tests/mocked-renamed.yaml", mockTest.trimIndent())
        makeDirs("$root/fixtures")
        writeTextFile("$root/fixtures/routes.json", "{}")

        assertEquals(emptyList(), Checker.check(project, listOf("$root/tests/mocked-renamed.yaml")))
    }

    private fun deleteIfThere(path: String) {
        removeRecursively(path)
    }
}
