package com.aliedu.kern.core.auth.di

import com.aliedu.kern.core.auth.AuthCoordinator
import com.aliedu.kern.core.auth.DefaultAuthCoordinator
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
abstract class AuthModule {
    @Binds
    @Singleton
    abstract fun bindAuthCoordinator(impl: DefaultAuthCoordinator): AuthCoordinator
}
