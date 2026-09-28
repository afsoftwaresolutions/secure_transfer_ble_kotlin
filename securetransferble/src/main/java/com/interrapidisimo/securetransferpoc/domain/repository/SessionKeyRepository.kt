package com.interrapidisimo.securetransferpoc.domain.repository

import com.interrapidisimo.securetransferpoc.domain.model.EcdhResult
import com.interrapidisimo.securetransferpoc.domain.model.EncryptedTransferEnvelope
import com.interrapidisimo.securetransferpoc.domain.model.ReceiverKeyAgreementResult
import com.interrapidisimo.securetransferpoc.domain.model.SenderKeyAgreementResult

enum class SessionMessagePurpose(val suffix: String) {
    LEGACY(""),
    REVERSE_DATA("|REVERSE_DATA"),
    REVERSE_ACK("|REVERSE_ACK")
}

interface SessionKeyRepository {

    suspend fun demonstrateKeyAgreement(): EcdhResult

    suspend fun prepareReceiverSession(
        sessionId: String,
        senderEphemeralPublicKey: String
    ): ReceiverKeyAgreementResult

    suspend fun completeSenderSession(
        sessionId: String,
        receiverEphemeralPublicKey: String
    ): SenderKeyAgreementResult

    suspend fun encryptSessionMessage(
        sessionId: String,
        messageId: String,
        plainText: String,
        purpose: SessionMessagePurpose = SessionMessagePurpose.LEGACY
    ): EncryptedTransferEnvelope

    suspend fun decryptSessionMessage(
        envelope: EncryptedTransferEnvelope,
        purpose: SessionMessagePurpose = SessionMessagePurpose.LEGACY
    ): String

    fun hasSessionKey(sessionId: String): Boolean

    fun clearSession(sessionId: String)
}