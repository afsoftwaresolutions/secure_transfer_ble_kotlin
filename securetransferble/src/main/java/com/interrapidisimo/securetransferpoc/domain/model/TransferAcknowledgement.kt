package com.interrapidisimo.securetransferpoc.domain.model

data class TransferAcknowledgement(
    val protocolVersion: Int,
    val sessionId: String,
    val acknowledgedMessageId: String,
    val duplicate: Boolean
)
