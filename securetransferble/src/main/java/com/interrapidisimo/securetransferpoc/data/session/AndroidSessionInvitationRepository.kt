package com.interrapidisimo.securetransferpoc.data.session

import com.interrapidisimo.securetransferpoc.domain.model.SessionInvitation
import com.interrapidisimo.securetransferpoc.domain.repository.DeviceIdentityRepository
import com.interrapidisimo.securetransferpoc.domain.repository.SessionInvitationRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.security.SecureRandom
import java.security.spec.ECGenParameterSpec
import java.util.Base64
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.security.KeyFactory
import java.security.spec.X509EncodedKeySpec
import javax.crypto.KeyAgreement
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class AndroidSessionInvitationRepository @Inject constructor(
    private val identityRepository: DeviceIdentityRepository
) : SessionInvitationRepository {

    companion object {
        private const val PROTOCOL_VERSION = 1
        private const val SOURCE_APP = "SECURE_TRANSFER_POC_KOTLIN"
        private const val SESSION_DURATION_MILLIS = 2 * 60 * 1000L
        private const val ALLOWED_CLOCK_DIFFERENCE_MILLIS = 30 * 1000L
    }

    /*
     * La clave privada temporal se conserva únicamente en memoria.
     * Nunca forma parte del QR.
     */
    private val activeSessions = ConcurrentHashMap<String, KeyPair>()

    private val activeSessionExpirations = ConcurrentHashMap<String, Long>()

    override suspend fun createSenderInvitation(): SessionInvitation = withContext(Dispatchers.Default) {

        val identity = identityRepository.getOrCreateIdentity()
        val ephemeralKeyPair = generateEphemeralKeyPair()

        val sessionId = UUID.randomUUID().toString()
        val now = System.currentTimeMillis()

        val encoder = Base64.getUrlEncoder()
            .withoutPadding()

        val nonceBytes = ByteArray(32).also {
            SecureRandom().nextBytes(it)
        }

        val unsignedInvitation = SessionInvitation(
            protocolVersion = PROTOCOL_VERSION,
            sessionId = sessionId,
            sourceApp = SOURCE_APP,
            sourceDeviceId = createDeviceId(
                identity.publicKey
            ),
            createdAtEpochMillis = now,
            expiresAtEpochMillis =
                now + SESSION_DURATION_MILLIS,
            nonce = encoder.encodeToString(nonceBytes),
            identityPublicKey = identity.publicKey,
            ephemeralPublicKey = encoder.encodeToString(
                ephemeralKeyPair.public.encoded
            ),
            signature = ""
        )

        val signatureBytes = identityRepository.sign(
            unsignedInvitation.signingBytes()
        )

        val signedInvitation = unsignedInvitation.copy(
            signature = encoder.encodeToString(
                signatureBytes
            )
        )

        activeSessions[sessionId] = ephemeralKeyPair

        activeSessionExpirations[sessionId] = signedInvitation.expiresAtEpochMillis

        signedInvitation
    }

    override suspend fun verifyInvitation(
        invitation: SessionInvitation
    ): Boolean = withContext(Dispatchers.Default) {

        val now = System.currentTimeMillis()

        val protocolIsValid = invitation.protocolVersion == PROTOCOL_VERSION

        val expirationIsValid = invitation.expiresAtEpochMillis > now

        val creationTimeIsValid = invitation.createdAtEpochMillis <= now + ALLOWED_CLOCK_DIFFERENCE_MILLIS

        val durationIsValid =
            invitation.expiresAtEpochMillis -
                    invitation.createdAtEpochMillis <=
                    SESSION_DURATION_MILLIS

        val sourceAppIsValid = invitation.sourceApp == SOURCE_APP

        if (
            !protocolIsValid ||
            !sourceAppIsValid ||
            !expirationIsValid ||
            !creationTimeIsValid ||
            !durationIsValid
        ) {
            return@withContext false
        }

        runCatching {
            val signatureBytes = Base64.getUrlDecoder().decode(invitation.signature)

            identityRepository.verify(
                data = invitation.signingBytes(),
                signature = signatureBytes,
                publicKey = invitation.identityPublicKey
            )
        }.getOrDefault(false)
    }

    override fun encodeToJson(
        invitation: SessionInvitation
    ): String {
        return JSONObject()
            .put(
                "protocolVersion",
                invitation.protocolVersion
            )
            .put("sessionId", invitation.sessionId)
            .put("sourceApp", invitation.sourceApp)
            .put(
                "sourceDeviceId",
                invitation.sourceDeviceId
            )
            .put(
                "createdAtEpochMillis",
                invitation.createdAtEpochMillis
            )
            .put(
                "expiresAtEpochMillis",
                invitation.expiresAtEpochMillis
            )
            .put("nonce", invitation.nonce)
            .put(
                "identityPublicKey",
                invitation.identityPublicKey
            )
            .put(
                "ephemeralPublicKey",
                invitation.ephemeralPublicKey
            )
            .put("signature", invitation.signature)
            .toString()
    }

    override fun decodeFromJson(
        invitationJson: String
    ): SessionInvitation {
        return JSONObject(invitationJson).run {
            SessionInvitation(
                protocolVersion =
                    getInt("protocolVersion"),
                sessionId =
                    getString("sessionId"),
                sourceApp =
                    getString("sourceApp"),
                sourceDeviceId =
                    getString("sourceDeviceId"),
                createdAtEpochMillis =
                    getLong("createdAtEpochMillis"),
                expiresAtEpochMillis =
                    getLong("expiresAtEpochMillis"),
                nonce =
                    getString("nonce"),
                identityPublicKey =
                    getString("identityPublicKey"),
                ephemeralPublicKey =
                    getString("ephemeralPublicKey"),
                signature =
                    getString("signature")
            )
        }
    }

    override fun isActiveSession(sessionId: String): Boolean {
        val expiration = activeSessionExpirations[sessionId]
            ?: return false

        val keyPairExists = activeSessions.containsKey(sessionId)

        if (!keyPairExists || System.currentTimeMillis() > expiration) {
            activeSessions.remove(sessionId)
            activeSessionExpirations.remove(sessionId)
            return false
        }

        return true
    }

    override fun clearSession(
        sessionId: String
    ) {
        if (sessionId.isBlank()) {
            return
        }

        activeSessions.remove(sessionId)
        activeSessionExpirations.remove(sessionId)
    }

    override fun calculateSenderSharedSecret(
        sessionId: String,
        receiverEphemeralPublicKey: String
    ): ByteArray {
        require(isActiveSession(sessionId)) {
            "La sesión no existe o ya expiró"
        }

        val senderKeyPair = requireNotNull(
            activeSessions[sessionId]
        ) {
            "No existe la clave ECDH temporal del emisor"
        }

        val receiverPublicKeyBytes =
            Base64.getUrlDecoder().decode(
                receiverEphemeralPublicKey
            )

        val receiverPublicKey = KeyFactory
            .getInstance("EC")
            .generatePublic(
                X509EncodedKeySpec(
                    receiverPublicKeyBytes
                )
            )

        return KeyAgreement.getInstance("ECDH").run {
            init(senderKeyPair.private)
            doPhase(receiverPublicKey, true)
            generateSecret()
        }
    }

    private fun generateEphemeralKeyPair(): KeyPair {
        return KeyPairGenerator.getInstance("EC").run {
            initialize(ECGenParameterSpec("secp256r1"))
            generateKeyPair()
        }
    }

    private fun createDeviceId(
        identityPublicKey: String
    ): String {
        val publicKeyBytes = Base64.getUrlDecoder()
            .decode(identityPublicKey)

        val fingerprint = MessageDigest
            .getInstance("SHA-256")
            .digest(publicKeyBytes)
            .copyOfRange(0, 16)

        return Base64.getUrlEncoder()
            .withoutPadding()
            .encodeToString(fingerprint)
    }
}