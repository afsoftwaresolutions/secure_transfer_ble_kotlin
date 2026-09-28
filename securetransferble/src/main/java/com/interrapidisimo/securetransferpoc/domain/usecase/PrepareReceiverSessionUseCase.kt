package com.interrapidisimo.securetransferpoc.domain.usecase

import com.interrapidisimo.securetransferpoc.domain.model.ReceiverKeyAgreementResult
import com.interrapidisimo.securetransferpoc.domain.repository.SessionKeyRepository
import javax.inject.Inject

class PrepareReceiverSessionUseCase @Inject constructor(
    private val repository: SessionKeyRepository
) {

    suspend operator fun invoke(
        sessionId: String,
        senderEphemeralPublicKey: String
    ): ReceiverKeyAgreementResult {
        return repository.prepareReceiverSession(
            sessionId = sessionId,
            senderEphemeralPublicKey =
                senderEphemeralPublicKey
        )
    }
}