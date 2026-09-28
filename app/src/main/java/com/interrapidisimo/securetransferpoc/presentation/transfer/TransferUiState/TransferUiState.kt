package com.interrapidisimo.securetransferpoc.presentation.transfer.TransferUiState

data class TransferUiState(

    val selectedRole: String? = null,

    val outgoingText: String = """
    {
      "guia": "12345678910",
      "estado": "ENTREGADA",
      "usuario": "84521",
      "observacion": "Transferencia BLE cifrada"
    }
""".trimIndent(),

    val message: String = "Identidad aún no creada",
    val publicKeyPreview: String? = null,
    val isLoading: Boolean = false,
    val error: String? = null,

    val challengePreview: String? = null,
    val signaturePreview: String? = null,
    val identityVerified: Boolean? = null,

    val ecdhPublicKeyA: String? = null,
    val ecdhPublicKeyB: String? = null,
    val secretFingerprintA: String? = null,
    val secretFingerprintB: String? = null,
    val sameSharedSecret: Boolean? = null,

    val sessionId: String? = null,
    val sessionKeyFingerprintA: String? = null,
    val sessionKeyFingerprintB: String? = null,
    val sameSessionKey: Boolean? = null,

    val encryptedMessagePreview: String? = null,
    val decryptedMessage: String? = null,
    val decryptionSuccessful: Boolean? = null,
    val modifiedMessageWasRejected: Boolean? = null,

    val invitationSessionId: String? = null,
    val invitationJson: String? = null,
    val invitationSignatureValid: Boolean? = null,

    val scannedInvitationValid: Boolean? = null,
    val scannedSessionId: String? = null,
    val scannedSourceApp: String? = null,
    val scannedSourceDeviceId: String? = null,

    val scannedSenderEphemeralPublicKey: String? = null,

    val receiverEphemeralPublicKey: String? = null,
    val receiverSessionKeyFingerprint: String? = null,
    val receiverEcdhReady: Boolean = false,

    val receiverHandshakeJson: String? = null,
    val receiverHandshakeReady: Boolean = false,

    val blePermissionsGranted: Boolean = false,
    val bleSupported: Boolean? = null,
    val bluetoothEnabled: Boolean? = null,
    val canScanBle: Boolean? = null,
    val canAdvertiseBle: Boolean? = null,
    val bleReady: Boolean = false,

    val peripheralStatus: String = "IDLE",
    val peripheralMessage: String = "Peripheral detenido",
    val connectedBleDevice: String? = null,

    val centralStatus: String = "IDLE",
    val centralMessage: String = "Cliente BLE detenido",
    val connectedDeviceAddress: String? = null,

    val receivedMessageId: String? = null,
    val receivedJson: String? = null,

    val receivedReply: String? = null,

    val canSendReply: Boolean = false,

    val generatedSessionId: String? = null,

)