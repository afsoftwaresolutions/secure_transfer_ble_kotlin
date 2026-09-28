package com.interrapidisimo.securetransferpoc.domain.model

data class SignedReceiverHandshakeResult(
    val handshake: ReceiverHandshake,
    val handshakeJson: String
)
