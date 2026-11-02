package me.timschneeberger.rootlessjamesdsp.utils

import android.annotation.SuppressLint
import android.content.Context
import android.content.SharedPreferences
import android.content.res.Resources
import android.media.AudioDeviceInfo
import android.media.AudioDeviceCallback
import android.media.AudioManager
import android.media.AudioAttributes
import android.media.AudioPlaybackConfiguration
import android.os.Build
import android.os.Handler
import android.os.Looper
import androidx.annotation.StringRes
import androidx.core.content.getSystemService
import androidx.mediarouter.media.MediaControlIntent
import androidx.mediarouter.media.MediaRouteSelector
import androidx.mediarouter.media.MediaRouter
import me.timschneeberger.rootlessjamesdsp.R
import me.timschneeberger.rootlessjamesdsp.utils.RoutingObserver.DeviceGroup.Companion.usesSingleProfile
import me.timschneeberger.rootlessjamesdsp.utils.preferences.Preferences
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject
import timber.log.Timber
import kotlin.properties.Delegates


class RoutingObserver(val context: Context) : MediaRouter.Callback(), KoinComponent,
    SharedPreferences.OnSharedPreferenceChangeListener {

    private val callbacks = mutableListOf<RoutingChangedCallback>()
    private val router by lazy { MediaRouter.getInstance(context) }
    private val prefs: Preferences.App by inject()
    private val audioManager by lazy {context.getSystemService<AudioManager>()!!}
    private val routeHandler=Handler(Looper.getMainLooper())
    private val refreshRoute=Runnable {refreshSelectedRoute()}
    private val deviceCallback=object: AudioDeviceCallback() {
        override fun onAudioDevicesAdded(addedDevices: Array<out AudioDeviceInfo>?)=scheduleRouteRefresh()
        override fun onAudioDevicesRemoved(removedDevices: Array<out AudioDeviceInfo>?)=scheduleRouteRefresh()
    }
    private val playbackCallback=object: AudioManager.AudioPlaybackCallback() {
        override fun onPlaybackConfigChanged(configs: MutableList<AudioPlaybackConfiguration>?)=scheduleRouteRefresh()
    }

    var currentDevice by Delegates.observable<Device?>(null) { _, old, new ->
        if (old != new)
            callbacks.toList().forEach { it.onRoutingDeviceChanged(new) }
    }
    @Volatile var mediaOutputEvidence = MediaOutputEvidence.UNKNOWN
        private set

    private val AudioDeviceInfo.deviceGroup: DeviceGroup
        get() = DeviceGroup.from(type)

    private fun MediaRouter.RouteInfo.findOutputDevice(): Device? {
        mediaOutputEvidence = MediaOutputEvidence.UNKNOWN
        // System media routing is authoritative. Merely being a connected USB device does not
        // mean an exclusive player or the current AudioFlinger stream is using that device.
        actualMediaOutput()?.let {return Device(it.productName.toString(),it.address,it.deviceGroup,it.id)}
        return context.getSystemService<AudioManager>()!!
            .getDevices(AudioManager.GET_DEVICES_OUTPUTS)
            .filter(AudioDeviceInfo::isSink)
            .find { info ->
                info.deviceGroup.let {
                    when {
                        isDeviceSpeaker -> it == DeviceGroup.SPEAKER
                        isBluetooth -> it == DeviceGroup.BLUETOOTH
                        isUsb -> it == DeviceGroup.USB
                        isHeadphone -> it == DeviceGroup.ANALOG
                        isHdmi -> it == DeviceGroup.HDMI
                        else -> it == DeviceGroup.OTHER
                    }
                }
            }?.let { device ->
                Device(
                    device.productName.toString(),
                    device.address,
                    device.deviceGroup,
                    device.id
                )
            }
    }

    private fun actualMediaOutput(): AudioDeviceInfo? {
        if(Build.VERSION.SDK_INT>=33) {
            runCatching {
                val outputs = audioManager.getAudioDevicesForAttributes(AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build())
                    .filter(AudioDeviceInfo::isSink).distinctBy {it.id}
                mediaOutputEvidence = MediaOutputEvidence.fromOutputTypes(outputs.map { it.type }, AudioDeviceInfo.TYPE_BUILTIN_SPEAKER)
                outputs.singleOrNull()
            }.getOrNull()?.let {return it}
        }
        // audioDeviceInfo was not populated on older Android. Fall back to the selected
        // system MediaRouter route, never to a merely connected USB device.
        return null
    }

    private fun scheduleRouteRefresh() {
        routeHandler.removeCallbacks(refreshRoute)
        // Device discovery can precede the policy-manager route switch; observe both moments.
        routeHandler.post(refreshRoute)
        routeHandler.postDelayed(refreshRoute,150)
        routeHandler.postDelayed(refreshRoute,650)
    }

    private fun refreshSelectedRoute() {
        val beforeEvidence = mediaOutputEvidence
        val next=runCatching {router.selectedRoute.findOutputDevice()}
            .onFailure {
                mediaOutputEvidence = MediaOutputEvidence.UNKNOWN
                Timber.w(it,"Unable to identify the selected output route")
            }.getOrNull()
        val before=currentDevice
        if(before?.id==next?.id && before?.endpointId==next?.endpointId && before?.name==next?.name && beforeEvidence==mediaOutputEvidence)return
        currentDevice=next
        if(before == null && next == null && beforeEvidence != mediaOutputEvidence)
            callbacks.toList().forEach { it.onRoutingDeviceChanged(null) }
    }

    private val MediaRouter.RouteInfo.isHdmi: Boolean
        @SuppressLint("DiscouragedApi")
        /*
            Breaking changes in Android 14:
            - 'HDMI' was replaced with 'External Device'
            - resource 'default_audio_route_name_hdmi' was renamed to 'default_audio_route_name_external_device'
            https://cs.android.com/android/_/android/platform/frameworks/base/+/376bae463a9e46437047efbd082c7ac56e1ced3f
         */
        get() = try {
            Resources.getSystem().getText(
                Resources.getSystem().getIdentifier(
                    sdkAbove(34 /* FIXME Build.VERSION_CODES.UPSIDE_DOWN_CAKE */) {
                        "default_audio_route_name_external_device"
                    }.below {
                        "default_audio_route_name_hdmi"
                    },
                    "string", "android"
                )
            ).let { hdmiName ->
                router.defaultRoute == this && hdmiName == name
            }
        }
        catch (ex: Resources.NotFoundException) {
            Timber.e("HDMI route name string not found")
            Timber.i(ex)
            // Fallback to unsafe check
            router.defaultRoute == this && name.contains("HDMI")
        }


    private val MediaRouter.RouteInfo.isUsb: Boolean
        @SuppressLint("DiscouragedApi")
        get() = runCatching {sdkAbove(Build.VERSION_CODES.Q) {
            Resources.getSystem().getText(
                Resources.getSystem().getIdentifier(
                    "default_audio_route_name_usb", "string", "android"
                )
            )
        }.below {
            ""
        }.let { usbName ->
            router.defaultRoute == this && usbName == name
        }}.getOrDefault(false)

    private val MediaRouter.RouteInfo.isHeadphone: Boolean
        @SuppressLint("DiscouragedApi")
        get() = runCatching {Resources.getSystem().getText(
            Resources.getSystem().getIdentifier(
                "default_audio_route_name_headphones", "string", "android"
            )
        ).let { hpName ->
            router.defaultRoute == this && hpName == name
        }}.getOrDefault(false)

    override fun onSharedPreferenceChanged(sharedPreferences: SharedPreferences?, key: String?) {
        when(key) {
            context.getString(R.string.key_device_profiles_enable) -> {
                if(prefs.get<Boolean>(R.string.key_device_profiles_enable))
                    retrigger()
            }
        }
    }

    override fun onRouteSelected(router: MediaRouter, route: MediaRouter.RouteInfo, reason: Int) {
        refreshSelectedRoute()
    }

    override fun onRouteChanged(router: MediaRouter, route: MediaRouter.RouteInfo) {
        refreshSelectedRoute()
    }

    override fun onRouteRemoved(router: MediaRouter, route: MediaRouter.RouteInfo) {
        if(router.selectedRoute===route)router.unselect(MediaRouter.UNSELECT_REASON_DISCONNECTED)
        scheduleRouteRefresh()
    }

    fun retrigger() {
        callbacks.toList().forEach { callback -> currentDevice?.let(callback::onRoutingDeviceChanged) }
    }

    fun registerOnRoutingChangeListener(callback: RoutingChangedCallback) {
        if (callbacks.isEmpty()) {
            prefs.registerOnSharedPreferenceChangeListener(this)
            router.addCallback(
                MediaRouteSelector.Builder()
                    .addControlCategory(MediaControlIntent.CATEGORY_LIVE_AUDIO)
                    .build(),
                this,
                0
            )
            audioManager.registerAudioDeviceCallback(deviceCallback,routeHandler)
            audioManager.registerAudioPlaybackCallback(playbackCallback,routeHandler)
        }
        // A device may already be connected before the first observer is registered.
        refreshSelectedRoute()
        callbacks.add(callback)
        callback.onRoutingDeviceChanged(currentDevice)
    }

    fun unregisterOnRoutingChangeListener(callback: RoutingChangedCallback) {
        callbacks.remove(callback)

        if (callbacks.isEmpty()) {
            prefs.unregisterOnSharedPreferenceChangeListener(this)
            router.removeCallback(this)
            audioManager.unregisterAudioDeviceCallback(deviceCallback)
            audioManager.unregisterAudioPlaybackCallback(playbackCallback)
            routeHandler.removeCallbacks(refreshRoute)
        }
    }

    interface RoutingChangedCallback {
        fun onRoutingDeviceChanged(device: Device?)
    }

    inner class Device(
        private val productName: String,
        private val address: String,
        val group: DeviceGroup,
        val endpointId: Int=0,
    ) {
        init {
            // Log unexpected events
            if(!group.usesSingleProfile() && !hasProductName()) {
                Timber.e(IllegalStateException("Device has no valid product name: $this"))
            }
        }

        val id: String
            get() = if(group.usesSingleProfile())
                group.name.lowercase()
            else
                "${group.name.lowercase()}_${(if(group == DeviceGroup.BLUETOOTH) address else productName).lowercase().sanitize()}"
        val name: String
            // If single profile, only use generic name
            get() = if(group.usesSingleProfile())
                context.getString(group.nameRes)
            // If Bluetooth device without address, resort to single profile
            else if((group == DeviceGroup.BLUETOOTH && address.isBlank()))
                "${context.getString(group.nameRes)} (${context.getString(R.string.group_unknown)})"
            else {
                "${if(hasProductName()) productName else address} (${context.getString(group.nameRes)})".let {
                    // Cleanup USB driver name prefix
                    if(group == DeviceGroup.USB)
                        it.replace("USB-Audio - ", "")
                    else
                        it
                }
            }


        override fun toString() = "Device(id=$id, group=$group, name='$name', productName='$productName', address='$address')"
        private fun hasProductName(): Boolean {
            // Special case: you can connect two phones of the same model name via bluetooth
            return (productName != Build.MODEL || group == DeviceGroup.BLUETOOTH) && productName.isNotEmpty()
        }
        private fun String.sanitize() = this.replace("[ \\;/:*?\"<>|&']".toRegex(),"_")
    }

    enum class DeviceGroup(@StringRes val nameRes: Int) {
        /* WARNING: Names of existing enum members are used as stable ids and must not be changed */
        ANALOG(R.string.group_wired_headphones),
        BLUETOOTH(R.string.group_bluetooth),
        HDMI(R.string.group_hdmi),
        SPEAKER(R.string.group_speaker),
        USB(R.string.group_usb),
        OTHER(R.string.group_unknown);

        companion object {
            fun DeviceGroup.usesSingleProfile() = this == ANALOG || this == SPEAKER || this == OTHER

            fun from(type: Int) = when (type) {
                AudioDeviceInfo.TYPE_BUILTIN_SPEAKER -> SPEAKER
                AudioDeviceInfo.TYPE_WIRED_HEADSET,
                AudioDeviceInfo.TYPE_LINE_ANALOG,
                AudioDeviceInfo.TYPE_WIRED_HEADPHONES -> ANALOG
                AudioDeviceInfo.TYPE_BLUETOOTH_A2DP,
                AudioDeviceInfo.TYPE_BLE_HEADSET,
                AudioDeviceInfo.TYPE_BLE_SPEAKER -> BLUETOOTH
                AudioDeviceInfo.TYPE_USB_DEVICE,
                AudioDeviceInfo.TYPE_USB_ACCESSORY,
                AudioDeviceInfo.TYPE_USB_HEADSET -> USB
                AudioDeviceInfo.TYPE_HDMI_ARC,
                AudioDeviceInfo.TYPE_HDMI_EARC,
                AudioDeviceInfo.TYPE_HDMI -> HDMI
                else -> OTHER
            }
        }
    }
}
