package com.interrapidisimo.securetransferpoc.transport.ble

import com.interrapidisimo.securetransferpoc.domain.model.ReceiverHandshake
import org.json.JSONObject
import javax.inject.Inject

class ReceiverHandshakeCodec @Inject constructor() {

    fun encode(
        handshake: ReceiverHandshake
    ): String {
        return JSONObject()
            .put(
                "protocolVersion",
                handshake.protocolVersion
            )
            .put(
                "sessionId",
                handshake.sessionId
            )
            .put(
                "receiverApp",
                handshake.receiverApp
            )
            .put(
                "createdAtEpochMillis",
                handshake.createdAtEpochMillis
            )
            .put(
                "nonce",
                handshake.nonce
            )
            .put(
                "identityPublicKey",
                handshake.identityPublicKey
            )
            .put(
                "ephemeralPublicKey",
                handshake.ephemeralPublicKey
            )
            .put(
                "signature",
                handshake.signature
            )
            .toString()
    }

    fun decode(
        json: String
    ): ReceiverHandshake {
        return JSONObject(json).run {
            ReceiverHandshake(
                protocolVersion =
                    getInt("protocolVersion"),
                sessionId =
                    getString("sessionId"),
                receiverApp =
                    getString("receiverApp"),
                createdAtEpochMillis =
                    getLong("createdAtEpochMillis"),
                nonce =
                    getString("nonce"),
                identityPublicKey =
                    getString("identityPublicKey"),
                ephemeralPublicKey =
                    getString("ephemeralPublicKey"),
                signature =
                    getString("signature")
            )
        }
    }
}