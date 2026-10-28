package com.jakala.gekko.cli

/** Maps the permission names of a test (`location`, `camera`, ...) to what each platform calls them. */
object PermissionNames {
    private val android =
        mapOf(
            "location" to
                listOf("android.permission.ACCESS_FINE_LOCATION", "android.permission.ACCESS_COARSE_LOCATION"),
            "camera" to listOf("android.permission.CAMERA"),
            "microphone" to listOf("android.permission.RECORD_AUDIO"),
            "notifications" to listOf("android.permission.POST_NOTIFICATIONS"),
            "contacts" to listOf("android.permission.READ_CONTACTS", "android.permission.WRITE_CONTACTS"),
            "calendar" to listOf("android.permission.READ_CALENDAR", "android.permission.WRITE_CALENDAR"),
            "photos" to listOf("android.permission.READ_MEDIA_IMAGES", "android.permission.READ_MEDIA_VIDEO"),
        )

    /** simctl privacy services; camera and notifications cannot be set from outside a simulator. */
    private val ios =
        mapOf(
            "location" to "location",
            "microphone" to "microphone",
            "contacts" to "contacts",
            "calendar" to "calendar",
            "photos" to "photos",
        )

    /** A full permission name (it contains a dot) is passed through as is. */
    fun android(name: String): List<String> {
        if ('.' in name) {
            return listOf(name)
        }
        return android[name.lowercase()] ?: throw StepFailure("Unknown permission '$name' for Android")
    }

    fun ios(name: String): String {
        return ios[name.lowercase()] ?: throw StepFailure("Permission '$name' cannot be set on iOS simulators")
    }

    /** `allow` or `deny`; anything else is a typo that should not silently grant or revoke. */
    fun isGranted(value: String): Boolean {
        return when (value.lowercase()) {
            "allow" -> true
            "deny" -> false
            else -> throw StepFailure("Permission value must be allow or deny, was '$value'")
        }
    }
}
