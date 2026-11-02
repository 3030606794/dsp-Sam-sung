package me.timschneeberger.rootlessjamesdsp.utils

import android.content.Context
import android.content.SharedPreferences
import android.content.pm.ApplicationInfo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import me.timschneeberger.rootlessjamesdsp.MainApplication
import me.timschneeberger.rootlessjamesdsp.model.AppInfo
import me.timschneeberger.rootlessjamesdsp.model.room.AppBlocklistRepository
import me.timschneeberger.rootlessjamesdsp.model.room.BlockedApp
import me.timschneeberger.rootlessjamesdsp.utils.extensions.CompatExtensions.getApplicationInfoCompat
import me.timschneeberger.rootlessjamesdsp.utils.extensions.CompatExtensions.getInstalledApplicationsCompat

/** Both editors use one serialized operation; the two persistent lists cannot retain overlap. */
object AppProcessingLists {
    enum class Target { SPECIFIED, EXCLUDED }
    data class SelectionFilter(val packages:Set<String>,val uids:Set<Int>) {
        fun contains(packageName:String,uid:Int)=packageName in packages || uid in uids
    }
    /** Counts refer to UID groups, matching the blocklist's actual audio-processing scope. */
    data class SystemAppsExclusionResult(val addedCount:Int,val skippedSpecifiedCount:Int)
    const val EDITING="app_processing_policy_editing"
    const val EDIT_UIDS="app_processing_policy_edit_uids"
    const val EXPECTED_EXCLUDED="app_processing_policy_expected_excluded_uids"
    const val EDIT_EPOCH="app_processing_policy_edit_epoch"
    const val COMPLETED_EPOCH="app_processing_policy_completed_epoch"
    private val mutex=Mutex()

    private fun preferences(context:Context)=context.getSharedPreferences(Constants.PREF_APP,Context.MODE_PRIVATE)
    private fun repository(context:Context)=(context.applicationContext as MainApplication).blockedAppRepository
    private fun packages(prefs:SharedPreferences)=prefs.getStringSet(ProcessingAppPolicy.PACKAGES,emptySet()).orEmpty().toSet()
    private fun owners(context:Context,packages:Set<String>)=packages.mapNotNull {pkg->
        runCatching {context.packageManager.getApplicationInfoCompat(pkg).uid}.getOrNull()?.let {pkg to it}
    }.toMap()
    private fun conflicts(blocked:List<BlockedApp>,selected:Set<String>,owners:Map<String,Int>)=
        blocked.filter {it.uid in owners.values || it.packageName in selected}

    private fun begin(prefs:SharedPreferences,uids:Set<Int>) {
        val previous=prefs.getLong(EDIT_EPOCH,0L)
        val epoch=if(previous==Long.MAX_VALUE)1L else previous+1L
        check(prefs.edit().putBoolean(EDITING,true)
            .putLong(EDIT_EPOCH,epoch)
            .putStringSet(EDIT_UIDS,uids.filter {it>=0}.map(Int::toString).toSet()).commit())
    }
    private suspend fun finish(prefs:SharedPreferences,repo:AppBlocklistRepository) {
        val finalUids=repo.blocklist.first().map {it.uid.toString()}.toSet()
        check(prefs.edit().putStringSet(EXPECTED_EXCLUDED,finalUids)
            .putLong(COMPLETED_EPOCH,prefs.getLong(EDIT_EPOCH,0L))
            .putBoolean(EDITING,false).commit())
    }
    private suspend fun normalizeLocked(context:Context,prefs:SharedPreferences,repo:AppBlocklistRepository) {
        val selected=packages(prefs)
        val blocked=repo.blocklist.first()
        val overlapping=conflicts(blocked,selected,owners(context,selected))
        if(overlapping.isEmpty() && !prefs.getBoolean(EDITING,false)) {
            finish(prefs,repo)
            return
        }
        begin(prefs,overlapping.map {it.uid}.toSet())
        try {overlapping.forEach {repo.delete(it)}}
        finally {finish(prefs,repo)}
    }

    /** Repairs legacy conflicts in actual Room rows, preferring an already specified app. */
    suspend fun normalize(context:Context)=withContext(Dispatchers.IO+NonCancellable) {
        mutex.withLock {normalizeLocked(context,preferences(context),repository(context))}
    }

    suspend fun select(context:Context,app:AppInfo,target:Target)=withContext(Dispatchers.IO+NonCancellable) {
        mutex.withLock {
            val prefs=preferences(context);val repo=repository(context)
            val selected=packages(prefs);val resolved=owners(context,selected)
            val blocked=repo.blocklist.first()
            val overlap=conflicts(blocked,selected,resolved)
            begin(prefs,overlap.map {it.uid}.toSet()+app.uid)
            try {
                when(target) {
                    Target.SPECIFIED->{
                        blocked.filter {it.uid==app.uid || it.packageName==app.packageName}.forEach {repo.delete(it)}
                        check(prefs.edit().putStringSet(ProcessingAppPolicy.PACKAGES,selected+app.packageName).commit())
                    }
                    Target.EXCLUDED->{
                        val remaining=selected.filterNot {it==app.packageName || resolved[it]==app.uid}.toSet()
                        check(prefs.edit().putStringSet(ProcessingAppPolicy.PACKAGES,remaining).commit())
                        repo.insert(BlockedApp(app.uid,app.packageName,app.appName))
                    }
                }
                // Other pre-existing conflicts are repaired by the same operation.
                val current=packages(prefs)
                conflicts(repo.blocklist.first(),current,owners(context,current)).forEach {repo.delete(it)}
            } finally {finish(prefs,repo)}
        }
    }

    suspend fun removeSpecified(context:Context,packageName:String)=withContext(Dispatchers.IO+NonCancellable) {
        mutex.withLock {
            val prefs=preferences(context);val repo=repository(context)
            begin(prefs,owners(context,setOf(packageName)).values.toSet())
            try {check(prefs.edit().putStringSet(ProcessingAppPolicy.PACKAGES,packages(prefs)-packageName).commit())}
            finally {finish(prefs,repo)}
        }
    }
    suspend fun removeExcluded(context:Context,app:BlockedApp)=withContext(Dispatchers.IO+NonCancellable) {
        mutex.withLock {
            val prefs=preferences(context);val repo=repository(context)
            begin(prefs,setOf(app.uid))
            try {repo.delete(app)}finally {finish(prefs,repo)}
        }
    }

    /** One policy epoch and one Room batch; already specified apps always keep precedence. */
    suspend fun excludeSystemApps(context:Context):SystemAppsExclusionResult=withContext(Dispatchers.IO+NonCancellable) {
        mutex.withLock {
            val appContext=context.applicationContext
            val prefs=preferences(appContext);val repo=repository(appContext)
            val selected=packages(prefs)
            val specifiedUids=owners(appContext,selected).values.toSet()
            val blocked=repo.blocklist.first()
            val blockedUids=blocked.map {it.uid}.toSet()
            val blockedPackages=blocked.mapNotNull {it.packageName}.toSet()
            val manager=appContext.packageManager
            val ownUid=appContext.applicationInfo.uid
            val systemFlags=ApplicationInfo.FLAG_SYSTEM or ApplicationInfo.FLAG_UPDATED_SYSTEM_APP
            val groups=manager.getInstalledApplicationsCompat(0).asSequence()
                .filter {(it.flags and ApplicationInfo.FLAG_INSTALLED)!=0 && (it.flags and systemFlags)!=0}
                .filter {it.uid>=0 && it.uid!=ownUid && it.packageName!=appContext.packageName}
                .groupBy {it.uid}
                .toSortedMap()
            var skippedSpecified=0
            val additions=ArrayList<BlockedApp>()
            groups.forEach {(uid,apps)->
                if(uid in specifiedUids || apps.any {it.packageName in selected}) {
                    skippedSpecified++
                } else if(uid !in blockedUids && apps.none {it.packageName in blockedPackages}) {
                    val representative=apps.minBy {it.packageName}
                    val name=runCatching {representative.loadLabel(manager).toString()}
                        .getOrDefault(representative.packageName)
                    additions.add(BlockedApp(uid,representative.packageName,name))
                }
            }
            if(additions.isNotEmpty()) {
                begin(prefs,additions.map {it.uid}.toSet())
                try {repo.insertAll(additions)}finally {finish(prefs,repo)}
            }
            SystemAppsExclusionResult(additions.size,skippedSpecified)
        }
    }

    /** Called on IO before loading icons; exclude the other list by package and by shared UID. */
    suspend fun oppositeSelection(context:Context,target:Target)=withContext(Dispatchers.IO+NonCancellable) {
        mutex.withLock {
            val prefs=preferences(context);val repo=repository(context)
            normalizeLocked(context,prefs,repo)
            when(target) {
                Target.SPECIFIED->repo.blocklist.first().let {rows->
                    SelectionFilter(rows.mapNotNull {it.packageName}.toSet(),rows.map {it.uid}.toSet())
                }
                Target.EXCLUDED->packages(prefs).let {selected->SelectionFilter(selected,owners(context,selected).values.toSet())}
            }
        }
    }
}
