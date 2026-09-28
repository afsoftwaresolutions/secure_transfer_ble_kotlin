package com.interrapidisimo.securetransferpoc.data.crypto

import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

object HkdfSha256 {

    private const val HASH_LENGTH = 32

    fun deriveKey(
        inputKeyMaterial: ByteArray,
        salt: ByteArray,
        info: ByteArray
    ): ByteArray {
        val effectiveSalt = if (salt.isEmpty()) {
            ByteArray(HASH_LENGTH)
        } else {
            salt
        }

        // HKDF-Extract
        val pseudoRandomKey = hmac(
            key = effectiveSalt,
            data = inputKeyMaterial
        )

        // HKDF-Expand: un bloque produce los 32 bytes requeridos.
        val outputKey = hmac(
            key = pseudoRandomKey,
            data = info + byteArrayOf(1)
        )

        pseudoRandomKey.fill(0)

        return outputKey
    }

    private fun hmac(
        key: ByteArray,
        data: ByteArray
    ): ByteArray {
        return Mac.getInstance("HmacSHA256").run {
            init(SecretKeySpec(key, "HmacSHA256"))
            doFinal(data)
        }
    }
}