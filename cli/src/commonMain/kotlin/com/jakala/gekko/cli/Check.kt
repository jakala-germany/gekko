package com.jakala.gekko.cli

/** What `gekko check` found: [problems] fail the check, [warnings] only point at things to tidy up. */
class CheckReport(val problems: List<String>, val warnings: List<String>)

/** Reads tests and flows without a device: broken YAML, unknown keys, missing flows, missing parameters. */
object Checker {
    fun check(project: Project, filter: List<String>): List<String> {
        return report(project, filter).problems
    }

    /**
     * The problems of tests, flows, groups and panes, and, when the whole project is checked, the elements, actions and
     * panes that nothing uses.
     */
    fun report(project: Project, filter: List<String>): CheckReport {
        val problems = mutableListOf<String>()
        val usage = PaneUsage()
        project.panes.usage = usage
        try {
            checkTests(project, filter, problems)
            problems += project.panes.problems()
            // Actions that no test calls are read too, but they do not count as a use of what they refer to
            project.panes.usage = null
            checkPanes(project, problems)
        } finally {
            project.panes.usage = null
        }
        val warnings = if (filter.isEmpty()) unused(project, usage) else emptyList()
        return CheckReport(problems, warnings)
    }

    private fun checkTests(project: Project, filter: List<String>, problems: MutableList<String>) {
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
            checkSteps(project, uses + test.steps, path, emptyList(), problems, null)
        }
    }

    /** Every action of every pane is read with dummy values for its params, also the ones no test uses yet. */
    private fun checkPanes(project: Project, problems: MutableList<String>) {
        for ((name, file) in project.panes.readable()) {
            val path = project.panes.pathOf(name)
            for ((action, declared) in file.actions) {
                val where = "$path > $action"
                val callable =
                    try {
                        project.loadCallable("$name.$action", declared.params.associateWith { "x" })
                    } catch (error: Exception) {
                        problems += "$where: ${error.message?.lineSequence()?.firstOrNull() ?: error}"
                        continue
                    }
                checkSteps(project, callable.steps, where, listOf(callable.name), problems, name)
            }
            val identity = file.identity.isNotEmpty()
            if (identity) {
                try {
                    project.panes.identity(name, Platform.Android)
                    project.panes.identity(name, Platform.Ios)
                    if (project.config.app.web != null) project.panes.identity(name, Platform.Web)
                } catch (error: Exception) {
                    problems += "$path: identity: ${error.message?.lineSequence()?.firstOrNull() ?: error}"
                }
            }
            for (element in file.elements.keys) {
                try {
                    project.panes.validate(
                        listOf(Step(action = Action(assertVisible = SelectorSpec(element = "$name.$element")))),
                        null,
                        project.panePlatforms.filter { platform ->
                            file.elements.getValue(element).resolve(platform) != null
                        },
                    )
                } catch (error: Exception) {
                    problems += "$path: element '$element': ${error.message?.lineSequence()?.firstOrNull() ?: error}"
                }
            }
        }
    }

    /** Elements, actions and panes that no test, flow or group uses, in the files that can be read. */
    private fun unused(project: Project, usage: PaneUsage): List<String> {
        val warnings = mutableListOf<String>()
        for ((name, file) in project.panes.readable()) {
            val path = project.panes.pathOf(name)
            if (name !in usage.panes) warnings += "$path: pane '$name' is not used by any test or flow"
            for (element in file.elements.keys) {
                // The identity is used when the pane is checked or acted in, so its elements are not unused
                if ("$name.$element" !in usage.elements && element !in file.identity) {
                    warnings += "$path: element '$element' is not used"
                }
            }
            for (action in file.actions.keys) {
                if ("$name.$action" !in usage.actions) warnings += "$path: action '$action' is not used"
            }
        }
        return warnings
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
        checkSteps(project, flows, path, emptyList(), problems, null)
    }

    private fun checkSteps(
        project: Project,
        steps: List<Step>,
        where: String,
        stack: List<String>,
        problems: MutableList<String>,
        scope: String?,
    ) {
        for (step in steps) {
            step.action.mock?.let { checkMock(project, it, where, problems) }
            val use = step.action.use
            val repeat = step.action.repeat
            if (use != null) {
                val called = project.panes.splitRef(use)?.takeIf { project.panes.hasAction(it.first, it.second) }
                val named =
                    if (called != null) use
                    else scope?.takeIf { project.panes.hasAction(it, use) }?.let { "$it.$use" } ?: use
                if (named in stack) {
                    problems += "$where: flow '$use' calls itself (${(stack + named).joinToString(" -> ")})"
                    continue
                }
                val callable =
                    try {
                        project.loadCallable(use, step.action.with, scope)
                    } catch (error: Exception) {
                        val kind = if (looksLikePaneAction(project, use)) "pane action" else "flow"
                        problems += "$where: $kind '$use': ${error.message?.lineSequence()?.firstOrNull() ?: error}"
                        continue
                    }
                checkSteps(project, callable.steps, "$where > $use", stack + callable.name, problems, callable.pane)
            }
            if (repeat != null) {
                checkSteps(project, repeat.steps, where, stack, problems, scope)
            }
        }
    }

    private fun looksLikePaneAction(project: Project, use: String): Boolean {
        return project.panes.splitRef(use)?.let { project.panes.exists(it.first) } == true
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
