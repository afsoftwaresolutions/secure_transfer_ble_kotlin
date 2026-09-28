package com.interrapidisimo.securetransferpoc.domain.usecase

import com.interrapidisimo.securetransferpoc.domain.model.ReceiverHandshakeVerificationResult
import com.interrapidisimo.securetransferpoc.domain.repository.DeviceIdentityRepository
import com.interrapidisimo.securetransferpoc.transport.ble.ReceiverHandshakeCodec
import java.util.Base64
import javax.inject.Inject

class VerifyReceiverHandshakeUseCase @Inject constructor(
    private val identityRepository: DeviceIdentityRepository,
    private val codec: ReceiverHandshakeCodec
) {

    suspend operator fun invoke(
        handshakeJson: String,
        expectedSessionId: String
    ): ReceiverHandshakeVerificationResult {
        val handshake = runCatching {
            codec.decode(handshakeJson)
        }.getOrElse {
            return ReceiverHandshakeVerificationResult(
                isValid = false,
                message =
                    "El handshake no contiene un JSON válido"
            )
        }

        if (
            handshake.protocolVersion !=
            PROTOCOL_VERSION
        ) {
            return ReceiverHandshakeVerificationResult(
                isValid = false,
                message =
                    "Versión de handshake no soportada"
            )
        }

        if (
            handshake.sessionId !=
            expectedSessionId
        ) {
            return ReceiverHandshakeVerificationResult(
                isValid = false,
                message =
                    "El handshake pertenece a otra sesión"
            )
        }

        val now = System.currentTimeMillis()

        val timestampIsValid =
            handshake.createdAtEpochMillis >=
                    now - MAX_HANDSHAKE_AGE_MILLIS &&
                    handshake.createdAtEpochMillis <=
                    now + ALLOWED_CLOCK_DIFFERENCE_MILLIS

        if (!timestampIsValid) {
            return ReceiverHandshakeVerificationResult(
                isValid = false,
                message =
                    "El handshake expiró o tiene una fecha futura"
            )
        }

        if (
            handshake.nonce.isBlank() ||
            handshake.identityPublicKey.isBlank() ||
            handshake.ephemeralPublicKey.isBlank() ||
            handshake.signature.isBlank()
        ) {
            return ReceiverHandshakeVerificationResult(
                isValid = false,
                message =
                    "El handshake contiene campos vacíos"
            )
        }

        val nonceIsValid = runCatching {
            Base64.getUrlDecoder()
                .decode(handshake.nonce)
                .size == NONCE_SIZE_BYTES
        }.getOrDefault(false)

        if (!nonceIsValid) {
            return ReceiverHandshakeVerificationResult(
                isValid = false,
                message =
                    "El nonce del receptor es inválido"
            )
        }

        val signatureIsValid = runCatching {
            val signatureBytes =
                Base64.getUrlDecoder()
                    .decode(handshake.signature)

            identityRepository.verify(
                data = handshake.signingBytes(),
                signature = signatureBytes,
                publicKey =
                    handshake.identityPublicKey
            )
        }.getOrDefault(false)

        if (!signatureIsValid) {
            return ReceiverHandshakeVerificationResult(
                isValid = false,
                message =
                    "La firma del receptor es inválida"
            )
        }

        return ReceiverHandshakeVerificationResult(
            isValid = true,
            message =
                "Identidad y clave ECDH del receptor verificadas",
            handshake = handshake
        )
    }

    private companion object {
        const val PROTOCOL_VERSION = 1
        const val NONCE_SIZE_BYTES = 32

        const val MAX_HANDSHAKE_AGE_MILLIS =
            2 * 60 * 1000L

        const val ALLOWED_CLOCK_DIFFERENCE_MILLIS =
            30 * 1000L
    }
}