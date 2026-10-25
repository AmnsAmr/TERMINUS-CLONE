package com.necroware.terminusplayer.playback

import android.content.Context
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Handler
import android.os.Looper
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class OutputDeviceType {
    SPEAKER,
    WIRED,
    USB,
    BLUETOOTH,
    UNKNOWN
}

data class OutputAudioDevice(
    val id: String,
    val displayName: String,
    val type: OutputDeviceType
)

/**
 * Monitors and resolves the currently active audio output route (Speaker, Wired, USB, Bluetooth)
 * without requiring BLUETOOTH_CONNECT or any runtime permissions.
 */
class AudioOutputDeviceManager(private val context: Context) {

    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
    private val mainHandler = Handler(Looper.getMainLooper())

    private val _currentOutputDevice = MutableStateFlow(resolveCurrentDevice())
    val currentOutputDevice: StateFlow<OutputAudioDevice> = _currentOutputDevice.asStateFlow()

    private val deviceCallback = object : AudioDeviceCallback() {
        override fun onAudioDevicesAdded(addedDevices: Array<out AudioDeviceInfo>?) {
            updateDevice()
        }

        override fun onAudioDevicesRemoved(removedDevices: Array<out AudioDeviceInfo>?) {
            updateDevice()
        }
    }

    private var isRegistered = false

    fun start() {
        if (!isRegistered && audioManager != null) {
            try {
                audioManager.registerAudioDeviceCallback(deviceCallback, mainHandler)
                isRegistered = true
                updateDevice()
            } catch (_: Exception) {
                // Fallback to initial device state if callback registration fails
            }
        }
    }

    fun stop() {
        if (isRegistered && audioManager != null) {
            try {
                audioManager.unregisterAudioDeviceCallback(deviceCallback)
            } catch (_: Exception) {
            } finally {
                isRegistered = false
            }
        }
    }

    private fun updateDevice() {
        _currentOutputDevice.value = resolveCurrentDevice()
    }

    fun resolveCurrentDevice(): OutputAudioDevice {
        val am = audioManager ?: return OutputAudioDevice(
            id = DEVICE_ID_SPEAKER,
            displayName = "Built-in Speaker",
            type = OutputDeviceType.SPEAKER
        )
        val devices = try {
            am.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
        } catch (_: Exception) {
            emptyArray()
        }
        return resolveOutputDeviceFromList(devices)
    }

    companion object {
        const val DEVICE_ID_SPEAKER = "builtin_speaker"
        const val DEVICE_ID_WIRED = "wired"
        const val DEVICE_ID_USB = "usb"
        const val DEVICE_ID_BT_PREFIX = "bt:"

        fun resolveOutputDeviceFromList(devices: Array<AudioDeviceInfo>): OutputAudioDevice {
            // 1. Check for Bluetooth outputs (prioritized when connected)
            val btDevice = devices.firstOrNull { it.isSink && isBluetoothDevice(it.type) }
            if (btDevice != null) {
                val productName = runCatching { btDevice.productName?.toString()?.trim() }.getOrNull()
                    ?.takeIf { it.isNotBlank() } ?: "Bluetooth Audio"
                return OutputAudioDevice(
                    id = "$DEVICE_ID_BT_PREFIX$productName",
                    displayName = productName,
                    type = OutputDeviceType.BLUETOOTH
                )
            }

            // 2. Check for Wired or AUX headset
            val wiredDevice = devices.firstOrNull { it.isSink && isWiredDevice(it.type) }
            if (wiredDevice != null) {
                return OutputAudioDevice(
                    id = DEVICE_ID_WIRED,
                    displayName = "Wired Headphones",
                    type = OutputDeviceType.WIRED
                )
            }

            // 3. Check for USB audio headset / DAC
            val usbDevice = devices.firstOrNull { it.isSink && isUsbDevice(it.type) }
            if (usbDevice != null) {
                val usbName = runCatching { usbDevice.productName?.toString()?.trim() }.getOrNull()
                    ?.takeIf { it.isNotBlank() } ?: "USB Audio"
                return OutputAudioDevice(
                    id = DEVICE_ID_USB,
                    displayName = usbName,
                    type = OutputDeviceType.USB
                )
            }

            // 4. Fallback to Built-in Speaker
            return OutputAudioDevice(
                id = DEVICE_ID_SPEAKER,
                displayName = "Built-in Speaker",
                type = OutputDeviceType.SPEAKER
            )
        }

        private fun isBluetoothDevice(type: Int): Boolean = when (type) {
            AudioDeviceInfo.TYPE_BLUETOOTH_A2DP,
            AudioDeviceInfo.TYPE_BLUETOOTH_SCO,
            AudioDeviceInfo.TYPE_HEARING_AID -> true
            26, 27, 30 -> true // TYPE_BLE_HEADSET (26), TYPE_BLE_SPEAKER (27), TYPE_BLE_BROADCAST (30)
            else -> false
        }

        private fun isWiredDevice(type: Int): Boolean = when (type) {
            AudioDeviceInfo.TYPE_WIRED_HEADPHONES,
            AudioDeviceInfo.TYPE_WIRED_HEADSET,
            AudioDeviceInfo.TYPE_LINE_ANALOG,
            AudioDeviceInfo.TYPE_LINE_DIGITAL,
            AudioDeviceInfo.TYPE_AUX_LINE -> true
            else -> false
        }

        private fun isUsbDevice(type: Int): Boolean = when (type) {
            AudioDeviceInfo.TYPE_USB_DEVICE,
            AudioDeviceInfo.TYPE_USB_HEADSET,
            AudioDeviceInfo.TYPE_USB_ACCESSORY -> true
            else -> false
        }
    }
}
