package com.interrapidisimo.securetransferpoc.data.crypto

import com.interrapidisimo.securetransferpoc.domain.model.EcdhResult
import com.interrapidisimo.securetransferpoc.domain.repository.SessionKeyRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.security.spec.ECGenParameterSpec
import java.util.Base64
import javax.crypto.KeyAgreement
import javax.inject.Inject
import javax.inject.Singleton
import java.util.UUID
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec
import com.interrapidisimo.securetransferpoc.domain.model.ReceiverKeyAgreementResult
import com.interrapidisimo.securetransferpoc.domain.model.SenderKeyAgreementResult
import com.interrapidisimo.securetransferpoc.domain.repository.SessionInvitationRepository
import java.security.KeyFactory
import java.security.PublicKey
import java.security.PrivateKey
import java.security.spec.X509EncodedKeySpec
import java.util.concurrent.ConcurrentHashMap
import com.interrapidisimo.securetransferpoc.domain.model.EncryptedTransferEnvelope
import com.interrapidisimo.securetransferpoc.domain.repository.SessionMessagePurpose

@Singleton
class EcdhSessionKeyRepository @Inject constructor(
    private val sessionInvitationRepository:
    SessionInvitationRepository
) : SessionKeyRepository {

    private val sessionKeys = ConcurrentHashMap<String, ByteArray>()

    override suspend fun prepareReceiverSession(
        sessionId: String,
        senderEphemeralPublicKey: String
    ): ReceiverKeyAgreementResult =
        withContext(Dispatchers.Default) {

            require(sessionId.isNotBlank()) {
                "El sessionId está vacío"
            }

            val receiverKeyPair =
                generateTemporaryKeyPair()

            val senderPublicKey = decodePublicKey(
                senderEphemeralPublicKey
            )

            val sharedSecret = calculateSharedSecret(
                ownPrivateKey = receiverKeyPair.private,
                peerPublicKey = senderPublicKey
            )

            val sessionKey = deriveSessionKey(
                sharedSecret = sharedSecret,
                sessionId = sessionId
            )

            saveSessionKey(
                sessionId = sessionId,
                sessionKey = sessionKey
            )

            val encoder = Base64.getUrlEncoder()
                .withoutPadding()

            val result = ReceiverKeyAgreementResult(
                sessionId = sessionId,
                receiverEphemeralPublicKey =
                    encoder.encodeToString(
                        receiverKeyPair.public.encoded
                    ),
                sessionKeyFingerprint =
                    encoder.encodeToString(
                        sha256(sessionKey)
                    )
            )

            sharedSecret.fill(0)
            sessionKey.fill(0)

            result
        }

    override suspend fun completeSenderSession(
        sessionId: String,
        receiverEphemeralPublicKey: String
    ): SenderKeyAgreementResult =
        withContext(Dispatchers.Default) {

            require(sessionId.isNotBlank()) {
                "El sessionId está vacío"
            }

            val sharedSecret =
                sessionInvitationRepository
                    .calculateSenderSharedSecret(
                        sessionId =
                            sessionId,
                        receiverEphemeralPublicKey =
                            receiverEphemeralPublicKey
                    )

            val sessionKey = deriveSessionKey(
                sharedSecret = sharedSecret,
                sessionId = sessionId
            )

            saveSessionKey(
                sessionId = sessionId,
                sessionKey = sessionKey
            )

            val fingerprint = Base64.getUrlEncoder()
                .withoutPadding()
                .encodeToString(
                    sha256(sessionKey)
                )

            sharedSecret.fill(0)
            sessionKey.fill(0)

            SenderKeyAgreementResult(
                sessionId = sessionId,
                sessionKeyFingerprint = fingerprint
            )
        }

    override suspend fun encryptSessionMessage(
        sessionId: String,
        messageId: String,
        plainText: String,
        purpose: SessionMessagePurpose
    ): EncryptedTransferEnvelope =
        withContext(Dispatchers.Default) {

            require(sessionId.isNotBlank()) {
                "El sessionId está vacío"
            }

            require(messageId.isNotBlank()) {
                "El messageId está vacío"
            }

            require(plainText.isNotBlank()) {
                "El mensaje que se desea cifrar está vacío"
            }

            val sessionKey = requireNotNull(
                sessionKeys[sessionId]?.copyOf()
            ) {
                "No existe una clave AES para la sesión $sessionId"
            }

            try {
                val iv = ByteArray(AES_GCM_IV_SIZE_BYTES).also {
                    SecureRandom().nextBytes(it)
                }

                val associatedData = createAssociatedData(
                    sessionId = sessionId,
                    messageId = messageId,
                    purpose = purpose
                )

                val encryptedBytes = encrypt(
                    plainText = plainText.encodeToByteArray(),
                    sessionKey = sessionKey,
                    iv = iv,
                    associatedData = associatedData
                )

                val encoder = Base64.getUrlEncoder()
                    .withoutPadding()

                EncryptedTransferEnvelope(
                    protocolVersion = PROTOCOL_VERSION,
                    sessionId = sessionId,
                    messageId = messageId,
                    iv = encoder.encodeToString(iv),
                    cipherText = encoder.encodeToString(
                        encryptedBytes
                    )
                )
            } finally {
                sessionKey.fill(0)
            }
        }

    override suspend fun decryptSessionMessage(
        envelope: EncryptedTransferEnvelope,
        purpose: SessionMessagePurpose
    ): String =
        withContext(Dispatchers.Default) {

            require(
                envelope.protocolVersion == PROTOCOL_VERSION
            ) {
                "Versión de protocolo no soportada: ${envelope.protocolVersion}"
            }

            require(envelope.sessionId.isNotBlank()) {
                "El sessionId está vacío"
            }

            require(envelope.messageId.isNotBlank()) {
                "El messageId está vacío"
            }

            val sessionKey = requireNotNull(
                sessionKeys[envelope.sessionId]?.copyOf()
            ) {
                "No existe una clave AES para la sesión ${envelope.sessionId}"
            }

            try {
                val decoder = Base64.getUrlDecoder()

                val iv = decoder.decode(envelope.iv)

                require(iv.size == AES_GCM_IV_SIZE_BYTES) {
                    "El IV de AES-GCM no tiene 12 bytes"
                }

                val encryptedBytes = decoder.decode(
                    envelope.cipherText
                )

                require(encryptedBytes.isNotEmpty()) {
                    "El contenido cifrado está vacío"
                }

                val associatedData = createAssociatedData(
                    sessionId = envelope.sessionId,
                    messageId = envelope.messageId,
                    purpose = purpose
                )

                decrypt(
                    encryptedMessage = encryptedBytes,
                    sessionKey = sessionKey,
                    iv = iv,
                    associatedData = associatedData
                ).decodeToString()
            } finally {
                sessionKey.fill(0)
            }
        }

    override fun hasSessionKey(
        sessionId: String
    ): Boolean {
        return sessionKeys.containsKey(sessionId)
    }

    override fun clearSession(
        sessionId: String
    ) {
        if (sessionId.isBlank()) {
            return
        }

        val removedKey = sessionKeys.remove(sessionId)

        removedKey?.fill(0)
    }

    override suspend fun demonstrateKeyAgreement(): EcdhResult =
        withContext(Dispatchers.Default) {

            val keyPairA = generateTemporaryKeyPair()
            val keyPairB = generateTemporaryKeyPair()

            val sessionId = UUID.randomUUID().toString()

            val sharedSecretA = calculateSharedSecret(
                ownKeyPair = keyPairA,
                peerKeyPair = keyPairB
            )

            val sharedSecretB = calculateSharedSecret(
                ownKeyPair = keyPairB,
                peerKeyPair = keyPairA
            )

            val salt = sha256(
                "IR_TRANSFER_V1|$sessionId"
                    .encodeToByteArray()
            )

            val info = "IR_TRANSFER_V1|AES_256_GCM|$sessionId"
                .encodeToByteArray()

            val sessionKeyA = HkdfSha256.deriveKey(
                inputKeyMaterial = sharedSecretA,
                salt = salt,
                info = info
            )

            val sessionKeyB = HkdfSha256.deriveKey(
                inputKeyMaterial = sharedSecretB,
                salt = salt,
                info = info
            )

            val originalMessage = """
                {
                    "guia": "12345678910",
                    "estado": "ENTREGADA",
                    "usuario": "84521"
                }
            """.trimIndent()

            val messageId = "MESSAGE-001"

            val associatedData = """
                IR_TRANSFER_V1|$sessionId|$messageId
            """.trimIndent().encodeToByteArray()

            val iv = ByteArray(12).also {
                SecureRandom().nextBytes(it)
            }

            val encryptedMessage = encrypt(
                plainText = originalMessage.encodeToByteArray(),
                sessionKey = sessionKeyA,
                iv = iv,
                associatedData = associatedData
            )

            val decryptedBytes = decrypt(
                encryptedMessage = encryptedMessage,
                sessionKey = sessionKeyB,
                iv = iv,
                associatedData = associatedData
            )

            val modifiedEncryptedMessage =
                encryptedMessage.copyOf().also {
                    it[0] = (it[0].toInt() xor 1).toByte()
                }

            val modifiedMessageWasRejected = runCatching {
                decrypt(
                    encryptedMessage = modifiedEncryptedMessage,
                    sessionKey = sessionKeyB,
                    iv = iv,
                    associatedData = associatedData
                )
            }.isFailure

            val decryptedMessage = decryptedBytes.decodeToString()

            val decryptionSuccessful =
                originalMessage == decryptedMessage

            val sameSessionKey = MessageDigest.isEqual(
                sessionKeyA,
                sessionKeyB
            )

            val sameSecret = MessageDigest.isEqual(
                sharedSecretA,
                sharedSecretB
            )

            val encoder = Base64.getUrlEncoder().withoutPadding()

            val fingerprintA = encoder.encodeToString(
                sha256(sharedSecretA)
            )

            val fingerprintB = encoder.encodeToString(
                sha256(sharedSecretB)
            )

            val result = EcdhResult(
                sessionId = sessionId,
                publicKeyA = encoder.encodeToString(
                    keyPairA.public.encoded
                ),
                publicKeyB = encoder.encodeToString(
                    keyPairB.public.encoded
                ),
                secretFingerprintA = encoder.encodeToString(
                    sha256(sharedSecretA)
                ),
                secretFingerprintB = encoder.encodeToString(
                    sha256(sharedSecretB)
                ),
                sameSecret = sameSecret,
                sessionKeyFingerprintA = encoder.encodeToString(
                    sha256(sessionKeyA)
                ),
                sessionKeyFingerprintB = encoder.encodeToString(
                    sha256(sessionKeyB)
                ),
                sameSessionKey = sameSessionKey,
                iv = encoder.encodeToString(iv),
                encryptedMessage = encoder.encodeToString(
                    encryptedMessage
                ),
                decryptedMessage = decryptedMessage,
                decryptionSuccessful = decryptionSuccessful,
                modifiedMessageWasRejected =
                    modifiedMessageWasRejected
            )

            sharedSecretA.fill(0)
            sharedSecretB.fill(0)
            sessionKeyA.fill(0)
            sessionKeyB.fill(0)

            result
        }

    private fun generateTemporaryKeyPair(): KeyPair {
        return KeyPairGenerator.getInstance("EC").run {
            initialize(ECGenParameterSpec("secp256r1"))
            generateKeyPair()
        }
    }

    private fun calculateSharedSecret(
        ownKeyPair: KeyPair,
        peerKeyPair: KeyPair
    ): ByteArray {
        return KeyAgreement.getInstance("ECDH").run {
            init(ownKeyPair.private)
            doPhase(peerKeyPair.public, true)
            generateSecret()
        }
    }

    private fun sha256(value: ByteArray): ByteArray {
        return MessageDigest.getInstance("SHA-256")
            .digest(value)
    }

    private fun encrypt(
        plainText: ByteArray,
        sessionKey: ByteArray,
        iv: ByteArray,
        associatedData: ByteArray
    ): ByteArray {
        val secretKey = SecretKeySpec(
            sessionKey,
            "AES"
        )

        return Cipher.getInstance("AES/GCM/NoPadding").run {
            init(
                Cipher.ENCRYPT_MODE,
                secretKey,
                GCMParameterSpec(128, iv)
            )

            updateAAD(associatedData)
            doFinal(plainText)
        }
    }

    private fun decrypt(
        encryptedMessage: ByteArray,
        sessionKey: ByteArray,
        iv: ByteArray,
        associatedData: ByteArray
    ): ByteArray {
        val secretKey = SecretKeySpec(
            sessionKey,
            "AES"
        )

        return Cipher.getInstance("AES/GCM/NoPadding").run {
            init(
                Cipher.DECRYPT_MODE,
                secretKey,
                GCMParameterSpec(128, iv)
            )

            updateAAD(associatedData)
            doFinal(encryptedMessage)
        }
    }

    private fun decodePublicKey(
        encodedPublicKey: String
    ): PublicKey {
        val publicKeyBytes = Base64
            .getUrlDecoder()
            .decode(encodedPublicKey)

        return KeyFactory
            .getInstance("EC")
            .generatePublic(
                X509EncodedKeySpec(publicKeyBytes)
            )
    }

    private fun calculateSharedSecret(
        ownPrivateKey: PrivateKey,
        peerPublicKey: PublicKey
    ): ByteArray {
        return KeyAgreement.getInstance("ECDH").run {
            init(ownPrivateKey)
            doPhase(peerPublicKey, true)
            generateSecret()
        }
    }

    private fun deriveSessionKey(
        sharedSecret: ByteArray,
        sessionId: String
    ): ByteArray {
        val salt = sha256(
            "IR_TRANSFER_V1|$sessionId"
                .encodeToByteArray()
        )

        val info =
            "IR_TRANSFER_V1|AES_256_GCM|$sessionId"
                .encodeToByteArray()

        return HkdfSha256.deriveKey(
            inputKeyMaterial = sharedSecret,
            salt = salt,
            info = info
        )
    }

    private fun saveSessionKey(
        sessionId: String,
        sessionKey: ByteArray
    ) {
        val previousKey = sessionKeys.put(
            sessionId,
            sessionKey.copyOf()
        )

        previousKey?.fill(0)
    }

    private fun createAssociatedData(
        sessionId: String,
        messageId: String,
        purpose: SessionMessagePurpose
    ): ByteArray {
        return "IR_TRANSFER_V1|$sessionId|$messageId${purpose.suffix}"
            .encodeToByteArray()
    }

    companion object {
        private const val PROTOCOL_VERSION = 1
        private const val AES_GCM_IV_SIZE_BYTES = 12
    }

}