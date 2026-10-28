package com.jakala.gekko.protocol

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
sealed interface Response {
    @Serializable @SerialName("ok") data object Ok : Response

    /**
     * [protocol] is the wire format number (see [Protocol]), [role] one of [Protocol.ROLE_AGENT] /
     * [Protocol.ROLE_RUNNER].
     */
    @Serializable
    @SerialName("hello")
    data class HelloResult(val protocol: Int, val version: String, val role: String) : Response

    /**
     * [density] is pixels per dp (1 on iOS, where coordinates are points); [rtl] is true for a right-to-left layout.
     */
    @Serializable @SerialName("metrics") data class MetricsResult(val density: Float, val rtl: Boolean) : Response

    @Serializable @SerialName("tree") data class TreeResult(val windows: List<UiNode>) : Response

    @Serializable @SerialName("foreground") data class ForegroundResult(val appInForeground: Boolean) : Response

    @Serializable @SerialName("screenshot") data class ScreenshotResult(val pngBase64: String) : Response

    /** The in-app agent only serves commands while the app owns the focus; the CLI then asks the system runner. */
    @Serializable @SerialName("notForeground") data object NotForeground : Response

    /**
     * [kind] is one of [ErrorKind] when the server knows what went wrong; [settled] tells whether the screen was done
     * loading when the wait ended (null when it was not checked).
     */
    @Serializable
    @SerialName("error")
    data class Error(val message: String, val kind: String? = null, val settled: Boolean? = null) : Response
}

object ErrorKind {
    /** No node matched the selector. */
    const val NOT_FOUND = "notFound"

    /** Nodes match but none is on screen. */
    const val NOT_ON_SCREEN = "notOnScreen"

    /** Nodes match but none is enabled. */
    const val DISABLED = "disabled"

    /** The matched node does not support the action (not editable, nothing scrollable). */
    const val REJECTED = "rejected"

    /** The request could not be understood or is not served by this server. */
    const val UNSUPPORTED = "unsupported"

    /** The wait for an expected state ran out. */
    const val TIMEOUT = "timeout"
}
