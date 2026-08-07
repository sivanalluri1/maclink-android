package com.sivanalluri.maclink.companion.connection

import java.util.UUID

data class DevicePresence(
    val deviceId: UUID,
    val deviceName: String,
    val appVersion: String,
) {
    init {
        require(deviceName.isNotBlank() && deviceName.length <= MAXIMUM_DEVICE_NAME_LENGTH)
        require(appVersion.isNotBlank() && appVersion.length <= MAXIMUM_APP_VERSION_LENGTH)
    }

    fun toJsonLine(): String = buildString {
        append('{')
        append("\"kind\":\"device_presence\",")
        append("\"version\":1,")
        append("\"deviceId\":").append(deviceId.toString().jsonString()).append(',')
        append("\"deviceName\":").append(deviceName.trim().jsonString()).append(',')
        append("\"platform\":\"android\",")
        append("\"appVersion\":").append(appVersion.jsonString())
        append("}\n")
    }

    companion object {
        const val MAXIMUM_DEVICE_NAME_LENGTH = 80
        const val MAXIMUM_APP_VERSION_LENGTH = 32
    }
}

private fun String.jsonString(): String = buildString {
    append('"')
    this@jsonString.forEach { character ->
        when (character) {
            '"' -> append("\\\"")
            '\\' -> append("\\\\")
            '\b' -> append("\\b")
            '\u000C' -> append("\\f")
            '\n' -> append("\\n")
            '\r' -> append("\\r")
            '\t' -> append("\\t")
            else -> if (character.code < 0x20) {
                append("\\u").append(character.code.toString(16).padStart(4, '0'))
            } else {
                append(character)
            }
        }
    }
    append('"')
}
