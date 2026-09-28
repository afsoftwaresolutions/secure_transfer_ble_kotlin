package com.interrapidisimo.securetransferpoc.domain.repository

import com.interrapidisimo.securetransferpoc.domain.model.DeviceIdentity

interface DeviceIdentityRepository {

    suspend fun getOrCreateIdentity(): DeviceIdentity

    suspend fun sign(data: ByteArray): ByteArray

    suspend fun verify(
        data: ByteArray,
        signature: ByteArray,
        publicKey: String
    ): Boolean

}