package me.timschneeberger.rootlessjamesdsp.service

import android.app.NotificationManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.content.pm.ServiceInfo
import android.media.AudioManager
import android.media.AudioPlaybackConfiguration
import android.media.audiofx.AudioEffect
import android.os.Build
import android.os.Handler
import android.os.Looper
import androidx.core.content.getSystemService
import androidx.core.content.ContextCompat
import androidx.lifecycle.Observer
import androidx.lifecycle.asLiveData
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.channels.Channel
import me.timschneeberger.rootlessjamesdsp.BuildConfig
import me.timschneeberger.rootlessjamesdsp.MainApplication
import me.timschneeberger.rootlessjamesdsp.R
import me.timschneeberger.rootlessjamesdsp.interop.JamesDspRemoteEngine
import me.timschneeberger.rootlessjamesdsp.model.IEffectSession
import me.timschneeberger.rootlessjamesdsp.model.root.RemoteEffectSession
import me.timschneeberger.rootlessjamesdsp.model.room.AppBlocklistDatabase
import me.timschneeberger.rootlessjamesdsp.model.room.AppBlocklistRepository
import me.timschneeberger.rootlessjamesdsp.model.room.BlockedApp
import me.timschneeberger.rootlessjamesdsp.session.root.OnRootSessionChangeListener
import me.timschneeberger.rootlessjamesdsp.session.root.RootActivePlaybackDiscovery
import me.timschneeberger.rootlessjamesdsp.utils.Constants
import me.timschneeberger.rootlessjamesdsp.utils.AppProcessingLists
import me.timschneeberger.rootlessjamesdsp.utils.ProcessingAppPolicy
import me.timschneeberger.rootlessjamesdsp.utils.RoutingObserver
import me.timschneeberger.rootlessjamesdsp.utils.UsbAudioFollowStatus
import me.timschneeberger.rootlessjamesdsp.utils.UsbSampleRateFollow
import me.timschneeberger.rootlessjamesdsp.utils.extensions.ContextExtensions.sendLocalBroadcast
import me.timschneeberger.rootlessjamesdsp.utils.notifications.Notifications
import me.timschneeberger.rootlessjamesdsp.utils.notifications.ServiceNotificationHelper
import me.timschneeberger.rootlessjamesdsp.utils.preferences.Preferences
import me.timschneeberger.rootlessjamesdsp.utils.sdkAbove
import org.koin.android.ext.android.inject
import org.koin.core.component.KoinComponent
import timber.log.Timber

class RootAudioProcessorService : BaseAudioProcessorService(), KoinComponent,
    SharedPreferences.OnSharedPreferenceChangeListener, OnRootSessionChangeListener, RoutingObserver.RoutingChangedCallback {

    // System services
    private lateinit var notificationManager: NotificationManager
    private lateinit var audioManager: AudioManager

    // Termination flags
    private var isServiceDisposing = false
    private val routingObserver: RoutingObserver by inject()
    private val routeHandler=Handler(Looper.getMainLooper())
    private var routeObserved=false
    private var routeFingerprint: String?=null
    private var previousRouteGroup: RoutingObserver.DeviceGroup?=null
    private val usbFollowScope=CoroutineScope(SupervisorJob()+Dispatchers.Main.immediate)
    private var usbFollowJob: Job?=null
    private var attachmentJob: Job?=null
    private var attachmentMode: Boolean?=null
    private var attachmentEpoch=0L
    private val activePlaybackDiscovery by lazy {RootActivePlaybackDiscovery(this)}
    private val discoveryWakeups=Channel<Unit>(Channel.CONFLATED)
    private val playbackCallback=object: AudioManager.AudioPlaybackCallback() {
        override fun onPlaybackConfigChanged(configs: MutableList<AudioPlaybackConfiguration>?) {
            // Public (possibly anonymized) playback callbacks wake the root probe only;
            // their configurations never authorize a session or prove a USB route.
            if(!isServiceDisposing)discoveryWakeups.trySend(Unit)
        }
    }
    private var blockedUids=arrayOf<Int>()
    private var blocklistReady=false
    private var appPolicyRequest: ProcessingAppPolicy.Request? = null
    private var appPolicyJob: Job? = null
    private var appPolicyEpoch = 0L
    private var appPolicyStartupReady = false
    private var policyResolutionComplete = false
    private var lastCompletedPolicyEpoch: Long? = null
    private var policyEditHeld = false
    private var expectedExcludedUids: Set<Int>? = null
    private val invalidatedAppPackages = mutableSetOf<String>()
    private var packageReceiverRegistered = false
    private val packageReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (isServiceDisposing) return
            val changedPackage = intent?.data?.schemeSpecificPart ?: return
            val selected = preferences.preferences.getStringSet(ProcessingAppPolicy.PACKAGES, emptySet()).orEmpty()
            if (changedPackage in selected) {
                invalidatedAppPackages += changedPackage
                refreshAppPolicy(forceResolve = true)
            }
            discoveryWakeups.trySend(Unit)
        }
    }
    private val usbFollow=UsbSampleRateFollow()
    private val refreshUsbRoute=Runnable {
        if(!isServiceDisposing) {
            app.rootSessionDatabase.refreshAfterOutputRouteChange()
            discoveryWakeups.trySend(Unit)
            sendLocalBroadcast(Intent(Constants.ACTION_SAMPLE_RATE_UPDATED))
        }
    }

    // Preferences
    private val preferences: Preferences.App by inject()

    // Room databases
    private val applicationScope = CoroutineScope(SupervisorJob())
    private val blockedAppDatabase by lazy { AppBlocklistDatabase.getDatabase(this, applicationScope) }
    private val blockedAppRepository by lazy { AppBlocklistRepository(blockedAppDatabase.appBlocklistDao()) }
    private val blockedApps by lazy { blockedAppRepository.blocklist.asLiveData() }
    private val blockedAppObserver = Observer<List<BlockedApp>?> { apps ->
        apps?.map { it.uid }?.let { uids ->
            blockedUids=uids.toTypedArray()
            blocklistReady=true
            Timber.d("blockedAppObserver: Excluded UIDs: ${uids.joinToString("; ")}")
            refreshAppPolicy()
        }
    }


    // App object
    private val app
        get() = application as MainApplication

    override fun onCreate() {
        me.timschneeberger.rootlessjamesdsp.expansion.SpeakerRouteBridge.clear()
        super.onCreate()

        // Player attachments wait for app policy; explicit system-global mode is independent.
        app.rootSessionDatabase.enabled = preferences.preferences.getBoolean(getString(R.string.key_powered_on), true)
        app.rootSessionDatabase.applyAppPolicy(ProcessingAppPolicy.pending())

        // Register shared preferences listener
        preferences.registerOnSharedPreferenceChangeListener(this)
        ContextCompat.registerReceiver(this, packageReceiver, IntentFilter().apply {
            addAction(Intent.ACTION_PACKAGE_ADDED)
            addAction(Intent.ACTION_PACKAGE_REMOVED)
            addAction(Intent.ACTION_PACKAGE_REPLACED)
            addDataScheme("package")
        }, ContextCompat.RECEIVER_NOT_EXPORTED)
        packageReceiverRegistered = true
        app.rootSessionDatabase.registerOnSessionChangeListener(this)
        app.rootSessionDatabase.setEffectsReleasedCallback {
            if (!isServiceDisposing) discoveryWakeups.trySend(Unit)
        }

        // Get reference to system services
        audioManager = getSystemService<AudioManager>()!!
        audioManager.registerAudioPlaybackCallback(playbackCallback,routeHandler)
        notificationManager = getSystemService<NotificationManager>()!!

        // Setup database observer
        blockedApps.observeForever(blockedAppObserver)

        // Launch foreground service
        val notification = if (app.isLegacyMode)
            ServiceNotificationHelper.createServiceNotificationLegacy(this)
        else
            ServiceNotificationHelper.createServiceNotification(this, arrayOf())

        sdkAbove(Build.VERSION_CODES.Q) {
            startForeground(
                Notifications.ID_SERVICE_STATUS,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK
            )
        }.below {
            startForeground(
                Notifications.ID_SERVICE_STATUS,
                notification
            )
        }

        // Initialize shared preferences manually
        onSharedPreferenceChanged(preferences.preferences,getString(R.string.key_powered_on))
        applyProcessingMode()
        // Retain USB observation even when the home page is not visible.
        routingObserver.registerOnRoutingChangeListener(this)
        usbFollowScope.launch {
            try {
                withContext(Dispatchers.IO) { AppProcessingLists.normalize(this@RootAudioProcessorService) }
            } catch (error: Exception) {
                if (!isActive || isServiceDisposing) return@launch
                Timber.w(error, "App scope normalization unavailable")
            }
            if (!isActive || isServiceDisposing) return@launch
            appPolicyStartupReady = true
            refreshAppPolicy()
            discoveryWakeups.trySend(Unit)
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Timber.d("onStartCommand")
        if (intent == null) {
            applyProcessingMode()
            return START_STICKY
        }

        if(JamesDspRemoteEngine.isPluginInstalled(this) in arrayOf(
                JamesDspRemoteEngine.PluginState.Unavailable, JamesDspRemoteEngine.PluginState.Unsupported)) {
            Timber.e("onStartCommand: Ignoring command because plugin is not installed")
            stopSelf()
            return START_NOT_STICKY
        }

        // Handle intent action
        when (intent.action) {
            null -> {
                Timber.wtf("onStartCommand: intent.action is null")
            }
            ACTION_START_ENHANCED_PROCESSING -> {
                applyProcessingMode()
                sendLocalBroadcast(Intent(Constants.ACTION_SERVICE_STARTED))
            }
            ACTION_REFRESH_PROCESSING_MODE -> applyProcessingMode()
            ACTION_STOP -> {
                stopSelf()
                return START_NOT_STICKY
            }
            AudioEffect.ACTION_OPEN_AUDIO_EFFECT_CONTROL_SESSION -> {
                if(app.rootSessionDatabase.sessionList.isEmpty())
                    sendLocalBroadcast(Intent(Constants.ACTION_SERVICE_STARTED))

                if(!isServiceDisposing && !app.rootSessionDatabase.usesPlayerAttachments)
                    app.rootSessionDatabase.addSessionByIntent(intent)
                // OPEN means an AudioTrack was created, not necessarily playing. The active
                // output-track discovery must authorize a positive SID before attachment.
                discoveryWakeups.trySend(Unit)
            }
            AudioEffect.ACTION_CLOSE_AUDIO_EFFECT_CONTROL_SESSION -> {
                // A late CLOSE may refer to the previous track while the player already reuses
                // the same SID. Only confirmed output-track discovery can retire the effect.
                discoveryWakeups.trySend(Unit)
                // Dynamic mode must remain alive with zero sessions, including exclusive USB.
            }
        }

        return START_STICKY
    }

    override fun onDestroy() {
        isServiceDisposing = true
        if (packageReceiverRegistered) {
            unregisterReceiver(packageReceiver)
            packageReceiverRegistered = false
        }
        app.rootSessionDatabase.setEffectsReleasedCallback(null)
        audioManager.unregisterAudioPlaybackCallback(playbackCallback)
        attachmentEpoch++
        discoveryWakeups.close()
        attachmentJob?.cancel()
        usbFollowJob?.cancel()
        usbFollowScope.cancel()
        UsbAudioFollowStatus.publish(usbFollow.update(
            app.isPlayerSessionMode, true, emptyList()).status)
        routeHandler.removeCallbacks(refreshUsbRoute)
        routingObserver.unregisterOnRoutingChangeListener(this)
        me.timschneeberger.rootlessjamesdsp.expansion.SpeakerRouteBridge.clear()
        super.onDestroy()

        // Stop foreground service
        stopForeground(STOP_FOREGROUND_REMOVE)

        // Unregister database observer
        blockedApps.removeObserver(blockedAppObserver)
        applicationScope.cancel()

        // Notify app about service termination and unregister
        sendLocalBroadcast(Intent(Constants.ACTION_SERVICE_STOPPED))

        app.rootSessionDatabase.clearSessions()

        preferences.unregisterOnSharedPreferenceChangeListener(this)
        app.rootSessionDatabase.unregisterOnSessionChangeListener(this)

        notificationManager.cancel(Notifications.ID_SERVICE_STATUS)
    }

    override fun onSharedPreferenceChanged(sharedPreferences: SharedPreferences?, key: String?) {
        when (key) {
            null, ProcessingAppPolicy.ENABLED, ProcessingAppPolicy.PACKAGES,
            ProcessingAppPolicy.EDITING, ProcessingAppPolicy.EDIT_UIDS,
            ProcessingAppPolicy.EXPECTED_EXCLUDED_UIDS, ProcessingAppPolicy.EDIT_EPOCH,
            ProcessingAppPolicy.COMPLETED_EPOCH -> refreshAppPolicy()
            getString(R.string.key_audioformat_processing),
            getString(R.string.key_audioformat_enhanced_processing),
            getString(R.string.key_audioformat_usb_rate_follow) -> {
                applyProcessingMode()
                updateUsbFollowMonitoring()
            }
            getString(R.string.key_powered_on) -> {
                app.rootSessionDatabase.enabled = sharedPreferences?.getBoolean(key, true) ?: true
                discoveryWakeups.trySend(Unit)
                updateServiceNotification()
            }
        }
    }

    /** Retain lists for player mode. Package ownership lookup runs off the service/UI thread. */
    private fun refreshAppPolicy(forceResolve: Boolean = false) {
        if (isServiceDisposing || !blocklistReady || !appPolicyStartupReady) return
        val values = preferences.preferences.all
        val editEpoch = (values[ProcessingAppPolicy.EDIT_EPOCH] as? Number)?.toLong() ?: 0L
        val completedEpoch = (values[ProcessingAppPolicy.COMPLETED_EPOCH] as? Number)?.toLong() ?: 0L
        if (values[ProcessingAppPolicy.EDITING] == true || editEpoch != completedEpoch) {
            expectedExcludedUids = null
            policyEditHeld = true
            appPolicyEpoch++
            appPolicyJob?.cancel()
            appPolicyJob = null
            policyResolutionComplete = false
            app.rootSessionDatabase.setAppPolicyEditPending(true, ProcessingAppPolicy.uidSet(values[ProcessingAppPolicy.EDIT_UIDS]))
            return
        }
        if (lastCompletedPolicyEpoch != completedEpoch) {
            // A fast IO commit can finish before Main handles the first key callback.
            // Its completed epoch still makes the final Room snapshot an atomic scope barrier.
            expectedExcludedUids = if (ProcessingAppPolicy.EXPECTED_EXCLUDED_UIDS in values)
                ProcessingAppPolicy.uidSet(values[ProcessingAppPolicy.EXPECTED_EXCLUDED_UIDS]) else null
            policyEditHeld = true
            app.rootSessionDatabase.setAppPolicyEditPending(true, ProcessingAppPolicy.uidSet(values[ProcessingAppPolicy.EDIT_UIDS]))
        }
        if (expectedExcludedUids?.let { it != blockedUids.toSet() } == true) {
            policyEditHeld = true
            app.rootSessionDatabase.setAppPolicyEditPending(true)
            return
        }
        // This expected snapshot is a one-shot transaction barrier, never a permanent Room filter.
        expectedExcludedUids = null
        lastCompletedPolicyEpoch = completedEpoch
        val request = ProcessingAppPolicy.request(preferences.preferences, blockedUids)
        if (request == appPolicyRequest && !forceResolve && invalidatedAppPackages.isEmpty() &&
            (policyResolutionComplete || appPolicyJob?.isActive == true)) {
            if (policyEditHeld) {
                policyEditHeld = false
                app.rootSessionDatabase.setAppPolicyEditPending(false)
                discoveryWakeups.trySend(Unit)
            }
            return
        }
        appPolicyRequest = request
        val epoch = ++appPolicyEpoch
        appPolicyJob?.cancel()
        policyResolutionComplete = false
        val invalidated = invalidatedAppPackages.toSet()
        invalidatedAppPackages.clear()
        // Remove newly disallowed sessions now; retain every already resolved, eligible effect.
        val confirmedOwners = app.rootSessionDatabase.sessionList.values
            .filter { it.uid >= 0 && it.packageName != packageName && it.packageName !in invalidated }
            .associate { it.packageName to it.uid }
        val cachedPolicy = app.rootSessionDatabase.processingAppPolicy.let {
            it.copy(allowedPackageUids = it.allowedPackageUids.filterKeys { name -> name !in invalidated })
        }
        app.rootSessionDatabase.applyAppPolicy(request.cached(cachedPolicy, confirmedOwners))
        policyEditHeld = false
        app.rootSessionDatabase.setAppPolicyEditPending(false)
        discoveryWakeups.trySend(Unit)
        updateUsbFollowMonitoring()
        appPolicyJob = usbFollowScope.launch {
            val resolved = withContext(Dispatchers.IO) { request.resolve(this@RootAudioProcessorService) }
            if (!isActive || isServiceDisposing || appPolicyEpoch != epoch) return@launch
            policyResolutionComplete = true
            app.rootSessionDatabase.applyAppPolicy(resolved)
            discoveryWakeups.trySend(Unit)
            updateUsbFollowMonitoring()
            updateServiceNotification()
        }
    }

    private fun applyProcessingMode() {
        if(isServiceDisposing)return
        val dynamic=app.isPlayerSessionMode
        if(attachmentMode==dynamic && attachmentJob?.isActive==true) {
            updateServiceNotification()
            return
        }
        attachmentMode=dynamic
        val epoch=++attachmentEpoch
        attachmentJob?.cancel()
        app.rootSessionDatabase.applyProcessingMode(dynamic)
        attachmentJob=usbFollowScope.launch {
            while(isActive && !isServiceDisposing && attachmentEpoch==epoch) {
                if(dynamic && (!blocklistReady || !app.rootSessionDatabase.processingAppPolicy.ready)) {
                    withTimeoutOrNull(750L) {discoveryWakeups.receive()}
                    continue
                }
                if(!dynamic)app.rootSessionDatabase.ensureGlobalSession()
                // One read-only root snapshot supplies both player SIDs and global-chain evidence.
                val snapshot=withContext(Dispatchers.IO) {
                    activePlaybackDiscovery.discover(!dynamic)
                }
                if(!isActive || attachmentEpoch!=epoch || app.isPlayerSessionMode!=dynamic)break
                if(snapshot!=null) {
                    if(dynamic)app.rootSessionDatabase.reconcileActivePlayers(snapshot.players, snapshot.processes)
                    else app.rootSessionDatabase.reconcileGlobalPlayback(snapshot)
                } else {
                    // Unreadable discovery never authorizes a guessed SID or global fallback.
                    app.rootSessionDatabase.playbackEvidenceUnavailable()
                }
                withTimeoutOrNull(if(dynamic)750L else 1000L) {discoveryWakeups.receive()}
            }
        }
        updateServiceNotification()
    }

    override fun onSessionChanged(sessionList: HashMap<Int, IEffectSession>) {
        updateServiceNotification()
        updateUsbFollowMonitoring()
    }

    override fun onRoutingDeviceChanged(device: RoutingObserver.Device?) {
        // This is independent of device-profile switching and USB session rebuilds.
        me.timschneeberger.rootlessjamesdsp.expansion.SpeakerRouteBridge.update(this, routingObserver.mediaOutputEvidence)
        val next=device?.let {"${it.id}:${it.endpointId}"}
        val changed=routeObserved && routeFingerprint!=next
        val usb=previousRouteGroup==RoutingObserver.DeviceGroup.USB || device?.group==RoutingObserver.DeviceGroup.USB
        routeObserved=true
        routeFingerprint=next
        previousRouteGroup=device?.group
        if(changed && (usb || !app.isPlayerSessionMode) && !isServiceDisposing) {
            routeHandler.removeCallbacks(refreshUsbRoute)
            routeHandler.postDelayed(refreshUsbRoute,200)
        }
        updateUsbFollowMonitoring()
    }

    private fun updateUsbFollowMonitoring() {
        val enabled=app.rootSessionDatabase.usesPlayerAttachments
        if(isServiceDisposing || !enabled) {
            usbFollowJob?.cancel();usbFollowJob=null
            UsbAudioFollowStatus.publish(usbFollow.update(enabled,true,emptyList()).status)
            return
        }
        if(usbFollowJob?.isActive==true)return
        UsbAudioFollowStatus.publish(usbFollow.update(true,true,emptyList()).status)
        usbFollowJob=usbFollowScope.launch {
            while(isActive && !isServiceDisposing) {
                // Player mode follows attached effects on every output, including system USB.
                val effects=app.rootSessionDatabase.sessionList.values.toList()
                    .mapNotNull {(it as? RemoteEffectSession)?.effect}
                val readings=withContext(Dispatchers.IO) {
                    effects.mapNotNull {runCatching {it.outputRuntimeSession()}.getOrNull()}
                }
                if(!isActive)break
                val decision=usbFollow.update(true,true,readings)
                UsbAudioFollowStatus.publish(decision.status)
                if(decision.synchronize) {
                    // Only driver host rate plus increasing processed frames can reach this branch.
                    app.rootSessionDatabase.synchronizeUsbSampleRate()
                    discoveryWakeups.trySend(Unit)
                    sendLocalBroadcast(Intent(Constants.ACTION_SAMPLE_RATE_UPDATED))
                }
                delay(750)
            }
        }
    }

    private fun updateServiceNotification() {
        if(app.isLegacyMode)
            ServiceNotificationHelper.pushServiceNotificationLegacy(this)
        else
            ServiceNotificationHelper.pushServiceNotification(this, app.rootSessionDatabase.sessionList.values.toTypedArray())
    }

    companion object {
        const val ACTION_START_ENHANCED_PROCESSING = BuildConfig.APPLICATION_ID + ".root.service.START_ENHANCED"
        const val ACTION_STOP = BuildConfig.APPLICATION_ID + ".root.service.STOP"
        const val ACTION_REFRESH_PROCESSING_MODE = BuildConfig.APPLICATION_ID + ".root.service.REFRESH_MODE"

        fun refreshProcessingMode(context: Context) {
            startService(context,Intent(context,RootAudioProcessorService::class.java)
                .setAction(ACTION_REFRESH_PROCESSING_MODE))
        }

        fun startService(context: Context, intent: Intent) {
            Timber.d("startService: intent=$intent")

            // Prevent launch if plugin missing
            if(JamesDspRemoteEngine.isPluginInstalled(context) in arrayOf(
                    JamesDspRemoteEngine.PluginState.Unavailable, JamesDspRemoteEngine.PluginState.Unsupported)) {
                Timber.e("Service launch cancelled. Plugin not installed.")
                return
            }

            try {
                sdkAbove(Build.VERSION_CODES.O) {
                    context.startForegroundService(intent)
                }.below {
                    context.startService(intent)
                }
            }
            catch (ex: Exception) {
                Timber.e("Failed to start service. Try to disable battery optimizations")
                Timber.e(ex)
            }
        }

        fun startServiceEnhanced(context: Context) {
            try {
                Intent(context, RootAudioProcessorService::class.java)
                    .apply { this.action = ACTION_START_ENHANCED_PROCESSING }
                    .run { startService(context, this) }
            }
            catch (ex: Exception) {
                Timber.e("Failed to start service. Try to disable battery optimizations")
                Timber.e(ex)
            }
        }

        fun stopService(context: Context) {
            try {
                Intent(context, RootAudioProcessorService::class.java)
                    .apply { this.action = ACTION_STOP }
                    .run { startService(context, this) }
            }
            catch(ex: Exception) {
                Timber.e(ex)
            }
        }

        fun updateLegacyMode(context: Context, enable: Boolean) {
            Timber.d("updateLegacyMode: enable=$enable")
            refreshProcessingMode(context)
        }
    }
}
