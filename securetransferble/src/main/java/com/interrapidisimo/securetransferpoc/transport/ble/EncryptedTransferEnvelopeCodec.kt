package com.interrapidisimo.securetransferpoc.transport.ble

import com.interrapidisimo.securetransferpoc.domain.model.EncryptedTransferEnvelope
import org.json.JSONObject

object EncryptedTransferEnvelopeCodec {

    fun encode(
        envelope: EncryptedTransferEnvelope
    ): String {
        return JSONObject()
            .put(
                "protocolVersion",
                envelope.protocolVersion
            )
            .put(
                "sessionId",
                envelope.sessionId
            )
            .put(
                "messageId",
                envelope.messageId
            )
            .put(
                "iv",
                envelope.iv
            )
            .put(
                "cipherText",
                envelope.cipherText
            )
            .toString()
    }

    fun decode(
        json: String
    ): EncryptedTransferEnvelope {
        val jsonObject = JSONObject(json)

        return EncryptedTransferEnvelope(
            protocolVersion =
                jsonObject.getInt("protocolVersion"),
            sessionId =
                jsonObject.getString("sessionId"),
            messageId =
                jsonObject.getString("messageId"),
            iv =
                jsonObject.getString("iv"),
            cipherText =
                jsonObject.getString("cipherText")
        )
    }
}