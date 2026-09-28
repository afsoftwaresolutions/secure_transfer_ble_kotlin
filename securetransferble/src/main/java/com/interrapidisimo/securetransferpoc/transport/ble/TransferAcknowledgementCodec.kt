package com.interrapidisimo.securetransferpoc.transport.ble

import com.interrapidisimo.securetransferpoc.domain.model.TransferAcknowledgement
import org.json.JSONObject

object TransferAcknowledgementCodec {

    fun encode(
        acknowledgement: TransferAcknowledgement
    ): String {
        return JSONObject()
            .put(
                "protocolVersion",
                acknowledgement.protocolVersion
            )
            .put(
                "sessionId",
                acknowledgement.sessionId
            )
            .put(
                "acknowledgedMessageId",
                acknowledgement.acknowledgedMessageId
            )
            .put(
                "duplicate",
                acknowledgement.duplicate
            )
            .toString()
    }

    fun decode(
        json: String
    ): TransferAcknowledgement {
        val value = JSONObject(json)

        return TransferAcknowledgement(
            protocolVersion =
                value.getInt("protocolVersion"),
            sessionId =
                value.getString("sessionId"),
            acknowledgedMessageId =
                value.getString(
                    "acknowledgedMessageId"
                ),
            duplicate =
                value.getBoolean("duplicate")
        )
    }
}