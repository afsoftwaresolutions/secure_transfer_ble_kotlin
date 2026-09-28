package com.interrapidisimo.securetransferpoc.domain.model

data class ScannedInvitationResult(
    val isValid: Boolean,
    val message: String,
    val sessionId: String? = null,
    val sourceApp: String? = null,
    val sourceDeviceId: String? = null,
    val senderEphemeralPublicKey: String? = null
)