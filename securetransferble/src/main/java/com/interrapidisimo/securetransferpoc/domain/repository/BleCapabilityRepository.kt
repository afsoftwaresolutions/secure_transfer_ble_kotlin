package com.interrapidisimo.securetransferpoc.domain.repository

import com.interrapidisimo.securetransferpoc.domain.model.BleCapabilities

interface BleCapabilityRepository {

    suspend fun checkCapabilities(): BleCapabilities

}