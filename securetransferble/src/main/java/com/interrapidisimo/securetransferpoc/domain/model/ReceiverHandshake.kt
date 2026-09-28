package com.interrapidisimo.securetransferpoc.domain.model

import java.io.ByteArrayOutputStream
import java.io.DataOutputStream

data class ReceiverHandshake(
    val protocolVersion: Int,
    val sessionId: String,
    val receiverApp: String,
    val createdAtEpochMillis: Long,
    val nonce: String,
    val identityPublicKey: String,
    val ephemeralPublicKey: String,
    val signature: String
) {

    fun signingBytes(): ByteArray {
        val output = ByteArrayOutputStream()

        DataOutputStream(output).use { data ->
            data.writeInt(protocolVersion)
            data.writeString(sessionId)
            data.writeString(receiverApp)
            data.writeLong(createdAtEpochMillis)
            data.writeString(nonce)
            data.writeString(identityPublicKey)
            data.writeString(ephemeralPublicKey)
        }

        return output.toByteArray()
    }

    private fun DataOutputStream.writeString(
        value: String
    ) {
        val bytes = value.encodeToByteArray()

        writeInt(bytes.size)
        write(bytes)
    }
}
