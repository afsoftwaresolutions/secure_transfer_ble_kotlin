package com.interrapidisimo.securetransferpoc

import com.interrapidisimo.securetransferpoc.domain.model.BleCentralState
import com.interrapidisimo.securetransferpoc.domain.model.BlePeripheralState
import com.interrapidisimo.securetransferpoc.domain.model.ReceiverKeyAgreementResult
import com.interrapidisimo.securetransferpoc.domain.model.ScannedInvitationResult
import com.interrapidisimo.securetransferpoc.domain.model.SignedInvitationResult
import com.interrapidisimo.securetransferpoc.domain.model.SignedReceiverHandshakeResult
import com.interrapidisimo.securetransferpoc.domain.usecase.BleCentralUseCase
import com.interrapidisimo.securetransferpoc.domain.usecase.BlePeripheralUseCase
import com.interrapidisimo.securetransferpoc.domain.usecase.CreateSignedInvitationUseCase
import com.interrapidisimo.securetransferpoc.domain.usecase.CreateSignedReceiverHandshakeUseCase
import com.interrapidisimo.securetransferpoc.domain.usecase.PrepareReceiverSessionUseCase
import com.interrapidisimo.securetransferpoc.domain.usecase.ValidateScannedInvitationUseCase
import kotlinx.coroutines.flow.StateFlow
import javax.inject.Inject
import javax.inject.Singleton
import com.interrapidisimo.securetransferpoc.domain.model.BleCentralStatus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch

data class ReceiverPreparation(
    val invitation: ScannedInvitationResult,
    val keyAgreement: ReceiverKeyAgreementResult? = null,
    val handshake: SignedReceiverHandshakeResult? = null
)

@Singleton
class SecureBleTransfer @Inject constructor(
    private val createSignedInvitation: CreateSignedInvitationUseCase,
    private val peripheral: BlePeripheralUseCase,
    private val central: BleCentralUseCase,
    private val validateInvitation: ValidateScannedInvitationUseCase,
    private val prepareReceiverSession: PrepareReceiverSessionUseCase,
    private val createReceiverHandshake: CreateSignedReceiverHandshakeUseCase
) {

    private var receiverPreparation: ReceiverPreparation? = null
    private val receiverScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var receiverConnectionJob: Job? = null

    val senderState: BlePeripheralState
        get() = peripheral.state.value

    val senderStates: StateFlow<BlePeripheralState>
        get() = peripheral.state

    val receiverState: BleCentralState
        get() = central.state.value

    val receiverStates: StateFlow<BleCentralState>
        get() = central.state

    val supportsReplies: Boolean
        get() = central.supportsReplies

    val receivedReplies: SharedFlow<String>
        get() = peripheral.receivedReplies


    suspend fun startSending(): SignedInvitationResult {
        val invitation = createSignedInvitation()
        check(invitation.signatureValid) {
            "La invitación no superó la verificación local"
        }
        peripheral.start(invitation.sessionId)
        return invitation
    }

    fun sendText(text: String) {
        peripheral.sendEncryptedData(text)
    }

    fun stopSending() {
        peripheral.stop()
    }

    suspend fun prepareReceiving(rawContent: String): ReceiverPreparation {

        receiverConnectionJob?.cancel()
        receiverConnectionJob = null
        receiverPreparation = null

        val invitation = validateInvitation(rawContent)

        if (!invitation.isValid) {
            return ReceiverPreparation(invitation = invitation)
        }

        val sessionId = requireNotNull(invitation.sessionId) {
            "La invitación no contiene sessionId"
        }
        val senderPublicKey =
            requireNotNull(invitation.senderEphemeralPublicKey) {
                "La invitación no contiene clave ECDH"
            }

        val keyAgreement = prepareReceiverSession(
            sessionId = sessionId,
            senderEphemeralPublicKey = senderPublicKey
        )
        val handshake = createReceiverHandshake(
            sessionId = sessionId,
            receiverEphemeralPublicKey =
                keyAgreement.receiverEphemeralPublicKey
        )

        return ReceiverPreparation(
            invitation = invitation,
            keyAgreement = keyAgreement,
            handshake = handshake
        ).also {
            receiverPreparation = it
        }

    }

    fun connectReceiver() {
        val preparation = checkNotNull(receiverPreparation) {
            "Primero debes preparar un QR válido"
        }
        val sessionId = checkNotNull(preparation.invitation.sessionId) {
            "La invitación no contiene sessionId"
        }
        val handshake = checkNotNull(preparation.handshake) {
            "No hay un handshake preparado"
        }
        val keyAgreement = checkNotNull(preparation.keyAgreement) {
            "No hay una clave de sesión preparada"
        }

        check(central.state.value.status == BleCentralStatus.IDLE) {
            "Detén la recepción anterior antes de conectar"
        }

        receiverConnectionJob?.cancel()
        receiverConnectionJob = receiverScope.launch {
            var helloSent = false
            var handshakeSent = false

            central.state.collect { state ->
                when (state.status) {
                    BleCentralStatus.SERVICE_READY -> {
                        if (!helloSent) {
                            helloSent = true
                            central.confirmSession(sessionId)
                        }
                    }

                    BleCentralStatus.SESSION_VERIFIED -> {
                        if (!handshakeSent) {
                            handshakeSent = true
                            central.sendReceiverHandshake(
                                handshakeJson = handshake.handshakeJson,
                                sessionKeyFingerprint =
                                    keyAgreement.sessionKeyFingerprint
                            )
                        }
                    }

                    else -> Unit
                }
            }
        }

        central.scanAndConnect()
    }

    fun stopReceiving() {
        receiverConnectionJob?.cancel()
        receiverConnectionJob = null
        receiverPreparation = null
        central.disconnect()
    }

    fun confirmReceiverSession() {
        val sessionId = checkNotNull(
            receiverPreparation?.invitation?.sessionId
        ) {
            "No hay una invitación preparada"
        }
        central.confirmSession(sessionId)
    }

    fun sendReceiverHandshake() {
        val preparation = checkNotNull(receiverPreparation) {
            "No hay una invitación preparada"
        }
        val handshake = checkNotNull(preparation.handshake) {
            "No hay un handshake preparado"
        }
        val keyAgreement = checkNotNull(preparation.keyAgreement) {
            "No hay una clave de sesión preparada"
        }

        central.sendReceiverHandshake(
            handshakeJson = handshake.handshakeJson,
            sessionKeyFingerprint = keyAgreement.sessionKeyFingerprint
        )
    }

    suspend fun sendReply(text: String) {
        central.sendReply(text)
    }
}