package com.interrapidisimo.securetransferpoc.domain.model

data class ReceiverKeyAgreementResult(
    val sessionId: String,
    val receiverEphemeralPublicKey: String,
    val sessionKeyFingerprint: String
)