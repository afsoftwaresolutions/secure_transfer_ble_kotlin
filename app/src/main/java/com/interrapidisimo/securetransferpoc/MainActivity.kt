package com.interrapidisimo.securetransferpoc

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.interrapidisimo.securetransferpoc.presentation.transfer.TransferScreen.QrCodeImage
import com.interrapidisimo.securetransferpoc.presentation.transfer.TransferViewModel.TransferViewModel
import com.interrapidisimo.securetransferpoc.ui.theme.SecureTransferPocTheme
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions
import dagger.hilt.android.AndroidEntryPoint
import androidx.compose.material3.OutlinedTextField

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    private enum class PendingBleAction {
        SEND,
        RECEIVE
    }

    private val viewModel:
            TransferViewModel by viewModels()

    private var pendingBleAction:
            PendingBleAction? = null

    private val qrScannerLauncher =
        registerForActivityResult(
            ScanContract()
        ) { result ->
            result.contents?.let { rawContent ->
                viewModel.processScannedInvitation(
                    rawContent
                )
            }
        }

    private val blePermissionLauncher =
        registerForActivityResult(
            ActivityResultContracts
                .RequestMultiplePermissions()
        ) { permissions ->
            val allGranted =
                permissions.values.all { it }

            viewModel.onBlePermissionsResult(
                allGranted
            )

            if (allGranted) {
                continuePendingBleAction()
            } else {
                pendingBleAction = null
            }
        }

    override fun onCreate(
        savedInstanceState: Bundle?
    ) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        setContent {
            SecureTransferPocTheme {
                val uiState by viewModel.uiState
                    .collectAsStateWithLifecycle()

                val scrollState =
                    rememberScrollState()

                Scaffold(
                    modifier = Modifier.fillMaxSize()
                ) { innerPadding ->
                    Column(
                        modifier = Modifier
                            .padding(innerPadding)
                            .padding(24.dp)
                            .fillMaxSize()
                            .verticalScroll(scrollState),
                        verticalArrangement =
                            Arrangement.spacedBy(16.dp)
                    ) {
                        Text(
                            text =
                                "Transferencia segura BLE"
                        )

                        Text(text = uiState.message)

                        uiState.error?.let {
                            Text(text = "Error: $it")
                        }

                        Button(
                            onClick = {
                                beginBleAction(
                                    PendingBleAction.SEND
                                )
                            },
                            enabled = !uiState.isLoading,
                            modifier =
                                Modifier.fillMaxWidth()
                        ) {
                            Text("Enviar datos")
                        }

                        OutlinedButton(
                            onClick = {
                                beginBleAction(
                                    PendingBleAction.RECEIVE
                                )
                            },
                            enabled = !uiState.isLoading,
                            modifier =
                                Modifier.fillMaxWidth()
                        ) {
                            Text("Recibir datos")
                        }

                        when (uiState.selectedRole) {
                            "SENDER" -> {
                                Text(
                                    text =
                                        "Modo: Emisor"
                                )

                                Text(
                                    text =
                                        "Estado: " +
                                                uiState.peripheralStatus
                                )

                                Text(
                                    text =
                                        uiState.peripheralMessage
                                )

                                uiState.receivedReply?.let { reply ->
                                    Text("Respuesta recibida: $reply")
                                }

                                uiState.invitationJson
                                    ?.let { invitationJson ->
                                        QrCodeImage(
                                            content =
                                                invitationJson
                                        )

                                        Text(
                                            "El receptor debe escanear este QR"
                                        )
                                    }

                                OutlinedTextField(
                                    value = uiState.outgoingText,
                                    onValueChange =
                                        viewModel::onOutgoingTextChanged,
                                    label = {
                                        Text("Información que se enviará")
                                    },
                                    supportingText = {
                                        Text(
                                            "${uiState.outgoingText.encodeToByteArray().size} bytes"
                                        )
                                    },
                                    minLines = 5,
                                    maxLines = 12,
                                    modifier = Modifier.fillMaxWidth()
                                )

                                Button(
                                    onClick =
                                        viewModel:: sendOutgoingText,
                                    enabled =
                                        uiState.peripheralStatus ==
                                                "SESSION_KEY_READY" ||
                                                uiState.peripheralStatus ==
                                                "DATA_CONFIRMED",
                                    modifier =
                                        Modifier.fillMaxWidth()
                                ) {
                                    Text(
                                        "Enviar texto cifrado"
                                    )
                                }

                                if (
                                    uiState.peripheralStatus ==
                                    "DATA_CONFIRMED"
                                ) {
                                    Text(
                                        "✅ Transferencia confirmada"
                                    )
                                }

                                OutlinedButton(
                                    onClick =
                                        viewModel::
                                        stopBlePeripheral,
                                    modifier =
                                        Modifier.fillMaxWidth()
                                ) {
                                    Text("Detener emisor")
                                }
                            }

                            "RECEIVER" -> {
                                Text(
                                    text =
                                        "Modo: Receptor"
                                )

                                Text(
                                    text =
                                        "Estado: " +
                                                uiState.centralStatus
                                )

                                Text(
                                    text =
                                        uiState.centralMessage
                                )

                                uiState.receivedMessageId
                                    ?.let {
                                        Text(
                                            "Message ID: $it"
                                        )
                                    }

                                uiState.receivedJson
                                    ?.let {
                                        Text(
                                            "JSON recibido y descifrado:\n$it"
                                        )
                                    }

                                if (
                                    uiState.centralStatus ==
                                    "ACK_SENT"
                                ) {
                                    Text(
                                        "✅ Recepción confirmada"
                                    )
                                }

                                OutlinedTextField(
                                    value = uiState.outgoingText,
                                    onValueChange = viewModel::onOutgoingTextChanged,
                                    label = { Text("Respuesta al creador del QR") },
                                    minLines = 3,
                                    maxLines = 8,
                                    modifier = Modifier.fillMaxWidth()
                                )

                                Button(
                                    onClick = viewModel::sendReply,
                                    enabled = uiState.canSendReply && !uiState.isLoading,
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Text("Responder al creador del QR")
                                }

                                OutlinedButton(
                                    onClick =
                                        viewModel::
                                        disconnectCentralBle,
                                    modifier =
                                        Modifier.fillMaxWidth()
                                ) {
                                    Text(
                                        "Desconectar receptor"
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    private fun beginBleAction(
        action: PendingBleAction
    ) {
        pendingBleAction = action
        requestBlePermissions()
    }

    private fun continuePendingBleAction() {
        val action = pendingBleAction ?: return
        pendingBleAction = null

        when (action) {
            PendingBleAction.SEND -> {
                viewModel.startSimplifiedSenderFlow()
            }

            PendingBleAction.RECEIVE -> {
                viewModel.prepareSimplifiedReceiverFlow()
                launchQrScanner()
            }
        }
    }

    private fun launchQrScanner() {
        val options = ScanOptions().apply {
            setPrompt(
                "Escanea la invitación del emisor"
            )
            setBeepEnabled(false)
            setOrientationLocked(false)
        }

        qrScannerLauncher.launch(options)
    }

    private fun requestBlePermissions() {
        val requiredPermissions =
            if (
                Build.VERSION.SDK_INT >=
                Build.VERSION_CODES.S
            ) {
                arrayOf(
                    Manifest.permission.BLUETOOTH_SCAN,
                    Manifest.permission.BLUETOOTH_CONNECT,
                    Manifest.permission.BLUETOOTH_ADVERTISE
                )
            } else {
                arrayOf(
                    Manifest.permission
                        .ACCESS_FINE_LOCATION
                )
            }

        val allGranted =
            requiredPermissions.all { permission ->
                ContextCompat.checkSelfPermission(
                    this,
                    permission
                ) == PackageManager.PERMISSION_GRANTED
            }

        if (allGranted) {
            viewModel.onBlePermissionsResult(true)
            continuePendingBleAction()
        } else {
            blePermissionLauncher.launch(
                requiredPermissions
            )
        }
    }
}