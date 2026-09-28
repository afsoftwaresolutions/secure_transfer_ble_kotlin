package com.interrapidisimo.securetransferpoc.data.ble

import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.pm.PackageManager
import com.interrapidisimo.securetransferpoc.domain.model.BleCapabilities
import com.interrapidisimo.securetransferpoc.domain.repository.BleCapabilityRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class AndroidBleCapabilityRepository @Inject constructor(
    @ApplicationContext private val context: Context
) : BleCapabilityRepository {

    override suspend fun checkCapabilities(): BleCapabilities = withContext(Dispatchers.Default) {

        val packageManager = context.packageManager

        val systemSupportsBle = packageManager.hasSystemFeature(
            PackageManager.FEATURE_BLUETOOTH_LE
        )

        val bluetoothManager = context.getSystemService(
            BluetoothManager::class.java
        )

        val adapter = bluetoothManager?.adapter

        if (!systemSupportsBle || adapter == null) {
            return@withContext BleCapabilities(
                bleSupported = false,
                bluetoothEnabled = false,
                canScan = false,
                canAdvertise = false
            )
        }

        val bluetoothEnabled = runCatching {
            adapter.isEnabled
        }.getOrDefault(false)

        val canScan = bluetoothEnabled && runCatching {
            adapter.bluetoothLeScanner != null
        }.getOrDefault(false)

        val canAdvertise = bluetoothEnabled && runCatching {
            adapter.isMultipleAdvertisementSupported &&
                    adapter.bluetoothLeAdvertiser != null
        }.getOrDefault(false)

        BleCapabilities(
            bleSupported = true,
            bluetoothEnabled = bluetoothEnabled,
            canScan = canScan,
            canAdvertise = canAdvertise
        )
    }
}