@file:Suppress("UNUSED_PARAMETER")

package me.timschneeberger.rootlessjamesdsp.flavor

import me.timschneeberger.rootlessjamesdsp.utils.RootCommandResult

// Stubbed
object RootShellImpl {
    fun initialize() = Unit
    interface OnShellAttachedCallback {
        fun onShellAttached(isRoot: Boolean)
    }

    fun getShell(callback: OnShellAttachedCallback) {}
    fun cmd(command: String) = false
    fun execute(command: String) = RootCommandResult(rootGranted = false)
}
