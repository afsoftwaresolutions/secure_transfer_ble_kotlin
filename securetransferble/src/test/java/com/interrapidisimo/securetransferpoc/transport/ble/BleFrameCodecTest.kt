package com.interrapidisimo.securetransferpoc.transport.ble

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BleFrameCodecTest {

    private val codec = BleFrameCodec()

    @Test
    fun fragmentAndReassemble_recoversOriginalMessage() {
        val originalPayload = ByteArray(600) {
            (it % 256).toByte()
        }

        val packets = codec.fragment(
            messageType =
                BleMessageType.RECEIVER_HANDSHAKE,
            payload = originalPayload,
            negotiatedMtu = 185
        )

        assertTrue(packets.size > 1)

        val assembler = BleMessageAssembler(codec)

        var completedMessage:
                AssembledBleMessage? = null

        packets.reversed().forEach { packet ->
            val result = assembler.accept(packet)

            if (result != null) {
                completedMessage = result
            }
        }

        assertNotNull(completedMessage)

        assertEquals(
            BleMessageType.RECEIVER_HANDSHAKE,
            completedMessage?.messageType
        )

        assertTrue(
            originalPayload.contentEquals(
                completedMessage?.payload
            )
        )
    }

    @Test
    fun fragmentPackets_neverExceedNegotiatedMtu() {
        val mtu = 185

        val packets = codec.fragment(
            messageType =
                BleMessageType.RECEIVER_HANDSHAKE,
            payload = ByteArray(1_000),
            negotiatedMtu = mtu
        )

        packets.forEach { packet ->
            assertTrue(
                packet.size <= mtu - 3
            )
        }
    }

    @Test
    fun decode_rejectsInvalidMagicBytes() {
        val packet = codec.fragment(
            messageType =
                BleMessageType.ACK,
            payload = "OK".encodeToByteArray(),
            negotiatedMtu = 185
        ).first()

        packet[0] = 0

        val result = runCatching {
            codec.decode(packet)
        }

        assertTrue(result.isFailure)
    }
}