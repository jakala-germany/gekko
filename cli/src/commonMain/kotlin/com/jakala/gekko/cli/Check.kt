package com.jakala.gekko.cli

/** Reads tests and flows without a device: broken YAML, unknown keys, missing flows, missing parameters. */
object Checker {
    fun check(project: Project, filter: List<String>): List<String> {
        val problems = mutableListOf<String>()
        for (path in project.testFiles(filter)) {
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

    private fun checkSteps(
        project: Project,
        steps: List<Step>,
        where: String,
        stack: List<String>,
        problems: MutableList<String>,
    ) {
        for (step in steps) {
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
}
