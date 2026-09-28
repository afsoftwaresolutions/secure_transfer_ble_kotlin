package com.interrapidisimo.securetransferpoc.domain.repository

import com.interrapidisimo.securetransferpoc.domain.model.SessionInvitation

interface SessionInvitationRepository {

    suspend fun createSenderInvitation(): SessionInvitation

    suspend fun verifyInvitation(
        invitation: SessionInvitation
    ): Boolean

    fun encodeToJson(
        invitation: SessionInvitation
    ): String

    fun decodeFromJson(
        invitationJson: String
    ): SessionInvitation

    fun isActiveSession(sessionId: String): Boolean

    fun clearSession(sessionId: String)

    fun calculateSenderSharedSecret(
        sessionId: String,
        receiverEphemeralPublicKey: String
    ): ByteArray
}