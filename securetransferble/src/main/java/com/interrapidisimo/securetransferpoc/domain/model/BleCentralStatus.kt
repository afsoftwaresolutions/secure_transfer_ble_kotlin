package com.interrapidisimo.securetransferpoc.domain.model

enum class BleCentralStatus {
    IDLE,
    SCANNING,
    CONNECTING,
    DISCOVERING_SERVICES,
    SERVICE_READY,
    DISCONNECTED,
    ERROR,
    VERIFYING_SESSION,
    SESSION_VERIFIED,
    EXCHANGING_ECDH,
    SESSION_KEY_READY,
    DATA_RECEIVING,
    DATA_RECEIVED,
    ACK_SENDING,
    ACK_SENT,
    SESSION_REJECTED,
    NEGOTIATING_MTU,
    ENABLING_NOTIFICATIONS,
}

data class BleCentralState(
    val status: BleCentralStatus = BleCentralStatus.IDLE,
    val message: String = "Cliente BLE detenido",
    val deviceAddress: String? = null,
    val receivedMessageId: String? = null,
    val receivedData: String? = null
)
