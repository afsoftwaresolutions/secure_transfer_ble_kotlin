package com.interrapidisimo.securetransferpoc.domain.model

data class SenderKeyAgreementResult(
    val sessionId: String,
    val sessionKeyFingerprint: String
)
