package com.interrapidisimo.securetransferpoc.transport.ble

import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

class BleMessageAssembler(
    private val codec: BleFrameCodec =
        BleFrameCodec()
) {

    private data class PendingMessage(
        val messageType: BleMessageType,
        val totalChunks: Int,
        val createdAtMillis: Long,
        val chunks: Array<ByteArray?>,
        var receivedBytes: Int = 0
    )

    private val pendingMessages =
        ConcurrentHashMap<UUID, PendingMessage>()

    @Synchronized
    fun accept(
        packet: ByteArray
    ): AssembledBleMessage? {
        removeExpiredMessages()

        val frame = codec.decode(packet)

        require(
            frame.totalChunks <= MAX_CHUNKS
        ) {
            "El mensaje contiene demasiados fragmentos"
        }

        val pending = pendingMessages.getOrPut(
            frame.messageId
        ) {
            PendingMessage(
                messageType = frame.messageType,
                totalChunks = frame.totalChunks,
                createdAtMillis =
                    System.currentTimeMillis(),
                chunks =
                    arrayOfNulls(frame.totalChunks)
            )
        }

        require(
            pending.messageType == frame.messageType
        ) {
            "Los fragmentos tienen tipos diferentes"
        }

        require(
            pending.totalChunks == frame.totalChunks
        ) {
            "Los fragmentos tienen totales diferentes"
        }

        val previousChunk =
            pending.chunks[frame.chunkIndex]

        if (previousChunk != null) {
            require(
                previousChunk.contentEquals(
                    frame.payload
                )
            ) {
                "Se recibió un fragmento duplicado diferente"
            }

            return null
        }

        val newTotalSize =
            pending.receivedBytes +
                    frame.payload.size

        require(newTotalSize <= MAX_MESSAGE_SIZE) {
            pendingMessages.remove(frame.messageId)
            "El mensaje supera el tamaño permitido"
        }

        pending.chunks[frame.chunkIndex] =
            frame.payload.copyOf()

        pending.receivedBytes = newTotalSize

        val complete = pending.chunks.all {
            it != null
        }

        if (!complete) {
            return null
        }

        val completePayload =
            ByteArray(pending.receivedBytes)

        var destinationOffset = 0

        pending.chunks.forEach { chunk ->
            val requiredChunk = requireNotNull(chunk)

            requiredChunk.copyInto(
                destination = completePayload,
                destinationOffset = destinationOffset
            )

            destinationOffset += requiredChunk.size
        }

        pendingMessages.remove(frame.messageId)

        return AssembledBleMessage(
            messageType = pending.messageType,
            messageId = frame.messageId,
            payload = completePayload
        )
    }

    @Synchronized
    fun clear() {
        pendingMessages.clear()
    }

    @Synchronized
    private fun removeExpiredMessages() {
        val expirationLimit =
            System.currentTimeMillis() -
                    MESSAGE_TIMEOUT_MILLIS

        pendingMessages.entries.removeIf {
            it.value.createdAtMillis <
                    expirationLimit
        }
    }

    private companion object {
        const val MESSAGE_TIMEOUT_MILLIS =
            30_000L

        const val MAX_MESSAGE_SIZE =
            256 * 1024

        const val MAX_CHUNKS = 2_048
    }
}