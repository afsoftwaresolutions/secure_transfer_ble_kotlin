package com.interrapidisimo.securetransferpoc.domain.model

data class SignedInvitationResult(
    val sessionId: String,
    val invitationJson: String,
    val signatureValid: Boolean
)