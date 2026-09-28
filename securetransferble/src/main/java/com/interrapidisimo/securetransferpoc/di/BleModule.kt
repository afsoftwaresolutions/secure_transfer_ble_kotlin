package com.interrapidisimo.securetransferpoc.di

import com.interrapidisimo.securetransferpoc.data.ble.AndroidBleCapabilityRepository
import com.interrapidisimo.securetransferpoc.data.ble.AndroidBleCentralRepository
import com.interrapidisimo.securetransferpoc.data.ble.AndroidBlePeripheralRepository
import com.interrapidisimo.securetransferpoc.domain.repository.BleCapabilityRepository
import com.interrapidisimo.securetransferpoc.domain.repository.BleCentralRepository
import com.interrapidisimo.securetransferpoc.domain.repository.BlePeripheralRepository
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
abstract class BleModule {

    @Binds
    @Singleton
    abstract fun bindBleCapabilityRepository(
        implementation:
        AndroidBleCapabilityRepository
    ): BleCapabilityRepository

    @Binds
    @Singleton
    abstract fun bindBlePeripheralRepository(
        implementation:
        AndroidBlePeripheralRepository
    ): BlePeripheralRepository

    @Binds
    @Singleton
    abstract fun bindBleCentralRepository(
        implementation: AndroidBleCentralRepository
    ): BleCentralRepository
}