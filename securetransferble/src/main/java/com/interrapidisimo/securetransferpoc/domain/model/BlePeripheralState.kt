package com.interrapidisimo.securetransferpoc.domain.model

enum class BlePeripheralStatus {
    IDLE,
    STARTING,
    ADVERTISING,
    CONNECTED,
    ERROR,
    SESSION_VERIFIED,
    EXCHANGING_ECDH,
    SESSION_KEY_READY,
    DATA_SENDING,
    DATA_SENT,
    WAITING_ACK,
    RETRYING,
    DATA_CONFIRMED,
    SESSION_REJECTED,
}

data class BlePeripheralState(
    val status: BlePeripheralStatus = BlePeripheralStatus.IDLE,
    val message: String = "Peripheral detenido",
    val sessionId: String? = null,
    val connectedDevice: String? = null
)
