package me.timschneeberger.rootlessjamesdsp.utils

import android.content.Context
import android.content.SharedPreferences

/** App ownership filtering for player sessions. It never overrides explicit system-global mode. */
data class ProcessingAppPolicy(
    val ready: Boolean,
    val allowlistEnabled: Boolean = false,
    val allowedPackageUids: Map<String, Int> = emptyMap(),
    val excludedUids: Set<Int> = emptySet()
) {
    fun allows(uid: Int, packageName: String): Boolean {
        if (!ready || uid < 0 || uid in excludedUids) return false
        if (!allowlistEnabled) return true
        // Compare both identities: shared UIDs must not authorize an unselected sibling package.
        return allowedPackageUids[packageName] == uid
    }

    data class Request(val enabled: Boolean, val packages: Set<String>, val excluded: Set<Int>) {
        /** Apply removals immediately, retaining already resolved, still selected applications. */
        fun cached(previous: ProcessingAppPolicy, confirmedOwners: Map<String, Int> = emptyMap()) = ProcessingAppPolicy(
            ready = true,
            allowlistEnabled = enabled,
            allowedPackageUids = if (enabled) (previous.allowedPackageUids + confirmedOwners)
                .filterKeys { it in packages } else emptyMap(),
            excludedUids = excluded
        )

        /** Call on IO: resolving selected package ownership must never scan packages on the UI thread. */
        @Suppress("DEPRECATION")
        fun resolve(context: Context): ProcessingAppPolicy {
            val resolved = if (enabled) packages.mapNotNull { name ->
                runCatching { context.packageManager.getApplicationInfo(name, 0).uid }
                    .getOrNull()?.let { name to it }
            }.toMap() else emptyMap()
            return ProcessingAppPolicy(true, enabled, resolved, excluded)
        }
    }

    companion object {
        const val ENABLED = "app_processing_allowlist_enabled"
        const val PACKAGES = "app_processing_allowlist_packages"
        const val EDITING = "app_processing_policy_editing"
        const val EDIT_UIDS = "app_processing_policy_edit_uids"
        const val EXPECTED_EXCLUDED_UIDS = "app_processing_policy_expected_excluded_uids"
        const val EDIT_EPOCH = "app_processing_policy_edit_epoch"
        const val COMPLETED_EPOCH = "app_processing_policy_completed_epoch"

        fun uidSet(value: Any?): Set<Int> = (value as? Set<*>)?.filterIsInstance<String>()
            ?.mapNotNull { it.toIntOrNull()?.takeIf { uid -> uid >= 0 } }?.toSet().orEmpty()

        fun pending() = ProcessingAppPolicy(ready = false)

        fun request(preferences: SharedPreferences, excluded: Array<Int>): Request {
            val values = preferences.all
            val packages = (values[PACKAGES] as? Set<*>)?.filterIsInstance<String>()
                ?.filter { it.isNotBlank() }?.toSet().orEmpty()
            return Request(values[ENABLED] == true, packages, excluded.toSet())
        }
    }
}
