package me.timschneeberger.rootlessjamesdsp.fusion

/** Used only when an output value is missing, or the user explicitly resets its slider. */
object OutputDefaults {
    const val THRESHOLD_DB = -0.1f
    const val RELEASE_MS = 1.5f
    const val POST_GAIN_DB = 0f
    const val BALANCE = 0f
}
