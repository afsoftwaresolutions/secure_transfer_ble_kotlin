package com.interrapidisimo.securetransferpoc.di

import com.interrapidisimo.securetransferpoc.data.crypto.AndroidDeviceIdentityRepository
import com.interrapidisimo.securetransferpoc.data.crypto.EcdhSessionKeyRepository
import com.interrapidisimo.securetransferpoc.domain.repository.DeviceIdentityRepository
import com.interrapidisimo.securetransferpoc.domain.repository.SessionKeyRepository
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
abstract class IdentityModule {

    @Binds
    @Singleton
    abstract fun bindDeviceIdentityRepository(
        implementation: AndroidDeviceIdentityRepository
    ): DeviceIdentityRepository

    @Binds
    @Singleton
    abstract fun bindSessionKeyRepository(
        implementation: EcdhSessionKeyRepository
    ): SessionKeyRepository

}