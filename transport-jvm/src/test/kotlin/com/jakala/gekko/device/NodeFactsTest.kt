package com.jakala.gekko.device

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class NodeFactsTest {
    @Test
    fun recognisesPasswordInputTypes() {
        // TYPE_CLASS_TEXT | TYPE_TEXT_VARIATION_PASSWORD, ..._VISIBLE_PASSWORD, ..._WEB_PASSWORD
        assertTrue(NodeFacts.isPasswordInputType(0x00000081))
        assertTrue(NodeFacts.isPasswordInputType(0x00000091))
        assertTrue(NodeFacts.isPasswordInputType(0x000000e1))
        // TYPE_CLASS_NUMBER | TYPE_NUMBER_VARIATION_PASSWORD
        assertTrue(NodeFacts.isPasswordInputType(0x00000012))
        // the same, with TYPE_TEXT_FLAG_NO_SUGGESTIONS and TYPE_TEXT_FLAG_CAP_CHARACTERS
        assertTrue(NodeFacts.isPasswordInputType(0x00081081))
        assertTrue(NodeFacts.isPasswordInputType(0x00001091))
    }

    @Test
    fun otherInputTypesAreNotPasswords() {
        assertFalse(NodeFacts.isPasswordInputType(0))
        // plain text, email address, person name, multi line text, plain number, phone
        assertFalse(NodeFacts.isPasswordInputType(0x00000001))
        assertFalse(NodeFacts.isPasswordInputType(0x00000021))
        assertFalse(NodeFacts.isPasswordInputType(0x00000061))
        assertFalse(NodeFacts.isPasswordInputType(0x00020001))
        assertFalse(NodeFacts.isPasswordInputType(0x00000002))
        assertFalse(NodeFacts.isPasswordInputType(0x00000003))
    }

    @Test
    fun aPlainViewReportsSelectionOnlyWhenSelected() {
        assertEquals(true, NodeFacts.selectedState(isSelected = true, tabLike = false))
        assertNull(NodeFacts.selectedState(isSelected = false, tabLike = false))
    }

    @Test
    fun aTabReportsSelectionBothWays() {
        assertEquals(true, NodeFacts.selectedState(isSelected = true, tabLike = true))
        assertEquals(false, NodeFacts.selectedState(isSelected = false, tabLike = true))
    }

    @Test
    fun recognisesTabLikeClasses() {
        assertTrue(NodeFacts.isTabLikeClass("com.google.android.material.tabs.TabLayout\$TabView"))
        assertTrue(NodeFacts.isTabLikeClass("com.google.android.material.navigation.NavigationBarItemView"))
        assertTrue(NodeFacts.isTabLikeClass("BottomNavigationItemView"))
        assertFalse(NodeFacts.isTabLikeClass("android.widget.TextView"))
        assertFalse(NodeFacts.isTabLikeClass("com.example.Tabular"))
        assertFalse(NodeFacts.isTabLikeClass(null))
    }

    @Test
    fun aDescriptionThatContainsTheSecretIsDropped() {
        assertNull(NodeFacts.safeDescription("Password hunter2", listOf("hunter2")))
        assertNull(NodeFacts.safeDescription("hunter2", listOf(null, "hunter2")))
    }

    @Test
    fun aLabelIsKept() {
        assertEquals("Password", NodeFacts.safeDescription("Password", listOf("hunter2", "", null)))
        assertNull(NodeFacts.safeDescription(null, listOf("hunter2")))
    }
}
