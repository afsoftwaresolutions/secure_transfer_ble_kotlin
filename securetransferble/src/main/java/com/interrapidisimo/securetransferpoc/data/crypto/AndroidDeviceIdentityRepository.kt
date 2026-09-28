package com.interrapidisimo.securetransferpoc.data.crypto

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import com.interrapidisimo.securetransferpoc.domain.model.DeviceIdentity
import com.interrapidisimo.securetransferpoc.domain.repository.DeviceIdentityRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.spec.ECGenParameterSpec
import java.util.Base64
import javax.inject.Inject
import javax.inject.Singleton
import java.security.KeyFactory
import java.security.PrivateKey
import java.security.Signature
import java.security.spec.X509EncodedKeySpec

@Singleton
class AndroidDeviceIdentityRepository @Inject constructor() : DeviceIdentityRepository {

    companion object {
        private const val ANDROID_KEYSTORE = "AndroidKeyStore"
        private const val IDENTITY_ALIAS = "ir_transfer_identity_v1"
    }

    override suspend fun getOrCreateIdentity(): DeviceIdentity =
        withContext(Dispatchers.IO) {

            val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply {
                load(null)
            }

            val alreadyExisted = keyStore.containsAlias(IDENTITY_ALIAS)

            if (!alreadyExisted) {
                generateIdentityKeyPair()
            }

            val publicKey = requireNotNull(
                keyStore.getCertificate(IDENTITY_ALIAS)?.publicKey
            ) {
                "No fue posible recuperar la clave pública"
            }

            val encodedPublicKey = Base64.getUrlEncoder()
                .withoutPadding()
                .encodeToString(publicKey.encoded)

            DeviceIdentity(
                publicKey = encodedPublicKey,
                wasCreated = !alreadyExisted
            )
        }

    override suspend fun sign(data: ByteArray): ByteArray =
        withContext(Dispatchers.IO) {

            val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply {
                load(null)
            }

            if (!keyStore.containsAlias(IDENTITY_ALIAS)) {
                generateIdentityKeyPair()
            }

            val privateKey = requireNotNull(
                keyStore.getKey(IDENTITY_ALIAS, null) as? PrivateKey
            ) {
                "No fue posible acceder a la clave privada"
            }

            Signature.getInstance("SHA256withECDSA").run {
                initSign(privateKey)
                update(data)
                sign()
            }
        }

    override suspend fun verify(
        data: ByteArray,
        signature: ByteArray,
        publicKey: String
    ): Boolean  = withContext(Dispatchers.Default) {

        val publicKeyBytes = Base64.getUrlDecoder().decode(publicKey)

        val decodedPublicKey = KeyFactory.getInstance("EC")
            .generatePublic(
                X509EncodedKeySpec(publicKeyBytes)
            )

        Signature.getInstance("SHA256withECDSA").run {
            initVerify(decodedPublicKey)
            update(data)
            verify(signature)
        }
    }

    private fun generateIdentityKeyPair() {
        val generator = KeyPairGenerator.getInstance(
            KeyProperties.KEY_ALGORITHM_EC,
            ANDROID_KEYSTORE
        )

        val specification = KeyGenParameterSpec.Builder(
            IDENTITY_ALIAS,
            KeyProperties.PURPOSE_SIGN or KeyProperties.PURPOSE_VERIFY
        )
            .setAlgorithmParameterSpec(
                ECGenParameterSpec("secp256r1")
            )
            .setDigests(KeyProperties.DIGEST_SHA256)
            .setUserAuthenticationRequired(false)
            .build()

        generator.initialize(specification)
        generator.generateKeyPair()
    }

}