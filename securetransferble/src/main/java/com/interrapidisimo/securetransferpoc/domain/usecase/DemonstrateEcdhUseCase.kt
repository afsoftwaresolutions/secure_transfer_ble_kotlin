package com.interrapidisimo.securetransferpoc.domain.usecase

import com.interrapidisimo.securetransferpoc.domain.model.EcdhResult
import com.interrapidisimo.securetransferpoc.domain.repository.SessionKeyRepository
import javax.inject.Inject

class DemonstrateEcdhUseCase @Inject constructor(
    private val repository: SessionKeyRepository
) {
    suspend operator fun invoke(): EcdhResult {
        return repository.demonstrateKeyAgreement()
    }
}