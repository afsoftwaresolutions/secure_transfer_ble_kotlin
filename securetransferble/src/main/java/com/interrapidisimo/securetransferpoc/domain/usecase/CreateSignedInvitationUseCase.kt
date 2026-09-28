package com.interrapidisimo.securetransferpoc.domain.usecase

import com.interrapidisimo.securetransferpoc.domain.model.SignedInvitationResult
import com.interrapidisimo.securetransferpoc.domain.repository.SessionInvitationRepository
import javax.inject.Inject

class CreateSignedInvitationUseCase @Inject constructor(
    private val repository:
    SessionInvitationRepository
) {

    suspend operator fun invoke(): SignedInvitationResult {

        val invitation = repository.createSenderInvitation()

        val signatureValid = repository.verifyInvitation(invitation)

        return SignedInvitationResult(
            sessionId = invitation.sessionId,
            invitationJson =
                repository.encodeToJson(invitation),
            signatureValid = signatureValid
        )
    }
}