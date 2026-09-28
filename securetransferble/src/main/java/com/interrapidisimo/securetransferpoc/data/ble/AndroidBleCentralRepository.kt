package com.interrapidisimo.securetransferpoc.data.ble

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.BluetoothStatusCodes
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.ParcelUuid
import android.util.Log
import androidx.core.content.ContextCompat
import com.interrapidisimo.securetransferpoc.domain.model.BleCentralState
import com.interrapidisimo.securetransferpoc.domain.model.BleCentralStatus
import com.interrapidisimo.securetransferpoc.domain.repository.BleCentralRepository
import com.interrapidisimo.securetransferpoc.transport.ble.BleProtocol
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.nio.charset.StandardCharsets
import javax.inject.Inject
import javax.inject.Singleton

import com.interrapidisimo.securetransferpoc.transport.ble.BleFrameCodec
import com.interrapidisimo.securetransferpoc.transport.ble.BleMessageType
import java.util.ArrayDeque
import android.bluetooth.BluetoothGattDescriptor

import com.interrapidisimo.securetransferpoc.domain.repository.SessionKeyRepository
import com.interrapidisimo.securetransferpoc.transport.ble.BleMessageAssembler
import com.interrapidisimo.securetransferpoc.transport.ble.EncryptedTransferEnvelopeCodec
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import com.interrapidisimo.securetransferpoc.domain.model.TransferAcknowledgement
import com.interrapidisimo.securetransferpoc.transport.ble.TransferAcknowledgementCodec
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

import com.interrapidisimo.securetransferpoc.domain.repository.SessionMessagePurpose
import kotlinx.coroutines.CompletableDeferred

import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout

import kotlinx.coroutines.TimeoutCancellationException

@Singleton
class AndroidBleCentralRepository @Inject constructor(
    @ApplicationContext private val context: Context,
    private val sessionKeyRepository: SessionKeyRepository
) : BleCentralRepository {

    private companion object {
        const val SCAN_TIMEOUT_MILLIS = 15_000L
        const val REQUESTED_MTU = 185
        const val MINIMUM_SESSION_MTU = 64
    }

    private val bluetoothManager =
        context.getSystemService(BluetoothManager::class.java)

    private val bluetoothAdapter
        get() = bluetoothManager?.adapter

    private val _state = MutableStateFlow(BleCentralState())
    override val state: StateFlow<BleCentralState> = _state.asStateFlow()

    private val handler = Handler(Looper.getMainLooper())

    private var scanCallback: ScanCallback? = null
    private var bluetoothGatt: BluetoothGatt? = null
    @Volatile
    private var reverseDataCharacteristic: BluetoothGattCharacteristic? = null

    override val supportsReplies: Boolean
        get() = reverseDataCharacteristic != null
    private var scanTimeout: Runnable? = null

    private enum class ControlWriteOperation {
        NONE,
        CONFIRM_SESSION,
        SEND_RECEIVER_HANDSHAKE,
        SEND_ACK
    }

    @Volatile
    private var pendingControlWrite =
        ControlWriteOperation.NONE

    @Volatile
    private var pendingSessionKeyFingerprint: String? = null

    private var negotiatedMtu: Int = 23

    private val frameCodec = BleFrameCodec()

    private val incomingDataAssembler =
        BleMessageAssembler()

    private val reverseAckAssembler = BleMessageAssembler()
    private val reverseAckLock = Any()
    private var pendingReverseMessageId: String? = null
    private var pendingReverseAck: CompletableDeferred<Unit>? = null

    private val repositoryScope = CoroutineScope(
        SupervisorJob() + Dispatchers.Default
    )

    @Volatile
    private var pendingSessionId: String? = null

    @Volatile
    private var verifiedSessionId: String? = null

    @Volatile
    private var receiverHandshakeCompleted = false

    private var pendingReverseWrite: CompletableDeferred<Unit>? = null

    private val handshakeFrames =
        ArrayDeque<ByteArray>()

    private val handshakeFramesLock = Any()

    private var totalHandshakeFrames = 0
    private var sentHandshakeFrames = 0

    private val ackFrames =
        ArrayDeque<ByteArray>()

    private val ackFramesLock = Any()

    private var totalAckFrames = 0
    private var sentAckFrames = 0

    private val processedMessageIds =
        ConcurrentHashMap.newKeySet<String>()

    @SuppressLint("MissingPermission")
    override fun scanAndConnect() {
        if (!hasRequiredPermissions()) {
            updateError("Faltan permisos Bluetooth")
            return
        }

        val adapter = bluetoothAdapter

        if (adapter == null) {
            updateError("Este dispositivo no soporta Bluetooth")
            return
        }

        if (!adapter.isEnabled) {
            updateError("Bluetooth está apagado")
            return
        }

        val scanner = adapter.bluetoothLeScanner

        if (scanner == null) {
            updateError("No fue posible iniciar el escáner BLE")
            return
        }

        stopScan()
        closeCurrentGatt()
        receiverHandshakeCompleted = false

        _state.value = BleCentralState(
            status = BleCentralStatus.SCANNING,
            message = "Buscando emisor BLE..."
        )

        val filter = ScanFilter.Builder()
            .setServiceUuid(ParcelUuid(BleProtocol.SERVICE_UUID))
            .build()

        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .build()

        val callback = object : ScanCallback() {

            override fun onScanResult(
                callbackType: Int,
                result: ScanResult
            ) {
                connectToDevice(result.device)
            }

            override fun onScanFailed(errorCode: Int) {
                stopScan()

                updateError(
                    message = "Error durante el escaneo BLE: $errorCode"
                )
            }
        }

        scanCallback = callback

        scanner.startScan(
            listOf(filter),
            settings,
            callback
        )

        scanTimeout = Runnable {
            if (_state.value.status == BleCentralStatus.SCANNING) {
                stopScan()

                updateError(
                    message = "No se encontró el emisor en 15 segundos"
                )
            }
        }.also {
            handler.postDelayed(it, SCAN_TIMEOUT_MILLIS)
        }
    }

    @SuppressLint("MissingPermission")
    private fun connectToDevice(device: BluetoothDevice) {
        if (_state.value.status != BleCentralStatus.SCANNING) {
            return
        }

        stopScan()

        _state.value = BleCentralState(
            status = BleCentralStatus.CONNECTING,
            message = "Emisor encontrado. Conectando...",
            deviceAddress = device.address
        )

        bluetoothGatt = device.connectGatt(
            context,
            false,
            gattCallback,
            BluetoothDevice.TRANSPORT_LE
        )
    }

    private val gattCallback = object : BluetoothGattCallback() {

        @SuppressLint("MissingPermission")
        override fun onConnectionStateChange(
            gatt: BluetoothGatt,
            status: Int,
            newState: Int
        ) {
            if (status != BluetoothGatt.GATT_SUCCESS) {
                updateError("Falló la conexión GATT: $status")
                closeGatt(gatt)
                return
            }

            when (newState) {
                BluetoothProfile.STATE_CONNECTED -> {
                    _state.value = BleCentralState(
                        status = BleCentralStatus.DISCOVERING_SERVICES,
                        message = "Conectado. Descubriendo servicios...",
                        deviceAddress = gatt.device.address
                    )

                    val discoveryStarted = gatt.discoverServices()

                    if (!discoveryStarted) {
                        updateError(
                            "No fue posible iniciar el descubrimiento de servicios"
                        )
                        closeGatt(gatt)
                    }
                }

                BluetoothProfile.STATE_DISCONNECTED -> {

                    incomingDataAssembler.clear()

                    clearCurrentSession()

                    closeGatt(gatt)

                    _state.value = BleCentralState(
                        status = BleCentralStatus.DISCONNECTED,
                        message = "Dispositivo desconectado"
                    )
                }
            }
        }

        @SuppressLint("MissingPermission")
        override fun onServicesDiscovered(
            gatt: BluetoothGatt,
            status: Int
        ) {
            if (status != BluetoothGatt.GATT_SUCCESS) {
                updateError("Falló el descubrimiento de servicios: $status")
                closeGatt(gatt)
                return
            }

            val service = gatt.getService(BleProtocol.SERVICE_UUID)

            if (service == null) {
                updateError("El dispositivo no contiene IR_TRANSFER")
                closeGatt(gatt)
                return
            }

            val hasSessionControl = service.getCharacteristic(
                    BleProtocol.SESSION_CONTROL_UUID
                ) != null

            val hasDataTransfer = service.getCharacteristic(
                    BleProtocol.DATA_TRANSFER_UUID
                ) != null

            val hasTransferStatus = service.getCharacteristic(
                    BleProtocol.TRANSFER_STATUS_UUID
                ) != null

            if (
                !hasSessionControl ||
                !hasDataTransfer ||
                !hasTransferStatus
            ) {
                updateError(
                    "El servicio BLE está incompleto"
                )
                closeGatt(gatt)
                return
            }

            reverseDataCharacteristic = service.getCharacteristic(
                BleProtocol.REVERSE_DATA_UUID
            )

            _state.value = BleCentralState(
                status = BleCentralStatus.NEGOTIATING_MTU,
                message = "Servicio encontrado. Negociando MTU...",
                deviceAddress = gatt.device.address
            )

            val mtuRequestStarted = gatt.requestMtu(REQUESTED_MTU)

            if (!mtuRequestStarted) {
                updateError("No fue posible iniciar la negociación del MTU")
            }
        }

        override fun onCharacteristicWrite(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            status: Int
        ) {

            if (bluetoothGatt !== gatt) return

            if (characteristic.uuid == BleProtocol.REVERSE_DATA_UUID) {
                val pending = synchronized(reverseAckLock) {
                    pendingReverseWrite.also {
                        pendingReverseWrite = null
                    }
                }

                if (status == BluetoothGatt.GATT_SUCCESS) {
                    pending?.complete(Unit)
                } else {
                    pending?.completeExceptionally(
                        IllegalStateException(
                            "Flutter rechazó un fragmento de respuesta: $status"
                        )
                    )
                }
                return
            }

            val isSessionControl =
                characteristic.uuid ==
                        BleProtocol.SESSION_CONTROL_UUID

            val isTransferStatus =
                characteristic.uuid ==
                        BleProtocol.TRANSFER_STATUS_UUID

            if (!isSessionControl && !isTransferStatus) {
                return
            }

            val operation = pendingControlWrite

            Log.d(
                "BLE_CLIENT",
                "Escritura terminada: " +
                        "operation=$operation, status=$status"
            )

            if (status != BluetoothGatt.GATT_SUCCESS) {
                when (operation) {
                    ControlWriteOperation
                        .SEND_RECEIVER_HANDSHAKE -> {
                        clearPendingHandshake()
                    }

                    ControlWriteOperation.SEND_ACK -> {
                        clearPendingAck()
                    }

                    else -> {
                        pendingControlWrite =
                            ControlWriteOperation.NONE
                    }
                }

                if (
                    operation ==
                    ControlWriteOperation.CONFIRM_SESSION
                ) {
                    pendingSessionId = null
                    verifiedSessionId = null
                }

                _state.value =
                    _state.value.copy(
                        status = if (
                            operation ==
                            ControlWriteOperation.SEND_ACK
                        ) {
                            BleCentralStatus.ERROR
                        } else {
                            BleCentralStatus.SESSION_REJECTED
                        },
                        message =
                            "El emisor rechazó $operation: $status",
                        deviceAddress =
                            gatt.device.address
                    )

                return
            }

            when (operation) {
                ControlWriteOperation.CONFIRM_SESSION -> {
                    pendingControlWrite =
                        ControlWriteOperation.NONE

                    verifiedSessionId = pendingSessionId
                    pendingSessionId = null

                    _state.value = BleCentralState(
                        status =
                            BleCentralStatus.SESSION_VERIFIED,
                        message =
                            "El emisor confirmó la sesión del QR",
                        deviceAddress = gatt.device.address
                    )
                }

                ControlWriteOperation
                    .SEND_RECEIVER_HANDSHAKE -> {

                    val hasMoreFrames =
                        synchronized(handshakeFramesLock) {
                            handshakeFrames.isNotEmpty()
                        }

                    if (hasMoreFrames) {
                        val sessionControl =
                            gatt.getService(
                                BleProtocol.SERVICE_UUID
                            )?.getCharacteristic(
                                BleProtocol.SESSION_CONTROL_UUID
                            )

                        if (sessionControl == null) {
                            clearPendingHandshake()

                            updateError(
                                "Se perdió SESSION_CONTROL"
                            )
                            return
                        }

                        writeNextHandshakeFrame(
                            gatt = gatt,
                            characteristic = sessionControl
                        )

                        return
                    }

                    val fingerprint =
                        pendingSessionKeyFingerprint

                    clearPendingHandshake()

                    receiverHandshakeCompleted = true

                    _state.value = BleCentralState(
                        status =
                            BleCentralStatus.SESSION_KEY_READY,
                        message =
                            "Handshake aceptado. " +
                                    "Huella AES receptor: " +
                                    fingerprint,
                        deviceAddress = gatt.device.address
                    )
                }

                ControlWriteOperation.SEND_ACK -> {
                    val hasMoreFrames =
                        synchronized(ackFramesLock) {
                            ackFrames.isNotEmpty()
                        }

                    if (hasMoreFrames) {
                        val transferStatus =
                            gatt.getService(
                                BleProtocol.SERVICE_UUID
                            )?.getCharacteristic(
                                BleProtocol.TRANSFER_STATUS_UUID
                            )

                        if (transferStatus == null) {
                            clearPendingAck()

                            updateError(
                                "Se perdió TRANSFER_STATUS"
                            )
                            return
                        }

                        writeNextAckFrame(
                            gatt = gatt,
                            characteristic = transferStatus
                        )

                        return
                    }

                    val receivedMessageId =
                        _state.value.receivedMessageId

                    clearPendingAck()

                    _state.value =
                        _state.value.copy(
                            status = BleCentralStatus.ACK_SENT,
                            message =
                                "ACK cifrado enviado para " +
                                        receivedMessageId,
                            deviceAddress =
                                gatt.device.address
                        )
                }

                ControlWriteOperation.NONE -> {
                    Log.w(
                        "BLE_CLIENT",
                        "Respuesta GATT sin operación pendiente"
                    )
                }
            }
        }

        @SuppressLint("MissingPermission")
        override fun onMtuChanged(
            gatt: BluetoothGatt,
            mtu: Int,
            status: Int
        ) {
            Log.d(
                "BLE_CLIENT",
                "MTU negociado: mtu=$mtu, status=$status"
            )

            negotiatedMtu = mtu

            if (
                status == BluetoothGatt.GATT_SUCCESS &&
                mtu >= MINIMUM_SESSION_MTU
            ) {
                enableDataTransferNotifications(gatt)
            } else {
                updateError(
                    "MTU insuficiente. Recibido: $mtu, status: $status"
                )
            }
        }

        @SuppressLint("MissingPermission")
        override fun onDescriptorWrite(
            gatt: BluetoothGatt,
            descriptor: BluetoothGattDescriptor,
            status: Int
        ) {
            if (
                bluetoothGatt !== gatt ||
                descriptor.uuid != BleProtocol.CLIENT_CONFIGURATION_UUID
            ) return

            when (descriptor.characteristic.uuid) {
                BleProtocol.DATA_TRANSFER_UUID -> {
                    if (status != BluetoothGatt.GATT_SUCCESS) {
                        updateError(
                            "No fue posible habilitar DATA_TRANSFER: $status"
                        )
                        return
                    }

                    if (reverseDataCharacteristic == null) {
                        markServiceReady(gatt)
                    } else {
                        handler.post {
                            enableReverseAckNotifications(gatt)
                        }
                    }
                }

                BleProtocol.TRANSFER_STATUS_UUID -> {
                    if (status != BluetoothGatt.GATT_SUCCESS) {
                        gatt.setCharacteristicNotification(
                            descriptor.characteristic,
                            false
                        )
                        reverseDataCharacteristic = null
                        Log.w(
                            "BLE_CLIENT",
                            "ACK inverso no disponible: $status"
                        )
                    }

                    markServiceReady(gatt)
                }
            }
        }

        override fun onCharacteristicChanged(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            value: ByteArray
        ) {
            routeNotification(
                gatt = gatt,
                characteristic = characteristic,
                value = value
            )
        }

        @Suppress("DEPRECATION")
        override fun onCharacteristicChanged(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic
        ) {
            val value = characteristic.value ?: return

            routeNotification(
                gatt = gatt,
                characteristic = characteristic,
                value = value
            )
        }

        @SuppressLint("MissingPermission")
        private fun enableDataTransferNotifications(
            gatt: BluetoothGatt
        ) {
            val characteristic = gatt
                .getService(BleProtocol.SERVICE_UUID)
                ?.getCharacteristic(
                    BleProtocol.DATA_TRANSFER_UUID
                )

            if (characteristic == null) {
                updateError(
                    "No se encontró DATA_TRANSFER"
                )
                return
            }

            val descriptor = characteristic
                .getDescriptor(
                    BleProtocol.CLIENT_CONFIGURATION_UUID
                )

            if (descriptor == null) {
                updateError(
                    "DATA_TRANSFER no contiene CCCD"
                )
                return
            }

            val localNotificationEnabled =
                gatt.setCharacteristicNotification(
                    characteristic,
                    true
                )

            if (!localNotificationEnabled) {
                updateError(
                    "No fue posible habilitar notificaciones localmente"
                )
                return
            }

            _state.value = BleCentralState(
                status =
                    BleCentralStatus.ENABLING_NOTIFICATIONS,
                message =
                    "Habilitando recepción de datos...",
                deviceAddress = gatt.device.address
            )

            val descriptorWriteStarted =
                if (
                    Build.VERSION.SDK_INT >=
                    Build.VERSION_CODES.TIRAMISU
                ) {
                    gatt.writeDescriptor(
                        descriptor,
                        BluetoothGattDescriptor
                            .ENABLE_NOTIFICATION_VALUE
                    ) == BluetoothStatusCodes.SUCCESS
                } else {
                    @Suppress("DEPRECATION")
                    descriptor.value =
                        BluetoothGattDescriptor
                            .ENABLE_NOTIFICATION_VALUE

                    @Suppress("DEPRECATION")
                    gatt.writeDescriptor(descriptor)
                }

            if (!descriptorWriteStarted) {
                updateError(
                    "No fue posible configurar el descriptor CCCD"
                )
            }
        }
    }

    private fun routeNotification(
        gatt: BluetoothGatt,
        characteristic: BluetoothGattCharacteristic,
        value: ByteArray
    ) {
        if (bluetoothGatt !== gatt) return

        if (characteristic.uuid == BleProtocol.TRANSFER_STATUS_UUID) {
            handleReverseAckNotification(value)
        } else {
            handleDataTransferNotification(gatt, characteristic, value)
        }
    }

    private fun handleReverseAckNotification(value: ByteArray) {
        val assembled = runCatching {
            reverseAckAssembler.accept(value)
        }.getOrElse { error ->
            reverseAckAssembler.clear()
            Log.e("BLE_CLIENT", "Fragmento de ACK inverso inválido", error)
            return
        }

        if (assembled == null) return
        reverseAckAssembler.clear()

        if (assembled.messageType != BleMessageType.ACK) {
            Log.e("BLE_CLIENT", "El ACK inverso tiene otro tipo de mensaje")
            return
        }

        repositoryScope.launch {
            try {
                val envelope = EncryptedTransferEnvelopeCodec.decode(
                    assembled.payload.decodeToString()
                )
                val sessionId = verifiedSessionId
                require(sessionId != null && envelope.sessionId == sessionId) {
                    "El ACK inverso pertenece a otra sesión"
                }

                val json = sessionKeyRepository.decryptSessionMessage(
                    envelope,
                    purpose = SessionMessagePurpose.REVERSE_ACK
                )
                val ack = TransferAcknowledgementCodec.decode(json)

                require(ack.protocolVersion == 1 && ack.sessionId == sessionId) {
                    "El ACK inverso es inválido"
                }

                val pending = synchronized(reverseAckLock) {
                    if (pendingReverseMessageId == ack.acknowledgedMessageId) {
                        pendingReverseAck
                    } else {
                        null
                    }
                }

                pending?.complete(Unit)
            } catch (error: Exception) {
                Log.e("BLE_CLIENT", "No se pudo validar el ACK inverso", error)
            }
        }
    }

    private fun handleDataTransferNotification(
        gatt: BluetoothGatt,
        characteristic: BluetoothGattCharacteristic,
        value: ByteArray
    ) {
        if (
            characteristic.uuid !=
            BleProtocol.DATA_TRANSFER_UUID
        ) {
            return
        }

        val assembledMessage = runCatching {
            incomingDataAssembler.accept(value)
        }.getOrElse { error ->
            incomingDataAssembler.clear()

            updateError(
                "Fragmento recibido inválido: ${error.message}"
            )
            return
        }

        if (assembledMessage == null) {
            _state.value =
                _state.value.copy(
                    status =
                        BleCentralStatus.DATA_RECEIVING,
                    message =
                        "Recibiendo fragmentos cifrados..."
                )

            return
        }

        if (
            assembledMessage.messageType !=
            BleMessageType.ENCRYPTED_DATA
        ) {
            incomingDataAssembler.clear()

            updateError(
                "Se recibió un tipo de mensaje inesperado"
            )
            return
        }

        val envelope = runCatching {
            val envelopeJson =
                assembledMessage.payload.decodeToString()

            EncryptedTransferEnvelopeCodec.decode(
                envelopeJson
            )
        }.getOrElse { error ->
            incomingDataAssembler.clear()

            updateError(
                "El sobre cifrado es inválido: ${error.message}"
            )
            return
        }

        val expectedSessionId = verifiedSessionId

        if (
            expectedSessionId.isNullOrBlank() ||
            envelope.sessionId != expectedSessionId
        ) {
            incomingDataAssembler.clear()

            updateError(
                "El mensaje no pertenece a la sesión verificada"
            )
            return
        }

        _state.value =
            _state.value.copy(
                status =
                    BleCentralStatus.DATA_RECEIVING,
                message =
                    "Mensaje reconstruido. Descifrando..."
            )

        repositoryScope.launch {
            runCatching {
                sessionKeyRepository.decryptSessionMessage(
                    envelope
                )
            }.onSuccess { decryptedJson ->
                incomingDataAssembler.clear()

                val idempotencyKey =
                    "${envelope.sessionId}|${envelope.messageId}"

                val firstReception =
                    processedMessageIds.add(
                        idempotencyKey
                    )

                _state.value =
                    _state.value.copy(
                        status =
                            BleCentralStatus.DATA_RECEIVED,
                        message = if (firstReception) {
                            "JSON recibido y descifrado correctamente"
                        } else {
                            "Mensaje duplicado; no se procesó nuevamente"
                        },
                        deviceAddress = gatt.device.address,
                        receivedMessageId = envelope.messageId,
                        receivedData = if (firstReception) {
                            decryptedJson
                        } else {
                            _state.value.receivedData
                        }
                    )

                sendEncryptedAck(
                    gatt = gatt,
                    sessionId = envelope.sessionId,
                    acknowledgedMessageId =
                        envelope.messageId,
                    duplicate = !firstReception
                )
            }.onFailure { error ->
                incomingDataAssembler.clear()

                Log.e(
                    "BLE_CLIENT",
                    "No fue posible descifrar el mensaje",
                    error
                )

                updateError(
                    "AES-GCM rechazó el mensaje: ${error.message}"
                )
            }
        }
    }

    private fun sendEncryptedAck(
        gatt: BluetoothGatt,
        sessionId: String,
        acknowledgedMessageId: String,
        duplicate: Boolean
    ) {
        repositoryScope.launch {
            runCatching {
                val acknowledgement =
                    TransferAcknowledgement(
                        protocolVersion = 1,
                        sessionId = sessionId,
                        acknowledgedMessageId =
                            acknowledgedMessageId,
                        duplicate = duplicate
                    )

                val acknowledgementJson =
                    TransferAcknowledgementCodec.encode(
                        acknowledgement
                    )

                val encryptedEnvelope =
                    sessionKeyRepository
                        .encryptSessionMessage(
                            sessionId = sessionId,
                            messageId =
                                UUID.randomUUID().toString(),
                            plainText =
                                acknowledgementJson
                        )

                val envelopeJson =
                    EncryptedTransferEnvelopeCodec.encode(
                        encryptedEnvelope
                    )

                frameCodec.fragment(
                    messageType =
                        BleMessageType.ACK,
                    payload =
                        envelopeJson.encodeToByteArray(),
                    negotiatedMtu = negotiatedMtu
                )
            }.onSuccess { frames ->
                synchronized(ackFramesLock) {
                    ackFrames.clear()
                    ackFrames.addAll(frames)

                    totalAckFrames = frames.size
                    sentAckFrames = 0
                }

                pendingControlWrite =
                    ControlWriteOperation.SEND_ACK

                _state.value =
                    _state.value.copy(
                        status =
                            BleCentralStatus.ACK_SENDING,
                        message =
                            "Enviando ACK cifrado en " +
                                    "${frames.size} fragmentos..."
                    )

                val transferStatus =
                    gatt.getService(
                        BleProtocol.SERVICE_UUID
                    )?.getCharacteristic(
                        BleProtocol.TRANSFER_STATUS_UUID
                    )

                if (transferStatus == null) {
                    clearPendingAck()

                    updateError(
                        "No se encontró TRANSFER_STATUS"
                    )

                    return@onSuccess
                }

                writeNextAckFrame(
                    gatt = gatt,
                    characteristic = transferStatus
                )
            }.onFailure { error ->
                clearPendingAck()

                updateError(
                    "No fue posible crear el ACK: " +
                            error.message
                )
            }
        }
    }

    @SuppressLint("MissingPermission")
    private fun writeNextAckFrame(
        gatt: BluetoothGatt,
        characteristic: BluetoothGattCharacteristic
    ): Boolean {
        val frame: ByteArray
        val frameNumber: Int

        synchronized(ackFramesLock) {
            if (ackFrames.isEmpty()) {
                return false
            }

            frame = ackFrames.removeFirst()
            sentAckFrames += 1
            frameNumber = sentAckFrames
        }

        _state.value =
            _state.value.copy(
                status = BleCentralStatus.ACK_SENDING,
                message =
                    "Enviando ACK $frameNumber/" +
                            totalAckFrames
            )

        val writeStarted =
            writeControlCharacteristic(
                gatt = gatt,
                characteristic = characteristic,
                payload = frame
            )

        if (!writeStarted) {
            clearPendingAck()

            updateError(
                "No fue posible enviar el ACK " +
                        frameNumber
            )
        }

        return writeStarted
    }

    private fun clearPendingAck() {
        synchronized(ackFramesLock) {
            ackFrames.clear()
            totalAckFrames = 0
            sentAckFrames = 0
        }

        if (
            pendingControlWrite ==
            ControlWriteOperation.SEND_ACK
        ) {
            pendingControlWrite =
                ControlWriteOperation.NONE
        }
    }

    private fun clearCurrentSession() {

        receiverHandshakeCompleted = false

        synchronized(reverseAckLock) {
            pendingReverseWrite?.completeExceptionally(
                IllegalStateException("La conexión BLE se cerró")
            )
            pendingReverseWrite = null
        }

        reverseAckAssembler.clear()
        synchronized(reverseAckLock) {
            pendingReverseAck?.completeExceptionally(
                IllegalStateException("La conexión BLE se cerró")
            )
            pendingReverseAck = null
            pendingReverseMessageId = null
        }

        val sessionId = verifiedSessionId ?: pendingSessionId

        sessionId?.let {
            sessionKeyRepository.clearSession(it)

            processedMessageIds.removeIf { key ->
                key.startsWith("$it|")
            }
        }

        pendingSessionId = null
        verifiedSessionId = null
    }

    @SuppressLint("MissingPermission")
    override fun disconnect() {
        stopScan()

        clearPendingHandshake()

        clearPendingAck()

        incomingDataAssembler.clear()

        clearCurrentSession()

        negotiatedMtu = 23

        bluetoothGatt?.disconnect()
        closeCurrentGatt()

        _state.value = BleCentralState(
            status = BleCentralStatus.IDLE,
            message = "Cliente BLE detenido"
        )
    }

    @SuppressLint("MissingPermission")
    override fun confirmSession(
        sessionId: String
    ) {
        if (sessionId.isBlank()) {
            updateError("No existe un sessionId validado")
            return
        }

        val gatt = bluetoothGatt

        if (gatt == null) {
            updateError("No existe una conexión GATT")
            return
        }

        val characteristic = gatt
            .getService(BleProtocol.SERVICE_UUID)
            ?.getCharacteristic(
                BleProtocol.SESSION_CONTROL_UUID
            )

        if (characteristic == null) {
            updateError("No se encontró SESSION_CONTROL")
            return
        }

        val payload = "HELLO|$sessionId"
            .toByteArray(StandardCharsets.UTF_8)

        pendingControlWrite =
            ControlWriteOperation.CONFIRM_SESSION

        pendingSessionId = sessionId

        _state.value = BleCentralState(
            status = BleCentralStatus.VERIFYING_SESSION,
            message = "Confirmando la sesión del QR...",
            deviceAddress = gatt.device.address
        )

        val writeStarted = writeControlCharacteristic(
            gatt = gatt,
            characteristic = characteristic,
            payload = payload
        )

        if (!writeStarted) {
            pendingControlWrite =
                ControlWriteOperation.NONE

            updateError("No fue posible enviar el sessionId")
        }
    }

    @SuppressLint("MissingPermission")
    override fun sendReceiverHandshake(
        handshakeJson: String,
        sessionKeyFingerprint: String
    ) {
        if (handshakeJson.isBlank()) {
            updateError(
                "El handshake del receptor está vacío"
            )
            return
        }

        val gatt = bluetoothGatt

        if (gatt == null) {
            updateError("No existe una conexión GATT")
            return
        }

        if (
            _state.value.status !=
            BleCentralStatus.SESSION_VERIFIED
        ) {
            updateError(
                "Primero debe confirmarse la sesión del QR"
            )
            return
        }

        val characteristic = gatt
            .getService(BleProtocol.SERVICE_UUID)
            ?.getCharacteristic(
                BleProtocol.SESSION_CONTROL_UUID
            )

        if (characteristic == null) {
            updateError("No se encontró SESSION_CONTROL")
            return
        }

        val frames = runCatching {
            frameCodec.fragment(
                messageType =
                    BleMessageType.RECEIVER_HANDSHAKE,
                payload =
                    handshakeJson.encodeToByteArray(),
                negotiatedMtu = negotiatedMtu
            )
        }.getOrElse { error ->
            updateError(
                "No fue posible fragmentar el handshake: " +
                        error.message
            )
            return
        }

        synchronized(handshakeFramesLock) {
            handshakeFrames.clear()
            handshakeFrames.addAll(frames)

            totalHandshakeFrames = frames.size
            sentHandshakeFrames = 0
        }

        pendingControlWrite =
            ControlWriteOperation
                .SEND_RECEIVER_HANDSHAKE

        pendingSessionKeyFingerprint =
            sessionKeyFingerprint

        _state.value = BleCentralState(
            status = BleCentralStatus.EXCHANGING_ECDH,
            message =
                "Enviando handshake firmado en " +
                        "${frames.size} fragmentos...",
            deviceAddress = gatt.device.address
        )

        writeNextHandshakeFrame(
            gatt = gatt,
            characteristic = characteristic
        )
    }

    override suspend fun sendReply(text: String) {
        require(text.isNotBlank()) { "La respuesta está vacía" }
        require(text.encodeToByteArray().size <= 100_000) {
            "La respuesta supera el tamaño permitido"
        }

        val gatt = checkNotNull(bluetoothGatt) {
            "No existe una conexión BLE activa"
        }
        val characteristic = checkNotNull(reverseDataCharacteristic) {
            "El emisor no ofrece el canal de respuesta"
        }
        val sessionId = checkNotNull(verifiedSessionId) {
            "Todavía no se confirmó la sesión"
        }

        check(receiverHandshakeCompleted) {
            "El handshake aún no ha terminado"
        }
        check(sessionKeyRepository.hasSessionKey(sessionId)) {
            "No existe la clave de esta sesión"
        }
        check(pendingControlWrite == ControlWriteOperation.NONE) {
            "Hay otra operación BLE en curso"
        }

        val acknowledgement = CompletableDeferred<Unit>()
        val messageId = UUID.randomUUID().toString()

        synchronized(reverseAckLock) {
            check(pendingReverseAck == null) {
                "Ya existe una respuesta esperando ACK"
            }
            pendingReverseMessageId = messageId
            pendingReverseAck = acknowledgement
        }

        try {
            val envelope = sessionKeyRepository.encryptSessionMessage(
                sessionId = sessionId,
                messageId = messageId,
                plainText = text,
                purpose = SessionMessagePurpose.REVERSE_DATA
            )
            val frames = frameCodec.fragment(
                messageType = BleMessageType.ENCRYPTED_DATA,
                payload = EncryptedTransferEnvelopeCodec
                    .encode(envelope)
                    .encodeToByteArray(),
                negotiatedMtu = negotiatedMtu
            )

            for (attempt in 0..2) {
                if (acknowledgement.isCompleted) {
                    acknowledgement.await()
                    return
                }

                reverseAckAssembler.clear()

                for (frame in frames) {
                    if (acknowledgement.isCompleted) break

                    check(bluetoothGatt === gatt && verifiedSessionId == sessionId) {
                        "La conexión BLE cambió durante la respuesta"
                    }
                    writeReverseFrame(gatt, characteristic, frame)
                }

                try {
                    withTimeout(5_000L) {
                        acknowledgement.await()
                    }
                    return
                } catch (error: TimeoutCancellationException) {
                    if (attempt == 2) throw error
                }
            }
        } finally {
            synchronized(reverseAckLock) {
                if (pendingReverseAck === acknowledgement) {
                    pendingReverseAck = null
                    pendingReverseMessageId = null
                }
            }
            reverseAckAssembler.clear()
        }
    }

    @SuppressLint("MissingPermission")
    private fun writeNextHandshakeFrame(
        gatt: BluetoothGatt,
        characteristic: BluetoothGattCharacteristic
    ): Boolean {
        val frame: ByteArray
        val currentFrameNumber: Int

        synchronized(handshakeFramesLock) {
            if (handshakeFrames.isEmpty()) {
                return false
            }

            frame = handshakeFrames.removeFirst()
            sentHandshakeFrames += 1
            currentFrameNumber = sentHandshakeFrames
        }

        _state.value = BleCentralState(
            status = BleCentralStatus.EXCHANGING_ECDH,
            message =
                "Enviando fragmento " +
                        "$currentFrameNumber/" +
                        "$totalHandshakeFrames",
            deviceAddress = gatt.device.address
        )

        val writeStarted = writeControlCharacteristic(
            gatt = gatt,
            characteristic = characteristic,
            payload = frame
        )

        if (!writeStarted) {
            clearPendingHandshake()

            updateError(
                "No fue posible enviar el fragmento " +
                        currentFrameNumber
            )
        }

        return writeStarted
    }

    private fun clearPendingHandshake() {
        synchronized(handshakeFramesLock) {
            handshakeFrames.clear()
            totalHandshakeFrames = 0
            sentHandshakeFrames = 0
        }

        pendingControlWrite =
            ControlWriteOperation.NONE

        pendingSessionKeyFingerprint = null
    }

    @SuppressLint("MissingPermission")
    private suspend fun writeReverseFrame(
        gatt: BluetoothGatt,
        characteristic: BluetoothGattCharacteristic,
        frame: ByteArray
    ) {
        val completion = CompletableDeferred<Unit>()

        synchronized(reverseAckLock) {
            check(pendingReverseWrite == null) {
                "Ya existe una escritura de respuesta pendiente"
            }
            pendingReverseWrite = completion
        }

        try {
            val started = withContext(Dispatchers.Main) {
                if (
                    bluetoothGatt !== gatt ||
                    pendingControlWrite != ControlWriteOperation.NONE
                ) {
                    false
                } else {
                    writeControlCharacteristic(
                        gatt = gatt,
                        characteristic = characteristic,
                        payload = frame
                    )
                }
            }

            check(started) {
                "No fue posible iniciar la escritura de un fragmento de respuesta"
            }

            withTimeout(10_000L) {
                completion.await()
            }
        } finally {
            synchronized(reverseAckLock) {
                if (pendingReverseWrite === completion) {
                    pendingReverseWrite = null
                }
            }
        }
    }

    @SuppressLint("MissingPermission")
    private fun writeControlCharacteristic(
        gatt: BluetoothGatt,
        characteristic: BluetoothGattCharacteristic,
        payload: ByteArray
    ): Boolean {
        return if (
            Build.VERSION.SDK_INT >=
            Build.VERSION_CODES.TIRAMISU
        ) {
            gatt.writeCharacteristic(
                characteristic,
                payload,
                BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
            ) == BluetoothStatusCodes.SUCCESS
        } else {
            @Suppress("DEPRECATION")
            characteristic.writeType =
                BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT

            @Suppress("DEPRECATION")
            characteristic.value = payload

            @Suppress("DEPRECATION")
            gatt.writeCharacteristic(characteristic)
        }
    }

    @SuppressLint("MissingPermission")
    private fun stopScan() {
        scanTimeout?.let(handler::removeCallbacks)
        scanTimeout = null

        val callback = scanCallback ?: return

        bluetoothAdapter
            ?.bluetoothLeScanner
            ?.stopScan(callback)

        scanCallback = null
    }

    private fun markServiceReady(gatt: BluetoothGatt) {
        if (bluetoothGatt !== gatt) return

        Log.d(
            "BLE_CLIENT",
            "Canal de respuesta disponible: ${reverseDataCharacteristic != null}"
        )

        _state.value = BleCentralState(
            status = BleCentralStatus.SERVICE_READY,
            message = "IR_TRANSFER listo para recibir datos",
            deviceAddress = gatt.device.address
        )
    }

    @SuppressLint("MissingPermission")
    private fun enableReverseAckNotifications(gatt: BluetoothGatt) {
        if (bluetoothGatt !== gatt) return

        val characteristic = gatt
            .getService(BleProtocol.SERVICE_UUID)
            ?.getCharacteristic(BleProtocol.TRANSFER_STATUS_UUID)
        val descriptor = characteristic
            ?.getDescriptor(BleProtocol.CLIENT_CONFIGURATION_UUID)

        if (
            reverseDataCharacteristic == null ||
            characteristic == null ||
            descriptor == null ||
            !gatt.setCharacteristicNotification(characteristic, true)
        ) {
            reverseDataCharacteristic = null
            markServiceReady(gatt)
            return
        }

        val started = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            gatt.writeDescriptor(
                descriptor,
                BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
            ) == BluetoothStatusCodes.SUCCESS
        } else {
            @Suppress("DEPRECATION")
            descriptor.value =
                BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE

            @Suppress("DEPRECATION")
            gatt.writeDescriptor(descriptor)
        }

        if (!started) {
            gatt.setCharacteristicNotification(characteristic, false)
            reverseDataCharacteristic = null
            markServiceReady(gatt)
        }
    }

    @SuppressLint("MissingPermission")
    private fun closeCurrentGatt() {
        bluetoothGatt?.close()
        bluetoothGatt = null
        reverseDataCharacteristic = null
    }

    @SuppressLint("MissingPermission")
    private fun closeGatt(gatt: BluetoothGatt) {
        gatt.close()

        if (bluetoothGatt === gatt) {
            bluetoothGatt = null
            reverseDataCharacteristic = null
        }
    }

    private fun updateError(message: String) {
        _state.value = BleCentralState(
            status = BleCentralStatus.ERROR,
            message = message
        )
    }

    private fun hasRequiredPermissions(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            hasPermission(Manifest.permission.BLUETOOTH_SCAN) &&
                    hasPermission(Manifest.permission.BLUETOOTH_CONNECT)
        } else {
            hasPermission(Manifest.permission.ACCESS_FINE_LOCATION)
        }
    }

    private fun hasPermission(permission: String): Boolean {
        return ContextCompat.checkSelfPermission(
            context,
            permission
        ) == PackageManager.PERMISSION_GRANTED
    }

}