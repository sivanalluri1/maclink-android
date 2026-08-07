package com.sivanalluri.maclink.companion.connection

import android.content.Context
import android.os.Build
import android.provider.Settings
import java.util.UUID

class PhoneIdentityStore(context: Context) {
    private val applicationContext = context.applicationContext
    private val preferences = applicationContext.getSharedPreferences("phone_identity", Context.MODE_PRIVATE)

    fun deviceId(): UUID {
        preferences.getString(DEVICE_ID_KEY, null)?.let { stored ->
            runCatching { UUID.fromString(stored) }.getOrNull()?.let { return it }
        }

        return UUID.randomUUID().also { generated ->
            preferences.edit().putString(DEVICE_ID_KEY, generated.toString()).apply()
        }
    }

    fun deviceName(): String {
        val configuredName = Settings.Global.getString(
            applicationContext.contentResolver,
            Settings.Global.DEVICE_NAME,
        )
        return configuredName
            ?.trim()
            ?.takeIf(String::isNotEmpty)
            ?.take(DevicePresence.MAXIMUM_DEVICE_NAME_LENGTH)
            ?: "${Build.MANUFACTURER} ${Build.MODEL}"
                .trim()
                .take(DevicePresence.MAXIMUM_DEVICE_NAME_LENGTH)
                .ifEmpty { "Android phone" }
    }

    private companion object {
        const val DEVICE_ID_KEY = "device_id"
    }
}
