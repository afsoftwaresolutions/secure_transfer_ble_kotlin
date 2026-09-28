package com.interrapidisimo.securetransferpoc.domain.model


data class SessionInvitation(
    val protocolVersion: Int,
    val sessionId: String,
    val sourceApp: String,
    val sourceDeviceId: String,
    val createdAtEpochMillis: Long,
    val expiresAtEpochMillis: Long,
    val nonce: String,
    val identityPublicKey: String,
    val ephemeralPublicKey: String,
    val signature: String
) {

    fun signingBytes(): ByteArray {
        val fields = listOf(
            protocolVersion.toString(),
            sessionId,
            sourceApp,
            sourceDeviceId,
            createdAtEpochMillis.toString(),
            expiresAtEpochMillis.toString(),
            nonce,
            identityPublicKey,
            ephemeralPublicKey
        )

        return fields.joinToString(separator = "|") { value ->
            val byteLength = value.encodeToByteArray().size
            "$byteLength:$value"
        }.encodeToByteArray()
    }
}