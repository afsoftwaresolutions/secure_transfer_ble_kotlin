package com.interrapidisimo.securetransferpoc.domain.usecase

import com.interrapidisimo.securetransferpoc.domain.model.ChallengeProofResult
import com.interrapidisimo.securetransferpoc.domain.repository.DeviceIdentityRepository
import java.security.SecureRandom
import java.util.Base64
import javax.inject.Inject

class CreateAndVerifyChallengeUseCase @Inject constructor(
    private val repository: DeviceIdentityRepository
) {

    suspend operator fun invoke(): ChallengeProofResult {
        val identity = repository.getOrCreateIdentity()

        val challenge = ByteArray(32).also {
            SecureRandom().nextBytes(it)
        }

        val signature = repository.sign(challenge)

        val validSignature = repository.verify(
            data = challenge,
            signature = signature,
            publicKey = identity.publicKey
        )

        val modifiedChallenge = challenge.copyOf().also {
            it[0] = (it[0].toInt() xor 1).toByte()
        }

        val modifiedChallengeAccepted = repository.verify(
            data = modifiedChallenge,
            signature = signature,
            publicKey = identity.publicKey
        )

        val encoder = Base64.getUrlEncoder().withoutPadding()

        return ChallengeProofResult(
            challenge = encoder.encodeToString(challenge),
            signature = encoder.encodeToString(signature),
            isValid = validSignature,
            modifiedChallengeWasRejected = !modifiedChallengeAccepted
        )
    }

}