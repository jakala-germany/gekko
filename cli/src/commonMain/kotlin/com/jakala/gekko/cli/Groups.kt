package com.jakala.gekko.cli

/** The `group.yaml` of a folder of tests, see [GroupFile]. */
class LoadedGroup(val path: String, val file: GroupFile) {
    val name: String
        get() = file.name ?: path.substringBeforeLast('/').substringAfterLast('/')
}

/** What a run hands to one device at a time: a test on its own, or all the tests of a group, in order. */
class RunUnit(val group: LoadedGroup?, val tests: List<Pair<String, TestFile>>)

object RunUnits {
    /**
     * The units that run on [platform]: a group limited to other platforms (`platform:` in its `group.yaml`) is left
     * out.
     */
    fun forPlatform(units: List<RunUnit>, platform: Platform): List<RunUnit> {
        return units.filter { unit -> unit.group?.file?.platform?.includes(platform) != false }
    }

    /**
     * Puts the selected tests (sorted by path, tags applied) into units: the tests of a group are one unit, so they
     * stay together on one device and in one shard; every other test is a unit of its own. Units keep the order of
     * their first test. A group none of whose tests was selected is not a unit and its setup does not run.
     */
    fun of(project: Project, tests: List<Pair<String, TestFile>>): List<RunUnit> {
        val units = mutableListOf<RunUnit>()
        val members = mutableMapOf<String, MutableList<Pair<String, TestFile>>>()
        for (test in tests) {
            val groupPath = project.groupFileOf(test.first)
            if (groupPath == null) {
                units += RunUnit(null, listOf(test))
                continue
            }
            val known = members[groupPath]
            if (known == null) {
                val list = mutableListOf(test)
                members[groupPath] = list
                units += RunUnit(LoadedGroup(groupPath, project.loadGroup(groupPath)), list)
            } else {
                known += test
            }
        }
        return units
    }
}
