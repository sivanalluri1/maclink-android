package com.sivanalluri.maclink.companion.pairing

import android.content.Context
import java.util.UUID

data class PairedMacRecord(
    val deviceId: UUID,
    val deviceName: String,
    val publicKeyDer: ByteArray,
    val pairedAt: Long,
)

class PairedMacStore(context: Context) {
    private val preferences = context.applicationContext.getSharedPreferences(
        "paired_macs",
        Context.MODE_PRIVATE,
    )

    fun save(record: PairedMacRecord) {
        val prefix = record.deviceId.toString().lowercase()
        preferences.edit()
            .putString("$prefix.name", record.deviceName)
            .putString("$prefix.public_key", record.publicKeyDer.base64Url())
            .putLong("$prefix.paired_at", record.pairedAt)
            .apply()
    }

    fun load(deviceId: UUID): PairedMacRecord? {
        val prefix = deviceId.toString().lowercase()
        val name = preferences.getString("$prefix.name", null) ?: return null
        val publicKey = preferences.getString("$prefix.public_key", null)?.decodeBase64Url() ?: return null
        return PairedMacRecord(
            deviceId = deviceId,
            deviceName = name,
            publicKeyDer = publicKey,
            pairedAt = preferences.getLong("$prefix.paired_at", 0),
        )
    }
}
