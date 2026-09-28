package com.interrapidisimo.securetransferpoc.transport.ble

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.UUID
import kotlin.math.max

class BleFrameCodec {

    fun fragment(
        messageType: BleMessageType,
        payload: ByteArray,
        negotiatedMtu: Int,
        messageId: UUID = UUID.randomUUID()
    ): List<ByteArray> {
        val maximumGattValueSize =
            negotiatedMtu - ATT_HEADER_SIZE

        val maximumPayloadSize =
            maximumGattValueSize - FRAME_HEADER_SIZE

        require(maximumPayloadSize > 0) {
            "El MTU $negotiatedMtu es demasiado pequeño"
        }

        val totalChunks = max(
            1,
            (
                    payload.size +
                            maximumPayloadSize - 1
                    ) / maximumPayloadSize
        )

        require(totalChunks <= MAX_UNSIGNED_SHORT) {
            "El mensaje requiere demasiados fragmentos"
        }

        return (0 until totalChunks).map { index ->
            val start = index * maximumPayloadSize

            val end = minOf(
                start + maximumPayloadSize,
                payload.size
            )

            val chunkPayload = if (payload.isEmpty()) {
                ByteArray(0)
            } else {
                payload.copyOfRange(start, end)
            }

            encode(
                BleFrame(
                    protocolVersion =
                        CURRENT_PROTOCOL_VERSION,
                    messageType = messageType,
                    messageId = messageId,
                    chunkIndex = index,
                    totalChunks = totalChunks,
                    payload = chunkPayload
                )
            )
        }
    }

    fun encode(frame: BleFrame): ByteArray {
        require(
            frame.payload.size <= MAX_UNSIGNED_SHORT
        ) {
            "El payload del fragmento es demasiado grande"
        }

        return ByteBuffer
            .allocate(
                FRAME_HEADER_SIZE +
                        frame.payload.size
            )
            .order(ByteOrder.BIG_ENDIAN)
            .apply {
                put(MAGIC_FIRST)
                put(MAGIC_SECOND)
                put(frame.protocolVersion)
                put(frame.messageType.code)

                putLong(
                    frame.messageId.mostSignificantBits
                )
                putLong(
                    frame.messageId.leastSignificantBits
                )

                putShort(
                    frame.chunkIndex.toShort()
                )

                putShort(
                    frame.totalChunks.toShort()
                )

                putShort(
                    frame.payload.size.toShort()
                )

                put(frame.payload)
            }
            .array()
    }

    fun decode(packet: ByteArray): BleFrame {
        require(packet.size >= FRAME_HEADER_SIZE) {
            "El fragmento BLE está incompleto"
        }

        val buffer = ByteBuffer
            .wrap(packet)
            .order(ByteOrder.BIG_ENDIAN)

        val firstMagic = buffer.get()
        val secondMagic = buffer.get()

        require(
            firstMagic == MAGIC_FIRST &&
                    secondMagic == MAGIC_SECOND
        ) {
            "El fragmento no pertenece a IR_TRANSFER"
        }

        val protocolVersion = buffer.get()

        require(
            protocolVersion ==
                    CURRENT_PROTOCOL_VERSION
        ) {
            "Versión de protocolo no soportada"
        }

        val messageType = BleMessageType.fromCode(
            buffer.get()
        )

        val messageId = UUID(
            buffer.long,
            buffer.long
        )

        val chunkIndex =
            buffer.short.toInt() and MAX_UNSIGNED_SHORT

        val totalChunks =
            buffer.short.toInt() and MAX_UNSIGNED_SHORT

        val payloadLength =
            buffer.short.toInt() and MAX_UNSIGNED_SHORT

        require(totalChunks > 0) {
            "Cantidad de fragmentos inválida"
        }

        require(chunkIndex < totalChunks) {
            "Índice de fragmento inválido"
        }

        require(payloadLength == buffer.remaining()) {
            "El tamaño del payload no coincide"
        }

        val payload = ByteArray(payloadLength)
        buffer.get(payload)

        return BleFrame(
            protocolVersion = protocolVersion,
            messageType = messageType,
            messageId = messageId,
            chunkIndex = chunkIndex,
            totalChunks = totalChunks,
            payload = payload
        )
    }

    companion object {
        const val FRAME_HEADER_SIZE = 26

        private const val ATT_HEADER_SIZE = 3
        private const val MAX_UNSIGNED_SHORT = 65_535

        private const val MAGIC_FIRST: Byte = 0x49
        private const val MAGIC_SECOND: Byte = 0x52

        private const val CURRENT_PROTOCOL_VERSION: Byte = 1
    }
}