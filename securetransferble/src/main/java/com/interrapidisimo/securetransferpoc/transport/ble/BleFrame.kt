package com.interrapidisimo.securetransferpoc.transport.ble

import java.util.UUID

enum class BleMessageType(
    val code: Byte
) {
    RECEIVER_HANDSHAKE(1),
    ENCRYPTED_DATA(2),
    ACK(3),
    ERROR(4);

    companion object {
        fun fromCode(code: Byte): BleMessageType {
            return entries.firstOrNull {
                it.code == code
            } ?: error(
                "Tipo de mensaje BLE desconocido: $code"
            )
        }
    }
}

data class BleFrame(
    val protocolVersion: Byte,
    val messageType: BleMessageType,
    val messageId: UUID,
    val chunkIndex: Int,
    val totalChunks: Int,
    val payload: ByteArray
) {
    init {
        require(chunkIndex >= 0) {
            "chunkIndex no puede ser negativo"
        }

        require(totalChunks > 0) {
            "totalChunks debe ser mayor que cero"
        }

        require(chunkIndex < totalChunks) {
            "chunkIndex debe ser menor que totalChunks"
        }
    }
}

data class AssembledBleMessage(
    val messageType: BleMessageType,
    val messageId: UUID,
    val payload: ByteArray
)