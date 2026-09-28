package com.interrapidisimo.securetransferpoc.domain.usecase

import com.interrapidisimo.securetransferpoc.domain.repository.BleCentralRepository
import javax.inject.Inject

class BleCentralUseCase @Inject constructor(
    private val repository: BleCentralRepository
) {
    val state = repository.state

    fun scanAndConnect() {
        repository.scanAndConnect()
    }

    fun disconnect() {
        repository.disconnect()
    }

    fun confirmSession(sessionId: String) {
        repository.confirmSession(sessionId)
    }

    fun sendReceiverHandshake(
        handshakeJson: String,
        sessionKeyFingerprint: String
    ) {
        repository.sendReceiverHandshake(
            handshakeJson = handshakeJson,
            sessionKeyFingerprint = sessionKeyFingerprint
        )
    }

    val supportsReplies: Boolean
        get() = repository.supportsReplies

    suspend fun sendReply(text: String) {
        repository.sendReply(text)
    }

}