package com.interrapidisimo.securetransferpoc.domain.model

data class EncryptedTransferEnvelope(
    val protocolVersion: Int,
    val sessionId: String,
    val messageId: String,
    val iv: String,
    val cipherText: String
)
