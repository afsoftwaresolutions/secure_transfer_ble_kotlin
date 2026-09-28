package com.interrapidisimo.securetransferpoc.domain.usecase

import com.interrapidisimo.securetransferpoc.domain.model.BleCapabilities
import com.interrapidisimo.securetransferpoc.domain.repository.BleCapabilityRepository
import javax.inject.Inject

class CheckBleCapabilitiesUseCase @Inject constructor(
    private val repository: BleCapabilityRepository
) {
    suspend operator fun invoke(): BleCapabilities {
        return repository.checkCapabilities()
    }
}