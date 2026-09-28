package com.interrapidisimo.securetransferpoc.domain.model

data class ChallengeProofResult(
    val challenge: String,
    val signature: String,
    val isValid: Boolean,
    val modifiedChallengeWasRejected: Boolean
)
