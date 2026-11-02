package me.timschneeberger.rootlessjamesdsp.utils

/** Result of a root command executed off the UI thread. */
data class RootCommandResult(
    val rootGranted: Boolean,
    val code: Int = -1,
    val output: List<String> = emptyList(),
) {
    val success: Boolean get() = rootGranted && code == 0
}
