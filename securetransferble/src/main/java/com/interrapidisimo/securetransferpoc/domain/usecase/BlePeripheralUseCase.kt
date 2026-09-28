package com.interrapidisimo.securetransferpoc.domain.usecase

import com.interrapidisimo.securetransferpoc.domain.model.BlePeripheralState
import com.interrapidisimo.securetransferpoc.domain.repository.BlePeripheralRepository
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import javax.inject.Inject

class BlePeripheralUseCase @Inject constructor(
    private val repository: BlePeripheralRepository
) {
    val state: StateFlow<BlePeripheralState>
        get() = repository.state

    fun start(sessionId: String) {
        repository.startAdvertising(sessionId)
    }

    fun sendEncryptedData(
        plainText: String
    ) {
        repository.sendEncryptedData(plainText)
    }

    fun stop() {
        repository.stopAdvertising()
    }

    val receivedReplies: SharedFlow<String>
        get() = repository.receivedReplies
}