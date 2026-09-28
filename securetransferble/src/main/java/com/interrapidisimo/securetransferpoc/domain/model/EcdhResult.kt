package com.interrapidisimo.securetransferpoc.domain.model

data class EcdhResult(
    val publicKeyA: String,
    val publicKeyB: String,
    val secretFingerprintA: String,
    val secretFingerprintB: String,
    val sameSecret: Boolean,

    val sessionId: String,
    val sessionKeyFingerprintA: String,
    val sessionKeyFingerprintB: String,
    val sameSessionKey: Boolean,

    val iv: String,
    val encryptedMessage: String,
    val decryptedMessage: String,
    val decryptionSuccessful: Boolean,
    val modifiedMessageWasRejected: Boolean
)