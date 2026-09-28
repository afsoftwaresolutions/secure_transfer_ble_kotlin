package com.interrapidisimo.securetransferpoc.domain.repository

import com.interrapidisimo.securetransferpoc.domain.model.BlePeripheralState
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow

interface BlePeripheralRepository {

    val state: StateFlow<BlePeripheralState>

    fun startAdvertising(sessionId: String)

    fun sendEncryptedData(plainText: String)

    fun stopAdvertising()

    val receivedReplies: SharedFlow<String>
}