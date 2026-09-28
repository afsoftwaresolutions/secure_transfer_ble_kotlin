package com.interrapidisimo.securetransferpoc.domain.model

data class BleCapabilities(
    val bleSupported: Boolean,
    val bluetoothEnabled: Boolean,
    val canScan: Boolean,
    val canAdvertise: Boolean
) {
    val readyForTransfer: Boolean
        get() = bleSupported &&
                bluetoothEnabled &&
                canScan &&
                canAdvertise
}
