package com.jakala.gekko.device

/** Decisions about single nodes that the agent and the system runner take the same way. */
object NodeFacts {
    private const val TYPE_MASK_CLASS = 0xf
    private const val TYPE_MASK_VARIATION = 0xff0
    private const val CLASS_TEXT = 0x1
    private const val CLASS_NUMBER = 0x2
    private const val TEXT_PASSWORD = 0x80
    private const val TEXT_VISIBLE_PASSWORD = 0x90
    private const val TEXT_WEB_PASSWORD = 0xe0
    private const val NUMBER_PASSWORD = 0x10

    private val tabClasses =
        setOf("TabView", "NavigationBarItemView", "BottomNavigationItemView", "NavigationRailItemView")

    /** Text, visible-password, web-password and number-password inputs: what a keyboard hides or never learns. */
    fun isPasswordInputType(inputType: Int): Boolean {
        val variation = inputType and TYPE_MASK_VARIATION
        return when (inputType and TYPE_MASK_CLASS) {
            CLASS_TEXT ->
                variation == TEXT_PASSWORD || variation == TEXT_VISIBLE_PASSWORD || variation == TEXT_WEB_PASSWORD
            CLASS_NUMBER -> variation == NUMBER_PASSWORD
            else -> false
        }
    }

    /** Tabs and bottom navigation items report their selection both ways; a plain view only when it is selected. */
    fun selectedState(isSelected: Boolean, tabLike: Boolean): Boolean? {
        return when {
            isSelected -> true
            tabLike -> false
            else -> null
        }
    }

    /** [className] is a class name with or without package, nested classes as `Outer$Inner`. */
    fun isTabLikeClass(className: String?): Boolean {
        return className != null && className.substringAfterLast('.').substringAfterLast('$') in tabClasses
    }

    /** The description of a password field, unless it contains what the person typed. */
    fun safeDescription(description: String?, secrets: List<String?>): String? {
        if (description == null) {
            return null
        }
        return if (secrets.any { !it.isNullOrEmpty() && description.contains(it) }) null else description
    }
}
