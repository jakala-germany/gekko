package com.jakala.gekko.cli

/** Reads tests and flows without a device: broken YAML, unknown keys, missing flows, missing parameters. */
object Checker {
    fun check(project: Project, filter: List<String>): List<String> {
        val problems = mutableListOf<String>()
        val checkedGroups = mutableSetOf<String>()
        for (path in project.testFiles(filter)) {
            project.groupFileOf(path)?.takeIf { checkedGroups.add(it) }?.let { checkGroup(project, it, problems) }
            val test =
                try {
                    project.loadTest(path)
                } catch (error: Exception) {
                    problems += "$path: ${error.message?.lineSequence()?.firstOrNull() ?: error}"
                    continue
                }
            val uses = test.uses.map { Step(action = Action(use = it)) }
            checkSteps(project, uses + test.steps, path, emptyList(), problems)
        }
        return problems
    }

    private fun checkGroup(project: Project, path: String, problems: MutableList<String>) {
        val group =
            try {
                project.loadGroup(path)
            } catch (error: Exception) {
                problems += "$path: ${error.message?.lineSequence()?.firstOrNull() ?: error}"
                return
            }
        val flows = (group.setup + group.teardown).map { Step(action = Action(use = it)) }
        checkSteps(project, flows, path, emptyList(), problems)
    }

    private fun checkSteps(
        project: Project,
        steps: List<Step>,
        where: String,
        stack: List<String>,
        problems: MutableList<String>,
    ) {
        for (step in steps) {
            step.action.mock?.let { checkMock(project, it, where, problems) }
            val use = step.action.use
            val repeat = step.action.repeat
            if (use != null) {
                if (use in stack) {
                    problems += "$where: flow '$use' calls itself (${(stack + use).joinToString(" -> ")})"
                    continue
                }
                val flow =
                    try {
                        project.loadFlow(use, step.action.with)
                    } catch (error: Exception) {
                        problems += "$where: flow '$use': ${error.message?.lineSequence()?.firstOrNull() ?: error}"
                        continue
                    }
                checkSteps(project, flow.steps, "$where > $use", stack + use, problems)
            }
            if (repeat != null) {
                checkSteps(project, repeat.steps, where, stack, problems)
            }
        }
    }

    /** The mock file has to be there and its placeholders have to be written correctly. */
    private fun checkMock(project: Project, mock: MockSpec, where: String, problems: MutableList<String>) {
        val file = mock.file ?: return
        val shown = "${project.config.mocksDir}/$file"
        val path = "${project.root}/$shown"
        if (!fileExists(path)) {
            problems += "$where: mock file $shown does not exist"
            return
        }
        try {
            // Texts kept by copyText only exist while a test runs; everything else is checked here
            val variables = Variables(language = { null }, today = { "2026-01-01" }, random = { "0".repeat(it * 2) })
            project.renderMock(project.readMockFile(file), { it }) { variables.resolve(it) }
        } catch (error: StepFailure) {
            if (error.message?.startsWith("Variable '") != true) problems += "$where: $shown: ${error.message}"
        } catch (error: Exception) {
            problems += "$where: $shown: ${error.message?.lineSequence()?.firstOrNull() ?: error}"
        }
    }
}
