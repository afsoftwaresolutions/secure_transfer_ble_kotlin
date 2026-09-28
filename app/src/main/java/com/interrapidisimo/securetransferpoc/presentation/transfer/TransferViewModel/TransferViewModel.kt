package com.interrapidisimo.securetransferpoc.presentation.transfer.TransferViewModel

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.interrapidisimo.securetransferpoc.domain.usecase.BleCentralUseCase
import com.interrapidisimo.securetransferpoc.domain.usecase.BlePeripheralUseCase
import com.interrapidisimo.securetransferpoc.domain.usecase.CheckBleCapabilitiesUseCase
import com.interrapidisimo.securetransferpoc.domain.usecase.CreateAndVerifyChallengeUseCase
import com.interrapidisimo.securetransferpoc.domain.usecase.CreateSignedInvitationUseCase
import com.interrapidisimo.securetransferpoc.domain.usecase.CreateSignedReceiverHandshakeUseCase
import com.interrapidisimo.securetransferpoc.domain.usecase.DemonstrateEcdhUseCase
import com.interrapidisimo.securetransferpoc.domain.usecase.GetOrCreateDeviceIdentityUseCase
import com.interrapidisimo.securetransferpoc.domain.usecase.PrepareReceiverSessionUseCase
import com.interrapidisimo.securetransferpoc.domain.usecase.ValidateScannedInvitationUseCase
import com.interrapidisimo.securetransferpoc.presentation.transfer.TransferUiState.TransferUiState
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject
import com.interrapidisimo.securetransferpoc.SecureBleTransfer

@HiltViewModel
class TransferViewModel @Inject constructor(
    private val getOrCreateDeviceIdentity: GetOrCreateDeviceIdentityUseCase,
    private val createAndVerifyChallenge: CreateAndVerifyChallengeUseCase,
    private val demonstrateEcdh: DemonstrateEcdhUseCase,
    private val createSignedInvitation: CreateSignedInvitationUseCase,
    private val checkBleCapabilities: CheckBleCapabilitiesUseCase,
    private val blePeripheral: BlePeripheralUseCase,
    private val secureBleTransfer: SecureBleTransfer
) : ViewModel() {

    private val _uiState = MutableStateFlow(TransferUiState())

    val uiState: StateFlow<TransferUiState> = _uiState.asStateFlow()

    private var automaticSessionConfirmationStarted = false

    private var automaticHandshakeStarted = false

    init {
        observePeripheralState()
        observeCentralState()
        observeReplies()
    }

    fun startSimplifiedSenderFlow() {
        automaticSessionConfirmationStarted = false
        automaticHandshakeStarted = false

        secureBleTransfer.stopReceiving()
        blePeripheral.stop()

        viewModelScope.launch {
            _uiState.update {
                it.copy(
                    selectedRole = "SENDER",
                    isLoading = true,
                    error = null,
                    message =
                        "Preparando transferencia segura...",
                    invitationJson = null,
                    generatedSessionId = null,
                    receivedJson = null,
                    receivedMessageId = null,
                    receivedReply = null,

                )
            }

            runCatching {
                val identity = getOrCreateDeviceIdentity()

                val invitation = secureBleTransfer.startSending()

                require(invitation.signatureValid) {
                    "La invitación no quedó firmada correctamente"
                }

                Pair(identity, invitation)
            }.onSuccess { result ->

                val identity = result.first
                val invitation = result.second

                _uiState.update {
                    it.copy(
                        isLoading = false,
                        message =
                            "QR listo. Esperando al receptor...",
                        publicKeyPreview = identity.publicKey.take(40) + "…",
                        invitationSessionId = invitation.sessionId,
                        invitationJson = invitation.invitationJson,
                        invitationSignatureValid = invitation.signatureValid,
                        generatedSessionId = invitation.sessionId
                    )
                }

            }.onFailure { error ->
                _uiState.update {
                    it.copy(
                        isLoading = false,
                        message = "No fue posible iniciar el emisor",
                        error = error.message
                    )
                }
            }
        }
    }

    fun prepareSimplifiedReceiverFlow() {
        automaticSessionConfirmationStarted = false
        automaticHandshakeStarted = false

        blePeripheral.stop()
        secureBleTransfer.stopReceiving()

        _uiState.update {
            it.copy(
                selectedRole = "RECEIVER",
                isLoading = false,
                error = null,
                message = "Escanea el QR del dispositivo emisor",
                scannedInvitationValid = null,
                scannedSessionId = null,
                receiverHandshakeJson = null,
                receiverHandshakeReady = false,
                centralStatus = "IDLE",
                centralMessage = "Esperando lectura del QR",
                receivedMessageId = null,
                canSendReply = false,
                receivedJson = null
            )
        }
    }

    fun createOrLoadIdentity() {

        viewModelScope.launch {
            _uiState.update {
                it.copy(
                    isLoading = true,
                    error = null,
                    message = "Procesando identidad..."
                )
            }

            runCatching {
                getOrCreateDeviceIdentity()
            }.onSuccess { identity ->
                _uiState.update {
                    it.copy(
                        isLoading = false,
                        message = if (identity.wasCreated) {
                            "Nueva identidad creada"
                        } else {
                            "Identidad existente recuperada"
                        },
                        publicKeyPreview =
                            identity.publicKey.take(40) + "…"
                    )
                }
            }.onFailure { throwable ->
                _uiState.update {
                    it.copy(
                        isLoading = false,
                        message = "No fue posible obtener la identidad",
                        error = throwable.message
                    )
                }
            }
        }
    }

    fun verifyIdentityOwnership() {
        viewModelScope.launch {
            _uiState.update {
                it.copy(
                    isLoading = true,
                    error = null,
                    message = "Firmando desafío..."
                )
            }

            runCatching {
                createAndVerifyChallenge()
            }.onSuccess { result ->
                _uiState.update {
                    it.copy(
                        isLoading = false,
                        message = if (
                            result.isValid &&
                            result.modifiedChallengeWasRejected
                        ) {
                            "Identidad demostrada correctamente"
                        } else {
                            "La comprobación de identidad falló"
                        },
                        challengePreview =
                            result.challenge.take(32) + "…",
                        signaturePreview =
                            result.signature.take(32) + "…",
                        identityVerified =
                            result.isValid &&
                                    result.modifiedChallengeWasRejected
                    )
                }
            }.onFailure { throwable ->
                _uiState.update {
                    it.copy(
                        isLoading = false,
                        message = "Error verificando la identidad",
                        error = throwable.message
                    )
                }
            }
        }
    }

    fun demonstrateEcdhAgreement() {
        viewModelScope.launch {
            _uiState.update {
                it.copy(
                    isLoading = true,
                    error = null,
                    message = "Calculando secreto compartido..."
                )
            }

            runCatching {
                demonstrateEcdh()
            }.onSuccess { result ->
                _uiState.update {
                    it.copy(
                        isLoading = false,
                        message = if (
                            result.sameSecret &&
                            result.sameSessionKey &&
                            result.decryptionSuccessful &&
                            result.modifiedMessageWasRejected
                        ) {
                            "Transferencia cifrada demostrada correctamente"
                        } else {
                            "La demostración criptográfica falló"
                        },
                        ecdhPublicKeyA =
                            result.publicKeyA.take(28) + "…",
                        ecdhPublicKeyB =
                            result.publicKeyB.take(28) + "…",
                        secretFingerprintA =
                            result.secretFingerprintA.take(28) + "…",
                        secretFingerprintB =
                            result.secretFingerprintB.take(28) + "…",
                        sameSharedSecret = result.sameSecret,
                        sessionId = result.sessionId,
                        sessionKeyFingerprintA =
                            result.sessionKeyFingerprintA.take(28) + "…",
                        sessionKeyFingerprintB =
                            result.sessionKeyFingerprintB.take(28) + "…",
                        sameSessionKey = result.sameSessionKey,
                        encryptedMessagePreview =
                            result.encryptedMessage.take(40) + "…",
                        decryptedMessage = result.decryptedMessage,
                        decryptionSuccessful =
                            result.decryptionSuccessful,
                        modifiedMessageWasRejected =
                            result.modifiedMessageWasRejected
                    )
                }
            }.onFailure { throwable ->
                _uiState.update {
                    it.copy(
                        isLoading = false,
                        message = "Error ejecutando ECDH",
                        error = throwable.message
                    )
                }
            }
        }
    }

    fun createSignedInvitation() {
        viewModelScope.launch {
            _uiState.update {
                it.copy(
                    isLoading = true,
                    error = null,
                    message = "Creando invitación..."
                )
            }

            runCatching {
                createSignedInvitation.invoke()
            }.onSuccess { result ->
                _uiState.update {
                    it.copy(
                        isLoading = false,
                        message = if (
                            result.signatureValid
                        ) {
                            "Invitación creada y firmada"
                        } else {
                            "La firma de la invitación no es válida"
                        },
                        invitationSessionId =
                            result.sessionId,
                        invitationJson =
                            result.invitationJson,
                        invitationSignatureValid =
                            result.signatureValid,
                        generatedSessionId = result.sessionId
                    )
                }
            }.onFailure { throwable ->
                _uiState.update {
                    it.copy(
                        isLoading = false,
                        message =
                            "Error creando la invitación",
                        error = throwable.message
                    )
                }
            }
        }
    }

    fun processScannedInvitation(
        rawContent: String
    ) {
        viewModelScope.launch {
            _uiState.update {
                it.copy(
                    isLoading = true,
                    error = null,
                    message = "Validando invitación...",
                    receiverEcdhReady = false,
                    receiverEphemeralPublicKey = null,
                    receiverSessionKeyFingerprint = null,
                    receiverHandshakeJson = null,
                    receiverHandshakeReady = false
                )
            }

            runCatching {
                secureBleTransfer.prepareReceiving(rawContent)
            }.onSuccess { processingResult ->
                val validationResult = processingResult.invitation
                val receiverKeyAgreement = processingResult.keyAgreement
                val signedHandshake = processingResult.handshake

                _uiState.update {
                    it.copy(
                        isLoading = false,
                        message = if (
                            validationResult.isValid &&
                            receiverKeyAgreement != null &&
                            signedHandshake != null
                        ) {
                            "QR válido, ECDH y handshake preparados"
                        } else {
                            validationResult.message
                        },
                        scannedInvitationValid = validationResult.isValid,
                        scannedSourceApp =  validationResult.sourceApp,
                        scannedSourceDeviceId = validationResult.sourceDeviceId,
                        scannedSessionId = validationResult.sessionId,
                        scannedSenderEphemeralPublicKey = validationResult.senderEphemeralPublicKey,
                        receiverEphemeralPublicKey = receiverKeyAgreement?.receiverEphemeralPublicKey,
                        receiverSessionKeyFingerprint = receiverKeyAgreement?.sessionKeyFingerprint,
                        receiverEcdhReady = receiverKeyAgreement != null,
                        receiverHandshakeJson = signedHandshake?.handshakeJson,
                        receiverHandshakeReady = signedHandshake != null
                    )
                }

                if (validationResult.isValid &&
                    receiverKeyAgreement != null &&
                    signedHandshake != null
                ) {
                    scanAndConnectBle()
                }

            }.onFailure { throwable ->
                _uiState.update {
                    it.copy(
                        isLoading = false,
                        message =
                            "No se pudo preparar el handshake",
                        error = throwable.message,
                        scannedInvitationValid = false,
                        scannedSessionId = null,
                        scannedSenderEphemeralPublicKey = null,
                        receiverEphemeralPublicKey = null,
                        receiverSessionKeyFingerprint = null,
                        receiverEcdhReady = false,
                        receiverHandshakeJson = null,
                        receiverHandshakeReady = false
                    )
                }
            }
        }
    }

    fun onBlePermissionsResult(
        allGranted: Boolean
    ) {
        _uiState.update {
            it.copy(
                blePermissionsGranted = allGranted,
                message = if (allGranted) {
                    "Permisos BLE concedidos"
                } else {
                    "Se requieren permisos Bluetooth"
                }
            )
        }

        if (allGranted) {
            checkBleCapabilities()
        }
    }

    fun checkBleCapabilities() {
        viewModelScope.launch {
            runCatching {
                checkBleCapabilities.invoke()
            }.onSuccess { capabilities ->

                val statusMessage = when {
                    !capabilities.bleSupported ->
                        "Este dispositivo no soporta BLE"

                    !capabilities.bluetoothEnabled ->
                        "Bluetooth está apagado"

                    !capabilities.canScan ->
                        "El dispositivo no puede escanear BLE"

                    !capabilities.canAdvertise ->
                        "El dispositivo no puede actuar como Peripheral"

                    else ->
                        "Dispositivo listo para enviar y recibir por BLE"
                }

                _uiState.update {
                    it.copy(
                        message = statusMessage,
                        bleSupported =
                            capabilities.bleSupported,
                        bluetoothEnabled =
                            capabilities.bluetoothEnabled,
                        canScanBle =
                            capabilities.canScan,
                        canAdvertiseBle =
                            capabilities.canAdvertise,
                        bleReady =
                            capabilities.readyForTransfer
                    )
                }
            }.onFailure { throwable ->
                _uiState.update {
                    it.copy(
                        message =
                            "Error comprobando Bluetooth",
                        error = throwable.message
                    )
                }
            }
        }
    }

    private fun observePeripheralState() {
        viewModelScope.launch {
            blePeripheral.state.collect { peripheral ->
                _uiState.update {
                    it.copy(
                        peripheralStatus =
                            peripheral.status.name,
                        peripheralMessage =
                            peripheral.message,
                        connectedBleDevice =
                            peripheral.connectedDevice
                    )
                }
            }
        }
    }

    fun startBlePeripheral() {
        val sessionId = _uiState.value.generatedSessionId

        if (sessionId.isNullOrBlank()) {
            _uiState.update { currentState ->
                currentState.copy(
                    peripheralMessage =
                        "Primero debes generar un QR"
                )
            }
            return
        }

        blePeripheral.start(sessionId)
    }

    fun stopBlePeripheral() {
        blePeripheral.stop()
    }

    fun onOutgoingTextChanged(
        value: String
    ) {
        _uiState.update {
            it.copy(
                outgoingText = value,
                error = null
            )
        }
    }

    fun sendOutgoingText() {
        val state = _uiState.value

        if (
            state.peripheralStatus !=
            "SESSION_KEY_READY" &&
            state.peripheralStatus !=
            "DATA_CONFIRMED"
        ) {
            _uiState.update {
                it.copy(
                    peripheralMessage =
                        "Primero debe completarse el intercambio ECDH " +
                                "o confirmarse la transferencia anterior"
                )
            }

            return
        }

        val text = state.outgoingText

        if (text.isBlank()) {
            _uiState.update {
                it.copy(
                    peripheralMessage =
                        "Escribe o pega información antes de enviarla"
                )
            }

            return
        }

        val sizeInBytes = text.encodeToByteArray().size

        if (sizeInBytes > MAX_OUTGOING_TEXT_BYTES) {
            _uiState.update {
                it.copy(
                    peripheralMessage =
                        "El contenido supera el límite del POC de " +
                                "$MAX_OUTGOING_TEXT_BYTES bytes"
                )
            }

            return
        }

        secureBleTransfer.sendText(text)
    }

    fun sendReply() {
        val state = _uiState.value
        if (!state.canSendReply || state.isLoading) return

        val text = state.outgoingText
        if (text.isBlank()) {
            _uiState.update {
                it.copy(error = "Escribe una respuesta antes de enviarla")
            }
            return
        }

        _uiState.update {
            it.copy(
                isLoading = true,
                error = null,
                message = "Enviando respuesta..."
            )
        }

        viewModelScope.launch {
            runCatching {
                secureBleTransfer.sendReply(text)
            }.onSuccess {
                _uiState.update {
                    it.copy(
                        isLoading = false,
                        message = "Flutter confirmó la respuesta"
                    )
                }
            }.onFailure { error ->
                _uiState.update {
                    it.copy(
                        isLoading = false,
                        message = "No se confirmó la respuesta",
                        error = error.message
                    )
                }
            }
        }
    }

    private fun observeCentralState() {
        viewModelScope.launch {
            secureBleTransfer.receiverStates.collect { centralState ->
                _uiState.update { currentState ->
                    currentState.copy(
                        centralStatus = centralState.status.name,
                        centralMessage = centralState.message,
                        connectedDeviceAddress = centralState.deviceAddress,
                        receivedMessageId = centralState.receivedMessageId,
                        receivedJson = centralState.receivedData,
                        canSendReply =
                            secureBleTransfer.supportsReplies &&
                                    centralState.status.name in setOf(
                                "SESSION_KEY_READY",
                                "ACK_SENT"
                            ),

                    )
                }

                when (centralState.status.name) {
                    "SERVICE_READY" -> {
                        if (
                            !automaticSessionConfirmationStarted
                        ) {
                            automaticSessionConfirmationStarted =
                                true

                            confirmBleSession()
                        }
                    }

                    "SESSION_VERIFIED" -> {
                        if (
                            !automaticHandshakeStarted
                        ) {
                            automaticHandshakeStarted = true
                            exchangeEcdhKeys()
                        }
                    }
                }
            }
        }
    }

    fun scanAndConnectBle() {
        secureBleTransfer.connectReceiver()
    }

    fun disconnectCentralBle() {
        automaticSessionConfirmationStarted = false
        automaticHandshakeStarted = false

        secureBleTransfer.stopReceiving()
    }

    fun confirmBleSession() {
        val sessionId = _uiState.value.scannedSessionId

        if (sessionId.isNullOrBlank()) {
            _uiState.update { currentState ->
                currentState.copy(
                    centralMessage = "Primero debes escanear un QR válido"
                )
            }
            return
        }

        secureBleTransfer.confirmReceiverSession()
    }

    fun exchangeEcdhKeys() {
        val state = _uiState.value

        if (state.centralStatus != "SESSION_VERIFIED") {
            _uiState.update {
                it.copy(
                    centralMessage =
                        "Primero debes confirmar la sesión"
                )
            }
            return
        }

        val handshakeJson =
            state.receiverHandshakeJson

        val fingerprint =
            state.receiverSessionKeyFingerprint

        if (
            handshakeJson.isNullOrBlank() ||
            fingerprint.isNullOrBlank() ||
            !state.receiverHandshakeReady
        ) {
            _uiState.update {
                it.copy(
                    centralMessage =
                        "El handshake firmado no está preparado"
                )
            }
            return
        }

        secureBleTransfer.sendReceiverHandshake()
    }

    private fun observeReplies() {
        viewModelScope.launch {
            secureBleTransfer.receivedReplies.collect { text ->
                _uiState.update { state ->
                    state.copy(receivedReply = text)
                }
            }
        }
    }

    companion object {
        private const val MAX_OUTGOING_TEXT_BYTES = 100_000
    }
}