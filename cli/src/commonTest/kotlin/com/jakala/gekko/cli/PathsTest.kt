package com.jakala.gekko.cli

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Windows paths look different, but gekko reads them on every host: they are text like any other. */
class PathsTest {
    @Test
    fun aPathIsAbsoluteWhenItStartsAtTheRoot() {
        for (path in listOf("/x", "\\x", "C:/x", "C:\\x", "d:/", "//server/share", "\\\\server\\share")) {
            assertTrue(isAbsolutePath(path), path)
        }
        for (path in listOf("", "x", "./x", "../x", "tests/a.yaml", "1:/x", "a/b:c")) {
            assertFalse(isAbsolutePath(path), path)
        }
    }

    @Test
    fun aPathOnADriveWithoutASlashIsNotInsideAProjectFolderEither() {
        assertTrue(isAbsolutePath("C:x"))
    }

    @Test
    fun anAbsolutePathStaysAsItIs() {
        assertEquals("C:/other", absolutePath("C:/other", "C:/work/app"))
        assertEquals("/other", absolutePath("/other", "/work/app"))
    }

    @Test
    fun aRelativePathIsPutBehindTheCurrentDirectory() {
        assertEquals("C:/work/app/tests", absolutePath("./tests", "C:/work/app"))
        assertEquals("C:/work/app", absolutePath(".", "C:/work/app"))
    }

    @Test
    fun theWebAppOfAProjectKeepsAnAbsoluteWindowsPath() {
        val config = GekkoConfig(app = AppIds(web = "C:/sample/index.html")).withPathsRelativeTo("C:/work/tests")

        assertEquals("C:/sample/index.html", config.app.web)
    }
}
