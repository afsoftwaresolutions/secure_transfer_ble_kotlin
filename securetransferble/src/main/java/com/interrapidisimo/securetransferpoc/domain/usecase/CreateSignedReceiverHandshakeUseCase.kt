package com.interrapidisimo.securetransferpoc.domain.usecase

import com.interrapidisimo.securetransferpoc.domain.model.ReceiverHandshake
import com.interrapidisimo.securetransferpoc.domain.model.SignedReceiverHandshakeResult
import com.interrapidisimo.securetransferpoc.domain.repository.DeviceIdentityRepository
import com.interrapidisimo.securetransferpoc.transport.ble.ReceiverHandshakeCodec
import java.security.SecureRandom
import java.util.Base64
import javax.inject.Inject

class CreateSignedReceiverHandshakeUseCase @Inject constructor(
    private val identityRepository:
    DeviceIdentityRepository,
    private val codec: ReceiverHandshakeCodec
) {

    suspend operator fun invoke(
        sessionId: String,
        receiverEphemeralPublicKey: String
    ): SignedReceiverHandshakeResult {
        require(sessionId.isNotBlank()) {
            "El sessionId está vacío"
        }

        require(
            receiverEphemeralPublicKey.isNotBlank()
        ) {
            "La clave ECDH del receptor está vacía"
        }

        val identity =
            identityRepository.getOrCreateIdentity()

        val nonceBytes = ByteArray(32).also {
            SecureRandom().nextBytes(it)
        }

        val encoder = Base64
            .getUrlEncoder()
            .withoutPadding()

        val unsignedHandshake = ReceiverHandshake(
            protocolVersion = PROTOCOL_VERSION,
            sessionId = sessionId,
            receiverApp = RECEIVER_APP,
            createdAtEpochMillis =
                System.currentTimeMillis(),
            nonce =
                encoder.encodeToString(nonceBytes),
            identityPublicKey =
                identity.publicKey,
            ephemeralPublicKey =
                receiverEphemeralPublicKey,
            signature = ""
        )

        val signature =
            identityRepository.sign(
                unsignedHandshake.signingBytes()
            )

        val signedHandshake = unsignedHandshake.copy(
                signature = encoder.encodeToString(signature)
            )

        return SignedReceiverHandshakeResult(
            handshake = signedHandshake,
            handshakeJson = codec.encode(signedHandshake)
        )
    }

    private companion object {
        const val PROTOCOL_VERSION = 1

        const val RECEIVER_APP =
            "SECURE_TRANSFER_POC_KOTLIN"
    }
}