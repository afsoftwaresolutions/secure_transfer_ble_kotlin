package com.interrapidisimo.securetransferpoc.domain.model

data class ReceiverHandshakeVerificationResult(
    val isValid: Boolean,
    val message: String,
    val handshake: ReceiverHandshake? = null
)
