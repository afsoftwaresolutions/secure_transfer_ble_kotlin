package com.interrapidisimo.securetransferpoc.domain.usecase

import com.interrapidisimo.securetransferpoc.domain.model.ScannedInvitationResult
import com.interrapidisimo.securetransferpoc.domain.repository.SessionInvitationRepository
import javax.inject.Inject

class ValidateScannedInvitationUseCase @Inject constructor(
    private val repository: SessionInvitationRepository
) {

    suspend operator fun invoke(
        rawContent: String
    ): ScannedInvitationResult {

        val invitation = runCatching {
            repository.decodeFromJson(rawContent)
        }.getOrElse {
            return ScannedInvitationResult(
                isValid = false,
                message = "El QR no contiene una invitación válida"
            )
        }

        val isValid = repository.verifyInvitation(invitation)

        return if (isValid) {
            ScannedInvitationResult(
                isValid = true,
                message =
                    "Invitación recibida y firma verificada",
                sessionId = invitation.sessionId,
                sourceApp = invitation.sourceApp,
                sourceDeviceId =
                    invitation.sourceDeviceId,
                senderEphemeralPublicKey =
                    invitation.ephemeralPublicKey
            )
        } else {
            ScannedInvitationResult(
                isValid = false,
                message =
                    "La invitación expiró o su firma es inválida"
            )
        }
    }
}