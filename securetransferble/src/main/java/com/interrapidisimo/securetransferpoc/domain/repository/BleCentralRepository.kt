package com.interrapidisimo.securetransferpoc.domain.repository

import com.interrapidisimo.securetransferpoc.domain.model.BleCentralState
import kotlinx.coroutines.flow.StateFlow

interface BleCentralRepository {

    val state: StateFlow<BleCentralState>

    fun scanAndConnect()

    fun disconnect()

    fun confirmSession(sessionId: String)

    fun sendReceiverHandshake(
        handshakeJson: String,
        sessionKeyFingerprint: String
    )

    val supportsReplies: Boolean

    suspend fun sendReply(text: String)
}