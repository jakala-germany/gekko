package com.jakala.gekko.agent

import java.io.File
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class GekkoInitializerTest {
    private fun keyFile(content: String?): File {
        val directory = kotlin.io.path.createTempDirectory("gekko-key").toFile()
        directory.deleteOnExit()
        val file = File(directory, "gekko-key")
        if (content != null) file.writeText(content)
        file.deleteOnExit()
        return file
    }

    @Test
    fun theKeyIsWhatTheCliLeftInTheFileWithoutItsLineBreak() {
        assertContentEquals("abc123".toByteArray(), GekkoInitializer.readKey(keyFile("abc123\n")))
    }

    @Test
    fun withoutAFileOrWithAnEmptyOneThereIsNoKeyAndSoNoAnswer() {
        assertNull(GekkoInitializer.readKey(keyFile(null)))
        assertNull(GekkoInitializer.readKey(keyFile("  \n")))
    }

    @Test
    fun theProcessOfTheAppIsTheMainProcess() {
        assertTrue(GekkoInitializer.isMainProcess("com.example.app", "com.example.app"))
    }

    @Test
    fun aServiceInItsOwnProcessIsNot() {
        assertFalse(GekkoInitializer.isMainProcess("com.example.app", "com.example.app:push"))
    }

    @Test
    fun aProcessWhoseNameIsNotKnownCountsAsTheApp() {
        assertTrue(GekkoInitializer.isMainProcess(null, "com.example.app:push"))
        assertTrue(GekkoInitializer.isMainProcess("com.example.app", null))
    }
}
