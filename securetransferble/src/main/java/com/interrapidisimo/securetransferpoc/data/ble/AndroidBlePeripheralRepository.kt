package com.interrapidisimo.securetransferpoc.data.ble

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothGattServer
import android.bluetooth.BluetoothGattServerCallback
import android.bluetooth.BluetoothGattService
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.le.AdvertiseCallback
import android.bluetooth.le.AdvertiseData
import android.bluetooth.le.AdvertiseSettings
import android.bluetooth.le.BluetoothLeAdvertiser
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.ParcelUuid
import android.util.Log
import androidx.core.content.ContextCompat
import com.interrapidisimo.securetransferpoc.domain.model.BlePeripheralState
import com.interrapidisimo.securetransferpoc.domain.model.BlePeripheralStatus
import com.interrapidisimo.securetransferpoc.domain.repository.BlePeripheralRepository
import com.interrapidisimo.securetransferpoc.domain.repository.SessionInvitationRepository
import com.interrapidisimo.securetransferpoc.transport.ble.BleProtocol
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.nio.charset.StandardCharsets
import com.interrapidisimo.securetransferpoc.domain.repository.SessionKeyRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton
import com.interrapidisimo.securetransferpoc.domain.usecase.VerifyReceiverHandshakeUseCase
import com.interrapidisimo.securetransferpoc.transport.ble.BleMessageAssembler
import com.interrapidisimo.securetransferpoc.transport.ble.BleMessageType
import android.bluetooth.BluetoothStatusCodes
import com.interrapidisimo.securetransferpoc.transport.ble.BleFrameCodec
import com.interrapidisimo.securetransferpoc.transport.ble.EncryptedTransferEnvelopeCodec
import java.util.UUID
import com.interrapidisimo.securetransferpoc.transport.ble.TransferAcknowledgementCodec
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import com.interrapidisimo.securetransferpoc.domain.repository.SessionMessagePurpose
import com.interrapidisimo.securetransferpoc.domain.model.TransferAcknowledgement
import kotlinx.coroutines.withContext

@Singleton
class AndroidBlePeripheralRepository @Inject constructor(
    @ApplicationContext private val context: Context,
    private val sessionInvitationRepository: SessionInvitationRepository,
    private val sessionKeyRepository: SessionKeyRepository,
    private val verifyReceiverHandshake: VerifyReceiverHandshakeUseCase
) : BlePeripheralRepository {

    companion object {
        private const val DEFAULT_MTU = 23
        private const val ACK_TIMEOUT_MILLIS = 5_000L
        private const val MAX_ACK_RETRIES = 2
    }

    private enum class NotificationKind {
        DATA,
        REVERSE_ACK
    }

    private val mutableState  = MutableStateFlow(BlePeripheralState())

    override val state: StateFlow<BlePeripheralState> = mutableState .asStateFlow()

    private val repositoryScope = CoroutineScope(
        SupervisorJob() + Dispatchers.Default
    )

    private val messageAssembler = BleMessageAssembler()

    private val ackAssembler = BleMessageAssembler()

    private val reverseDataAssembler = BleMessageAssembler()

    private val processedReverseMessageIds = mutableSetOf<String>()

    @Volatile
    private var pendingOutgoingMessageId: String? = null

    private var lastTransferFrames: List<ByteArray> = emptyList()

    private var ackRetryAttempt: Int = 0

    private var ackTimeoutJob: Job? = null

    private val bluetoothManager: BluetoothManager =
        context.getSystemService(
            BluetoothManager::class.java
        )

    private var gattServer: BluetoothGattServer? = null
    private var advertiser: BluetoothLeAdvertiser? = null
    private var activeSessionId: String? = null

    private var connectedReceiver: BluetoothDevice? = null

    private var negotiatedMtu: Int = DEFAULT_MTU

    private var dataNotificationsEnabled: Boolean = false

    private var reverseAckNotificationsEnabled = false

    private val frameCodec = BleFrameCodec()

    private val outgoingDataFrames =
        ArrayDeque<ByteArray>()

    private val notificationLock = Any()
    private var notificationInFlight: NotificationKind? = null
    private val reverseAckFrames = ArrayDeque<ByteArray>()

    private val outgoingDataLock = Any()

    private var totalDataFrames: Int = 0

    private var sentDataFrames: Int = 0

    private val mutableReceivedReplies =
        MutableSharedFlow<String>(extraBufferCapacity = 64)

    override val receivedReplies: SharedFlow<String> =
        mutableReceivedReplies.asSharedFlow()

    private val advertiseCallback =
        object : AdvertiseCallback() {

            override fun onStartSuccess(
                settingsInEffect: AdvertiseSettings
            ) {
                mutableState .value = BlePeripheralState(
                    status = BlePeripheralStatus.ADVERTISING,
                    message =
                        "Servicio BLE publicado",
                    sessionId = activeSessionId
                )
            }

            override fun onStartFailure(
                errorCode: Int
            ) {
                mutableState .value = BlePeripheralState(
                    status =
                        BlePeripheralStatus.ERROR,
                    message =
                        "Error publicando BLE: $errorCode",
                    sessionId = activeSessionId
                )
            }
        }

    private val gattServerCallback =
        object : BluetoothGattServerCallback() {

            override fun onServiceAdded(
                status: Int,
                service: BluetoothGattService
            ) {
                if (
                    status == BluetoothGatt.GATT_SUCCESS
                ) {
                    startBleAdvertising()
                } else {
                    mutableState .value =
                        BlePeripheralState(
                            status =
                                BlePeripheralStatus.ERROR,
                            message =
                                "No se pudo agregar el servicio GATT: $status",
                            sessionId = activeSessionId
                        )
                }
            }

            @SuppressLint("MissingPermission")
            override fun onConnectionStateChange(
                device: BluetoothDevice,
                status: Int,
                newState: Int
            ) {

                if (newState == BluetoothProfile.STATE_DISCONNECTED &&
                    connectedReceiver?.address != device.address
                ) {
                    return
                }

                if (newState == BluetoothProfile.STATE_CONNECTED) {
                    val currentReceiver = connectedReceiver
                    if (currentReceiver != null &&
                        currentReceiver.address != device.address
                    ) {
                        gattServer?.cancelConnection(device)
                        return
                    }
                }

                messageAssembler.clear()

                when (newState) {
                    BluetoothProfile.STATE_CONNECTED -> {

                        connectedReceiver = device

                        mutableState .value =
                            BlePeripheralState(
                                status =
                                    BlePeripheralStatus.CONNECTED,
                                message =
                                    "Un receptor se conectó",
                                sessionId =
                                    activeSessionId,
                                connectedDevice =
                                    device.address
                            )
                    }

                    BluetoothProfile.STATE_DISCONNECTED -> {

                        ackAssembler.clear()
                        reverseDataAssembler.clear()

                        synchronized(processedReverseMessageIds) {
                            processedReverseMessageIds.clear()
                        }

                        synchronized(notificationLock) {
                            notificationInFlight = null
                            reverseAckFrames.clear()
                        }

                        connectedReceiver = null
                        dataNotificationsEnabled = false
                        reverseAckNotificationsEnabled = false
                        negotiatedMtu = DEFAULT_MTU

                        clearPendingTransfer()

                        mutableState .value =
                            BlePeripheralState(
                                status =
                                    BlePeripheralStatus.ADVERTISING,
                                message =
                                    "Receptor desconectado; esperando otro",
                                sessionId =
                                    activeSessionId
                            )
                    }
                }
            }

            @SuppressLint("MissingPermission")
            override fun onCharacteristicWriteRequest(
                device: BluetoothDevice,
                requestId: Int,
                characteristic: BluetoothGattCharacteristic,
                preparedWrite: Boolean,
                responseNeeded: Boolean,
                offset: Int,
                value: ByteArray
            ) {
                val connectPermissionGranted =
                    Build.VERSION.SDK_INT <
                            Build.VERSION_CODES.S ||
                            ContextCompat.checkSelfPermission(
                                context,
                                Manifest.permission.BLUETOOTH_CONNECT
                            ) == PackageManager.PERMISSION_GRANTED

                if (!connectPermissionGranted) {
                    mutableState.value =
                        mutableState.value.copy(
                            status = BlePeripheralStatus.ERROR,
                            message =
                                "No está autorizado BLUETOOTH_CONNECT"
                        )
                    return
                }

                if (connectedReceiver?.address != device.address) {
                    if (responseNeeded) {
                        sendGattResponse(
                            device, requestId,
                            BluetoothGatt.GATT_REQUEST_NOT_SUPPORTED,
                            offset, null
                        )
                    }
                    return
                }

                when (characteristic.uuid) {

                    BleProtocol.REVERSE_DATA_UUID -> {
                        handleReverseDataFrame(
                            device = device,
                            requestId = requestId,
                            packet = value,
                            preparedWrite = preparedWrite,
                            responseNeeded = responseNeeded,
                            offset = offset
                        )
                        return
                    }

                    BleProtocol.TRANSFER_STATUS_UUID -> {
                        handleAckFrame(
                            device = device,
                            requestId = requestId,
                            packet = value,
                            preparedWrite = preparedWrite,
                            responseNeeded = responseNeeded,
                            offset = offset
                        )

                        return
                    }

                    BleProtocol.SESSION_CONTROL_UUID -> {
                        val possibleTextCommand =
                            value.toString(
                                StandardCharsets.UTF_8
                            )

                        if (
                            possibleTextCommand
                                .startsWith("HELLO|")
                        ) {
                            handleHelloRequest(
                                device = device,
                                requestId = requestId,
                                command = possibleTextCommand,
                                preparedWrite = preparedWrite,
                                responseNeeded = responseNeeded,
                                offset = offset
                            )

                            return
                        }

                        handleHandshakeFrame(
                            device = device,
                            requestId = requestId,
                            packet = value,
                            preparedWrite = preparedWrite,
                            responseNeeded = responseNeeded,
                            offset = offset
                        )

                        return
                    }

                    else -> {
                        if (responseNeeded) {
                            sendGattResponse(
                                device = device,
                                requestId = requestId,
                                status = BluetoothGatt.GATT_REQUEST_NOT_SUPPORTED,
                                offset = offset,
                                value = null
                            )
                        }

                        return
                    }
                }
            }

            @Suppress("DEPRECATION")
            override fun onDescriptorWriteRequest(
                device: BluetoothDevice,
                requestId: Int,
                descriptor: BluetoothGattDescriptor,
                preparedWrite: Boolean,
                responseNeeded: Boolean,
                offset: Int,
                value: ByteArray
            ) {

                if (connectedReceiver?.address != device.address) {
                    if (responseNeeded) {
                        sendGattResponse(
                            device, requestId,
                            BluetoothGatt.GATT_REQUEST_NOT_SUPPORTED,
                            offset, null
                        )
                    }
                    return
                }

                if (descriptor.uuid ==
                    BleProtocol.CLIENT_CONFIGURATION_UUID &&
                    descriptor.characteristic.uuid ==
                    BleProtocol.DATA_TRANSFER_UUID
                ) {
                    dataNotificationsEnabled =
                        value.contentEquals(
                            BluetoothGattDescriptor
                                .ENABLE_NOTIFICATION_VALUE
                        )

                    Log.d(
                        "BLE_SERVER",
                        "Notificaciones DATA_TRANSFER: " +
                                dataNotificationsEnabled
                    )
                }

                if (
                    descriptor.uuid == BleProtocol.CLIENT_CONFIGURATION_UUID &&
                    descriptor.characteristic.uuid == BleProtocol.TRANSFER_STATUS_UUID
                ) {
                    reverseAckNotificationsEnabled =
                        connectedReceiver?.address == device.address &&
                                value.contentEquals(
                                    BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                                )

                    Log.d(
                        "BLE_SERVER",
                        "Notificaciones ACK inverso: $reverseAckNotificationsEnabled"
                    )
                }

                descriptor.value = value

                if (responseNeeded) {
                    sendGattResponse(
                        device = device,
                        requestId = requestId,
                        status =
                            BluetoothGatt.GATT_SUCCESS,
                        offset = offset,
                        value = value
                    )
                }
            }

            override fun onMtuChanged(
                device: BluetoothDevice,
                mtu: Int
            ) {
                if (connectedReceiver?.address != device.address) return

                negotiatedMtu = mtu

                Log.d(
                    "BLE_SERVER",
                    "MTU acordado con ${device.address}: $mtu"
                )
            }

            override fun onNotificationSent(
                device: BluetoothDevice,
                status: Int
            ) {
                if (connectedReceiver?.address != device.address) return

                val completedKind = synchronized(notificationLock) {
                    notificationInFlight.also {
                        notificationInFlight = null
                    }
                }

                if (completedKind == NotificationKind.REVERSE_ACK) {
                    if (status == BluetoothGatt.GATT_SUCCESS) {
                        sendNextReverseAckFrame()
                    } else {
                        synchronized(notificationLock) {
                            reverseAckFrames.clear()
                        }
                        Log.e("BLE_SERVER", "Falló una notificación del ACK inverso: $status")
                    }

                    val idle = synchronized(notificationLock) {
                        notificationInFlight == null && reverseAckFrames.isEmpty()
                    }
                    val pendingData = synchronized(outgoingDataLock) {
                        outgoingDataFrames.isNotEmpty()
                    }
                    if (idle && pendingData && pendingOutgoingMessageId != null) {
                        sendNextDataFrame()
                    }
                    return
                }

                if (completedKind != NotificationKind.DATA) return

                if (status != BluetoothGatt.GATT_SUCCESS) {
                    clearOutgoingDataFrames()

                    mutableState.value =
                        mutableState.value.copy(
                            status = BlePeripheralStatus.ERROR,
                            message =
                                "Falló una notificación BLE: $status; " +
                                        "se programó un reintento"
                        )

                    scheduleAckTimeout()
                    sendNextReverseAckFrame()

                    return
                }

                sentDataFrames += 1

                Log.d(
                    "BLE_SERVER",
                    "Fragmento enviado: " +
                            "$sentDataFrames/$totalDataFrames"
                )

                sendNextDataFrame()
            }
        }

    private fun handleReverseDataFrame(
        device: BluetoothDevice,
        requestId: Int,
        packet: ByteArray,
        preparedWrite: Boolean,
        responseNeeded: Boolean,
        offset: Int
    ) {
        fun respond(status: Int) {
            if (responseNeeded) {
                sendGattResponse(device, requestId, status, 0, null)
            }
        }

        val sessionId = activeSessionId
        if (
            preparedWrite || offset != 0 || !responseNeeded ||
            sessionId == null ||
            connectedReceiver?.address != device.address ||
            !sessionKeyRepository.hasSessionKey(sessionId)
        ) {
            reverseDataAssembler.clear()
            respond(BluetoothGatt.GATT_REQUEST_NOT_SUPPORTED)
            return
        }

        val assembled = runCatching {
            reverseDataAssembler.accept(packet)
        }.getOrElse {
            reverseDataAssembler.clear()
            respond(BluetoothGatt.GATT_REQUEST_NOT_SUPPORTED)
            return
        }

        if (assembled == null) {
            respond(BluetoothGatt.GATT_SUCCESS)
            return
        }

        reverseDataAssembler.clear()

        if (assembled.messageType != BleMessageType.ENCRYPTED_DATA) {
            respond(BluetoothGatt.GATT_REQUEST_NOT_SUPPORTED)
            return
        }

        repositoryScope.launch {
            runCatching {
                val envelope = EncryptedTransferEnvelopeCodec.decode(
                    assembled.payload.decodeToString()
                )
                require(envelope.sessionId == sessionId) {
                    "La respuesta pertenece a otra sesión"
                }

                val text = sessionKeyRepository.decryptSessionMessage(
                    envelope,
                    purpose = SessionMessagePurpose.REVERSE_DATA
                )
                envelope.messageId to text
            }.onSuccess { (messageId, text) ->
                if (
                    activeSessionId != sessionId ||
                    connectedReceiver?.address != device.address
                ) {
                    respond(BluetoothGatt.GATT_REQUEST_NOT_SUPPORTED)
                    return@onSuccess
                }

                val key = "$sessionId|$messageId"
                val isNew = synchronized(processedReverseMessageIds) {
                    processedReverseMessageIds.add(key)
                }

                if (
                    isNew &&
                    (mutableReceivedReplies.subscriptionCount.value == 0 ||
                            !mutableReceivedReplies.tryEmit(text))
                ) {
                    synchronized(processedReverseMessageIds) {
                        processedReverseMessageIds.remove(key)
                    }
                    respond(BluetoothGatt.GATT_REQUEST_NOT_SUPPORTED)
                    return@onSuccess
                }

                respond(BluetoothGatt.GATT_SUCCESS)

                queueReverseAck(
                    sessionId = sessionId,
                    messageId = messageId,
                    deviceAddress = device.address,
                    duplicate = !isNew
                )

            }.onFailure {
                respond(BluetoothGatt.GATT_REQUEST_NOT_SUPPORTED)
            }
        }
    }

    private fun handleAckFrame(
        device: BluetoothDevice,
        requestId: Int,
        packet: ByteArray,
        preparedWrite: Boolean,
        responseNeeded: Boolean,
        offset: Int
    ) {
        if (preparedWrite || offset != 0) {
            rejectAck(
                device = device,
                requestId = requestId,
                responseNeeded = responseNeeded,
                message = "Escritura ACK inválida"
            )
            return
        }

        val assembledMessage = runCatching {
            ackAssembler.accept(packet)
        }.getOrElse { error ->
            rejectAck(
                device = device,
                requestId = requestId,
                responseNeeded = responseNeeded,
                message =
                    "Fragmento ACK inválido: ${error.message}"
            )
            return
        }

        if (assembledMessage == null) {
            if (responseNeeded) {
                sendGattResponse(
                    device = device,
                    requestId = requestId,
                    status =
                        BluetoothGatt.GATT_SUCCESS,
                    offset = 0,
                    value = null
                )
            }

            return
        }

        if (
            assembledMessage.messageType !=
            BleMessageType.ACK
        ) {
            rejectAck(
                device = device,
                requestId = requestId,
                responseNeeded = responseNeeded,
                message = "Tipo de ACK inesperado"
            )
            return
        }

        val encryptedEnvelope = runCatching {
            EncryptedTransferEnvelopeCodec.decode(
                assembledMessage.payload
                    .decodeToString()
            )
        }.getOrElse { error ->
            rejectAck(
                device = device,
                requestId = requestId,
                responseNeeded = responseNeeded,
                message =
                    "Sobre ACK inválido: ${error.message}"
            )
            return
        }

        val expectedSessionId = activeSessionId

        if (
            expectedSessionId.isNullOrBlank() ||
            encryptedEnvelope.sessionId !=
            expectedSessionId
        ) {
            rejectAck(
                device = device,
                requestId = requestId,
                responseNeeded = responseNeeded,
                message =
                    "El ACK no pertenece a la sesión"
            )
            return
        }

        repositoryScope.launch {
            runCatching {
                val acknowledgementJson =
                    sessionKeyRepository
                        .decryptSessionMessage(
                            encryptedEnvelope
                        )

                TransferAcknowledgementCodec.decode(
                    acknowledgementJson
                )
            }.onSuccess { acknowledgement ->
                val expectedMessageId =
                    pendingOutgoingMessageId

                val validAck =
                    acknowledgement.protocolVersion == 1 &&
                            acknowledgement.sessionId ==
                            expectedSessionId &&
                            acknowledgement
                                .acknowledgedMessageId ==
                            expectedMessageId

                if (!validAck) {
                    rejectAck(
                        device = device,
                        requestId = requestId,
                        responseNeeded =
                            responseNeeded,
                        message =
                            "El ACK no corresponde al mensaje enviado"
                    )

                    return@onSuccess
                }

                ackAssembler.clear()
                clearPendingTransfer()

                mutableState.value =
                    mutableState.value.copy(
                        status =
                            BlePeripheralStatus.DATA_CONFIRMED,
                        message = if (
                            acknowledgement.duplicate
                        ) {
                            "ACK válido: el receptor ya tenía el mensaje"
                        } else {
                            "ACK válido: transferencia confirmada"
                        }
                    )

                if (responseNeeded) {
                    sendGattResponse(
                        device = device,
                        requestId = requestId,
                        status =
                            BluetoothGatt.GATT_SUCCESS,
                        offset = 0,
                        value = null
                    )
                }
            }.onFailure { error ->
                rejectAck(
                    device = device,
                    requestId = requestId,
                    responseNeeded = responseNeeded,
                    message =
                        "ACK cifrado rechazado: ${error.message}"
                )
            }
        }
    }

    private fun rejectAck(
        device: BluetoothDevice,
        requestId: Int,
        responseNeeded: Boolean,
        message: String
    ) {
        ackAssembler.clear()

        mutableState.value =
            mutableState.value.copy(
                status = BlePeripheralStatus.ERROR,
                message = message
            )

        if (responseNeeded) {
            sendGattResponse(
                device = device,
                requestId = requestId,
                status =
                    BluetoothGatt
                        .GATT_REQUEST_NOT_SUPPORTED,
                offset = 0,
                value = null
            )
        }
    }

    private fun handleHelloRequest(
        device: BluetoothDevice,
        requestId: Int,
        command: String,
        preparedWrite: Boolean,
        responseNeeded: Boolean,
        offset: Int
    ) {
        val receivedSessionId = command
            .removePrefix("HELLO|")
            .takeIf { it.isNotBlank() }

        val validFormat =
            offset == 0 &&
                    !preparedWrite &&
                    receivedSessionId != null

        val matchesAdvertisedSession =
            receivedSessionId != null &&
                    receivedSessionId == activeSessionId

        val sessionStillActive =
            receivedSessionId != null &&
                    sessionInvitationRepository.isActiveSession(
                        receivedSessionId
                    )

        val sessionAccepted =
            validFormat &&
                    matchesAdvertisedSession &&
                    sessionStillActive

        mutableState.value =
            mutableState.value.copy(
                status = if (sessionAccepted) {
                    BlePeripheralStatus.SESSION_VERIFIED
                } else {
                    BlePeripheralStatus.SESSION_REJECTED
                },
                message = when {
                    !validFormat ->
                        "El comando HELLO es inválido"

                    !matchesAdvertisedSession ->
                        "El sessionId no corresponde al QR anunciado"

                    !sessionStillActive ->
                        "La sesión expiró o no existe"

                    else ->
                        "Receptor vinculado a $receivedSessionId"
                }
            )

        if (responseNeeded) {
            sendGattResponse(
                device = device,
                requestId = requestId,
                status = if (sessionAccepted) {
                    BluetoothGatt.GATT_SUCCESS
                } else {
                    BluetoothGatt.GATT_REQUEST_NOT_SUPPORTED
                },
                offset = offset,
                value = null
            )
        }
    }

    private fun handleHandshakeFrame(
        device: BluetoothDevice,
        requestId: Int,
        packet: ByteArray,
        preparedWrite: Boolean,
        responseNeeded: Boolean,
        offset: Int
    ) {
        if (preparedWrite || offset != 0) {
            rejectHandshakeFrame(
                device = device,
                requestId = requestId,
                responseNeeded = responseNeeded,
                message =
                    "Se recibió una escritura BLE preparada"
            )
            return
        }

        val assembledMessage = runCatching {
            messageAssembler.accept(packet)
        }.getOrElse { error ->
            rejectHandshakeFrame(
                device = device,
                requestId = requestId,
                responseNeeded = responseNeeded,
                message =
                    "Fragmento BLE inválido: ${error.message}"
            )
            return
        }

        /*
         * Si todavía faltan fragmentos, aceptamos el actual
         * para que el cliente pueda enviar el siguiente.
         */
        if (assembledMessage == null) {
            if (responseNeeded) {
                sendGattResponse(
                    device = device,
                    requestId = requestId,
                    status = BluetoothGatt.GATT_SUCCESS,
                    offset = 0,
                    value = null
                )
            }
            return
        }

        if (
            assembledMessage.messageType !=
            BleMessageType.RECEIVER_HANDSHAKE
        ) {
            rejectHandshakeFrame(
                device = device,
                requestId = requestId,
                responseNeeded = responseNeeded,
                message =
                    "Tipo de mensaje BLE inesperado"
            )
            return
        }

        val expectedSessionId = activeSessionId

        if (
            expectedSessionId.isNullOrBlank() ||
            !sessionInvitationRepository.isActiveSession(
                expectedSessionId
            )
        ) {
            rejectHandshakeFrame(
                device = device,
                requestId = requestId,
                responseNeeded = responseNeeded,
                message =
                    "La sesión del emisor expiró"
            )
            return
        }

        val handshakeJson = runCatching {
            assembledMessage.payload.decodeToString()
        }.getOrElse {
            rejectHandshakeFrame(
                device = device,
                requestId = requestId,
                responseNeeded = responseNeeded,
                message =
                    "El handshake no está codificado en UTF-8"
            )
            return
        }

        mutableState.value =
            mutableState.value.copy(
                status =
                    BlePeripheralStatus.EXCHANGING_ECDH,
                message =
                    "Verificando identidad del receptor..."
            )

        repositoryScope.launch {
            runCatching {
                val verification =
                    verifyReceiverHandshake(
                        handshakeJson = handshakeJson,
                        expectedSessionId =
                            expectedSessionId
                    )

                require(verification.isValid) {
                    verification.message
                }

                val handshake = requireNotNull(
                    verification.handshake
                ) {
                    "No se recuperó el handshake verificado"
                }

                sessionKeyRepository.completeSenderSession(
                    sessionId = expectedSessionId,
                    receiverEphemeralPublicKey =
                        handshake.ephemeralPublicKey
                )
            }.onSuccess { result ->
                mutableState.value =
                    mutableState.value.copy(
                        status =
                            BlePeripheralStatus.SESSION_KEY_READY,
                        message =
                            "Handshake verificado. " +
                                    "Huella AES emisor: " +
                                    result.sessionKeyFingerprint
                    )

                if (responseNeeded) {
                    sendGattResponse(
                        device = device,
                        requestId = requestId,
                        status =
                            BluetoothGatt.GATT_SUCCESS,
                        offset = 0,
                        value = null
                    )
                }
            }.onFailure { error ->
                Log.e(
                    "BLE_SERVER",
                    "Handshake rechazado",
                    error
                )

                rejectHandshakeFrame(
                    device = device,
                    requestId = requestId,
                    responseNeeded = responseNeeded,
                    message =
                        "Handshake rechazado: ${error.message}"
                )
            }
        }
    }

    private fun rejectHandshakeFrame(
        device: BluetoothDevice,
        requestId: Int,
        responseNeeded: Boolean,
        message: String
    ) {
        messageAssembler.clear()

        mutableState.value =
            mutableState.value.copy(
                status =
                    BlePeripheralStatus.SESSION_REJECTED,
                message = message
            )

        if (responseNeeded) {
            sendGattResponse(
                device = device,
                requestId = requestId,
                status =
                    BluetoothGatt.GATT_REQUEST_NOT_SUPPORTED,
                offset = 0,
                value = null
            )
        }
    }

    @SuppressLint("MissingPermission")
    override fun startAdvertising(
        sessionId: String
    ) {
        stopInternal(updateState = false)

        activeSessionId = sessionId

        mutableState.value = BlePeripheralState(
            status = BlePeripheralStatus.STARTING,
            message = "Creando servidor GATT...",
            sessionId = sessionId
        )

        runCatching {
            val adapter = requireNotNull(
                bluetoothManager.adapter
            ) {
                "Bluetooth no está disponible"
            }

            require(adapter.isEnabled) {
                "Bluetooth está apagado"
            }

            advertiser = requireNotNull(
                adapter.bluetoothLeAdvertiser
            ) {
                "El dispositivo no puede anunciar BLE"
            }

            gattServer = requireNotNull(
                bluetoothManager.openGattServer(
                    context,
                    gattServerCallback
                )
            ) {
                "No fue posible abrir el servidor GATT"
            }

            val service = createGattService()

            check(gattServer?.addService(service) == true) {
                "Android rechazó el servicio GATT"
            }
        }.onFailure { error ->
            stopInternal(updateState = false)

            mutableState .value = BlePeripheralState(
                status = BlePeripheralStatus.ERROR,
                message =
                    error.message ?: "Error iniciando BLE",
                sessionId = sessionId
            )
        }
    }

    @SuppressLint("MissingPermission")
    private fun startBleAdvertising() {
        val settings = AdvertiseSettings.Builder()
            .setAdvertiseMode(
                AdvertiseSettings.ADVERTISE_MODE_LOW_LATENCY
            )
            .setTxPowerLevel(
                AdvertiseSettings.ADVERTISE_TX_POWER_MEDIUM
            )
            .setConnectable(true)
            .setTimeout(0)
            .build()

        val advertiseData = AdvertiseData.Builder()
            .setIncludeDeviceName(false)
            .addServiceUuid(ParcelUuid(BleProtocol.SERVICE_UUID))
            .build()

        advertiser?.startAdvertising(
            settings,
            advertiseData,
            advertiseCallback
        )
    }

    @Suppress("DEPRECATION")
    private fun createGattService(): BluetoothGattService {

        val service = BluetoothGattService(
            BleProtocol.SERVICE_UUID,
            BluetoothGattService.SERVICE_TYPE_PRIMARY
        )

        val sessionControl =
            BluetoothGattCharacteristic(
                BleProtocol.SESSION_CONTROL_UUID,
                BluetoothGattCharacteristic.PROPERTY_WRITE or
                        BluetoothGattCharacteristic.PROPERTY_NOTIFY,
                BluetoothGattCharacteristic.PERMISSION_WRITE
            ).apply {
                addDescriptor(createNotificationDescriptor())
            }

        val dataTransfer =
            BluetoothGattCharacteristic(
                BleProtocol.DATA_TRANSFER_UUID,
                BluetoothGattCharacteristic.PROPERTY_READ or
                        BluetoothGattCharacteristic.PROPERTY_NOTIFY,
                BluetoothGattCharacteristic.PERMISSION_READ
            ).apply {
                addDescriptor(createNotificationDescriptor())
            }

        val transferStatus =
            BluetoothGattCharacteristic(
                BleProtocol.TRANSFER_STATUS_UUID,
                BluetoothGattCharacteristic.PROPERTY_WRITE or
                        BluetoothGattCharacteristic.PROPERTY_NOTIFY,
                BluetoothGattCharacteristic.PERMISSION_WRITE
            ).apply {
                addDescriptor(createNotificationDescriptor())
            }

        val reverseData = BluetoothGattCharacteristic(
            BleProtocol.REVERSE_DATA_UUID,
            BluetoothGattCharacteristic.PROPERTY_WRITE,
            BluetoothGattCharacteristic.PERMISSION_WRITE
        )

        service.addCharacteristic(sessionControl)
        service.addCharacteristic(dataTransfer)
        service.addCharacteristic(transferStatus)
        service.addCharacteristic(reverseData)

        return service
    }

    @Suppress("DEPRECATION")
    private fun createNotificationDescriptor(): BluetoothGattDescriptor {

        return BluetoothGattDescriptor(
            BleProtocol.CLIENT_CONFIGURATION_UUID,
            BluetoothGattDescriptor.PERMISSION_READ or
                    BluetoothGattDescriptor.PERMISSION_WRITE
        )
    }

    @SuppressLint("MissingPermission")
    private fun sendGattResponse(
        device: BluetoothDevice,
        requestId: Int,
        status: Int,
        offset: Int,
        value: ByteArray?
    ) {
        runCatching {
            gattServer?.sendResponse(
                device,
                requestId,
                status,
                offset,
                value
            )
        }
    }

    override fun sendEncryptedData(
        plainText: String
    ) {
        if (plainText.isBlank()) {
            updateDataSendingError(
                "El JSON que se desea enviar está vacío"
            )
            return
        }

        if (pendingOutgoingMessageId != null) {
            updateDataSendingError(
                "Ya existe una transferencia esperando ACK"
            )
            return
        }

        val sessionId = activeSessionId

        if (sessionId.isNullOrBlank()) {
            updateDataSendingError(
                "No existe una sesión activa"
            )
            return
        }

        if (
            !sessionKeyRepository.hasSessionKey(
                sessionId
            )
        ) {
            updateDataSendingError(
                "La clave AES todavía no está preparada"
            )
            return
        }

        if (connectedReceiver == null) {
            updateDataSendingError(
                "No existe un receptor conectado"
            )
            return
        }

        if (!dataNotificationsEnabled) {
            updateDataSendingError(
                "El receptor no habilitó DATA_TRANSFER"
            )
            return
        }

        repositoryScope.launch {
            runCatching {
                val messageId =
                    UUID.randomUUID().toString()

                val encryptedEnvelope =
                    sessionKeyRepository
                        .encryptSessionMessage(
                            sessionId = sessionId,
                            messageId = messageId,
                            plainText = plainText
                        )

                val envelopeJson =
                    EncryptedTransferEnvelopeCodec
                        .encode(encryptedEnvelope)

                val frames = frameCodec.fragment(
                    messageType =
                        BleMessageType.ENCRYPTED_DATA,
                    payload =
                        envelopeJson.encodeToByteArray(),
                    negotiatedMtu = negotiatedMtu
                )

                require(frames.isNotEmpty()) {
                    "No se generaron fragmentos BLE"
                }

                Pair(messageId, frames)
            }.onSuccess { result ->
                val messageId = result.first
                val frames = result.second

                pendingOutgoingMessageId = messageId

                lastTransferFrames =
                    frames.map { frame ->
                        frame.copyOf()
                    }

                ackRetryAttempt = 0

                synchronized(outgoingDataLock) {
                    outgoingDataFrames.clear()
                    outgoingDataFrames.addAll(frames)

                    totalDataFrames = frames.size
                    sentDataFrames = 0
                }

                mutableState.value =
                    mutableState.value.copy(
                        status =
                            BlePeripheralStatus.DATA_SENDING,
                        message =
                            "Enviando JSON cifrado en " +
                                    "${frames.size} fragmentos..."
                    )

                sendNextDataFrame()
            }.onFailure { error ->
                clearOutgoingDataFrames()

                updateDataSendingError(
                    "No fue posible preparar el envío: " +
                            error.message
                )
            }
        }
    }

    @SuppressLint("MissingPermission")
    override fun stopAdvertising() {
        stopInternal(updateState = true)
    }

    @SuppressLint("MissingPermission")
    private fun stopInternal(
        updateState: Boolean
    ) {
        val sessionIdToClear = activeSessionId

        runCatching {
            advertiser?.stopAdvertising(
                advertiseCallback
            )
        }

        runCatching {
            gattServer?.clearServices()
            gattServer?.close()
        }

        messageAssembler.clear()

        ackAssembler.clear()

        reverseDataAssembler.clear()

        synchronized(processedReverseMessageIds) {
            processedReverseMessageIds.clear()
        }

        synchronized(notificationLock) {
            notificationInFlight = null
            reverseAckFrames.clear()
        }

        clearPendingTransfer()

        connectedReceiver = null
        dataNotificationsEnabled = false
        reverseAckNotificationsEnabled = false
        negotiatedMtu = DEFAULT_MTU

        advertiser = null
        gattServer = null

        sessionIdToClear?.let { sessionId ->
            sessionKeyRepository.clearSession(
                sessionId
            )

            sessionInvitationRepository.clearSession(
                sessionId
            )
        }

        activeSessionId = null

        if (updateState) {
            mutableState .value = BlePeripheralState(
                status = BlePeripheralStatus.IDLE,
                message = "Peripheral detenido"
            )
        }
    }

    @SuppressLint("MissingPermission")
    private fun sendNextDataFrame() {
        val device = connectedReceiver

        if (device == null) {
            clearOutgoingDataFrames()

            updateDataSendingError(
                "El receptor se desconectó"
            )
            return
        }

        val server = gattServer

        if (server == null) {
            clearOutgoingDataFrames()

            updateDataSendingError(
                "El servidor GATT no está disponible"
            )
            return
        }

        val characteristic = server
            .getService(BleProtocol.SERVICE_UUID)
            ?.getCharacteristic(
                BleProtocol.DATA_TRANSFER_UUID
            )

        if (characteristic == null) {
            clearOutgoingDataFrames()

            updateDataSendingError(
                "No se encontró DATA_TRANSFER"
            )
            return
        }

        val nextFrame = synchronized(
            outgoingDataLock
        ) {
            if (outgoingDataFrames.isEmpty()) {
                null
            } else {
                outgoingDataFrames.removeFirst()
            }
        }

        if (nextFrame == null) {
            mutableState.value =
                mutableState.value.copy(
                    status =
                        BlePeripheralStatus.WAITING_ACK,
                    message =
                        "JSON enviado en " +
                                "$totalDataFrames fragmentos; " +
                                "esperando ACK"
                )

            scheduleAckTimeout()
            return
        }

        val canNotify = synchronized(notificationLock) {
            if (notificationInFlight != null) {
                false
            } else {
                notificationInFlight = NotificationKind.DATA
                true
            }
        }

        if (!canNotify) {
            synchronized(outgoingDataLock) {
                outgoingDataFrames.addFirst(nextFrame)
            }
            return
        }

        val notificationStarted =
            if (
                Build.VERSION.SDK_INT >=
                Build.VERSION_CODES.TIRAMISU
            ) {
                server.notifyCharacteristicChanged(
                    device,
                    characteristic,
                    false,
                    nextFrame
                ) == BluetoothStatusCodes.SUCCESS
            } else {
                @Suppress("DEPRECATION")
                characteristic.value = nextFrame

                @Suppress("DEPRECATION")
                server.notifyCharacteristicChanged(
                    device,
                    characteristic,
                    false
                )
            }

        if (!notificationStarted) {

            synchronized(notificationLock) {
                notificationInFlight = null
            }

            clearOutgoingDataFrames()

            mutableState.value =
                mutableState.value.copy(
                    status = BlePeripheralStatus.ERROR,
                    message =
                        "Android rechazó la notificación; " +
                                "se programó un reintento"
                )

            scheduleAckTimeout()
            sendNextReverseAckFrame()
        }
    }

    @SuppressLint("MissingPermission")
    private fun sendNextReverseAckFrame() {
        val device = connectedReceiver
        val server = gattServer
        val characteristic = server
            ?.getService(BleProtocol.SERVICE_UUID)
            ?.getCharacteristic(BleProtocol.TRANSFER_STATUS_UUID)

        if (
            device == null || server == null || characteristic == null ||
            !reverseAckNotificationsEnabled
        ) {
            synchronized(notificationLock) {
                reverseAckFrames.clear()
            }
            return
        }

        val frame = synchronized(notificationLock) {
            if (notificationInFlight != null || reverseAckFrames.isEmpty()) {
                null
            } else {
                notificationInFlight = NotificationKind.REVERSE_ACK
                reverseAckFrames.removeFirst()
            }
        } ?: return

        val started = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            server.notifyCharacteristicChanged(
                device, characteristic, false, frame
            ) == BluetoothStatusCodes.SUCCESS
        } else {
            @Suppress("DEPRECATION")
            characteristic.value = frame
            @Suppress("DEPRECATION")
            server.notifyCharacteristicChanged(device, characteristic, false)
        }

        if (!started) {
            synchronized(notificationLock) {
                notificationInFlight = null
                reverseAckFrames.clear()
            }
            Log.e("BLE_SERVER", "No se pudo iniciar la notificación del ACK inverso")
        }
    }

    private fun queueReverseAck(
        sessionId: String,
        messageId: String,
        deviceAddress: String,
        duplicate: Boolean
    ) {
        repositoryScope.launch {
            try {
                val acknowledgement = TransferAcknowledgement(
                    protocolVersion = 1,
                    sessionId = sessionId,
                    acknowledgedMessageId = messageId,
                    duplicate = duplicate
                )

                val envelope = sessionKeyRepository.encryptSessionMessage(
                    sessionId = sessionId,
                    messageId = UUID.randomUUID().toString(),
                    plainText = TransferAcknowledgementCodec.encode(acknowledgement),
                    purpose = SessionMessagePurpose.REVERSE_ACK
                )

                val frames = frameCodec.fragment(
                    messageType = BleMessageType.ACK,
                    payload = EncryptedTransferEnvelopeCodec
                        .encode(envelope)
                        .encodeToByteArray(),
                    negotiatedMtu = negotiatedMtu
                )

                if (
                    activeSessionId != sessionId ||
                    connectedReceiver?.address != deviceAddress ||
                    !reverseAckNotificationsEnabled
                ) {
                    return@launch
                }

                synchronized(notificationLock) {
                    reverseAckFrames.addAll(frames)
                }

                withContext(Dispatchers.Main) {
                    sendNextReverseAckFrame()
                }
            } catch (error: Exception) {
                Log.e("BLE_SERVER", "No se pudo preparar el ACK inverso", error)
            }
        }
    }

    private fun scheduleAckTimeout() {
        ackTimeoutJob?.cancel()

        ackTimeoutJob = repositoryScope.launch {
            delay(ACK_TIMEOUT_MILLIS)

            if (pendingOutgoingMessageId == null) {
                return@launch
            }

            if (
                ackRetryAttempt >=
                MAX_ACK_RETRIES
            ) {
                mutableState.value =
                    mutableState.value.copy(
                        status =
                            BlePeripheralStatus.ERROR,
                        message =
                            "No se recibió ACK después de " +
                                    "$MAX_ACK_RETRIES reintentos"
                    )

                return@launch
            }

            retryLastTransfer()
        }
    }

    private fun retryLastTransfer() {
        val frames = lastTransferFrames.map {
            it.copyOf()
        }

        if (frames.isEmpty()) {
            updateDataSendingError(
                "No existen fragmentos para reintentar"
            )
            return
        }

        if (connectedReceiver == null) {
            updateDataSendingError(
                "El receptor se desconectó antes del reintento"
            )
            return
        }

        ackRetryAttempt += 1

        synchronized(outgoingDataLock) {
            outgoingDataFrames.clear()
            outgoingDataFrames.addAll(frames)

            totalDataFrames = frames.size
            sentDataFrames = 0
        }

        mutableState.value =
            mutableState.value.copy(
                status =
                    BlePeripheralStatus.RETRYING,
                message =
                    "ACK no recibido. Reintento " +
                            "$ackRetryAttempt/" +
                            "$MAX_ACK_RETRIES"
            )

        sendNextDataFrame()
    }

    private fun clearPendingTransfer() {
        ackTimeoutJob?.cancel()
        ackTimeoutJob = null

        pendingOutgoingMessageId = null
        lastTransferFrames = emptyList()
        ackRetryAttempt = 0

        clearOutgoingDataFrames()
    }

    private fun clearOutgoingDataFrames() {
        synchronized(outgoingDataLock) {
            outgoingDataFrames.clear()
            totalDataFrames = 0
            sentDataFrames = 0
        }
    }

    private fun updateDataSendingError(
        message: String
    ) {
        mutableState.value =
            mutableState.value.copy(
                status = BlePeripheralStatus.ERROR,
                message = message
            )
    }

}