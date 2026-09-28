package com.interrapidisimo.securetransferpoc.domain.usecase

import com.interrapidisimo.securetransferpoc.domain.model.DeviceIdentity
import com.interrapidisimo.securetransferpoc.domain.repository.DeviceIdentityRepository
import javax.inject.Inject

class GetOrCreateDeviceIdentityUseCase @Inject constructor(
    private val repository: DeviceIdentityRepository
) {
    suspend operator fun invoke(): DeviceIdentity {
        return repository.getOrCreateIdentity()
    }
}