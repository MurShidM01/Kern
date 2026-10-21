package com.aliedu.kern.core.terminal.implementation.di

import com.aliedu.kern.core.terminal.implementation.runtime.LinuxProotBackend
import com.aliedu.kern.core.terminal.implementation.runtime.DeviceShellBackend
import com.aliedu.kern.core.terminal.implementation.runtime.SessionBackend
import dagger.Binds
import dagger.Module
import dagger.multibindings.IntoSet
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

/** Registers every session backend so [TerminalSessionManager] can select one per [SessionKind]. */
@Module
@InstallIn(SingletonComponent::class)
abstract class TerminalBackendModule {
    @Binds
    @IntoSet
    abstract fun bindDeviceShellBackend(impl: DeviceShellBackend): SessionBackend

    @Binds
    @IntoSet
    abstract fun bindLinuxProotBackend(impl: LinuxProotBackend): SessionBackend
}
