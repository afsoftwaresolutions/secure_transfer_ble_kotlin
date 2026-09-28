package com.interrapidisimo.securetransferpoc.di

import com.interrapidisimo.securetransferpoc.data.session.AndroidSessionInvitationRepository
import com.interrapidisimo.securetransferpoc.domain.repository.SessionInvitationRepository
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
abstract class SessionModule {

    @Binds
    @Singleton
    abstract fun bindSessionInvitationRepository(
        implementation:
        AndroidSessionInvitationRepository
    ): SessionInvitationRepository
}