package com.aliedu.kern.core.terminal.implementation.di

import com.aliedu.kern.core.terminal.api.TerminalRuntime
import com.aliedu.kern.core.terminal.implementation.install.DistroCatalogStore
import com.aliedu.kern.core.terminal.implementation.install.DistroInstaller
import com.aliedu.kern.core.terminal.implementation.install.EnvironmentInstaller
import com.aliedu.kern.core.terminal.implementation.install.DistroCatalogProvider
import com.aliedu.kern.core.terminal.implementation.runtime.DevServerManager
import com.aliedu.kern.core.terminal.implementation.runtime.LayeredTerminalRuntime
import com.aliedu.kern.core.terminal.implementation.runtime.PortProbe
import com.aliedu.kern.core.terminal.implementation.runtime.SocketPortProbe
import com.aliedu.kern.core.terminal.implementation.runtime.TerminalCommandExecutor
import com.aliedu.kern.core.terminal.implementation.runtime.TerminalInspectorImpl
import com.aliedu.kern.core.tools.CommandExecutor
import com.aliedu.kern.core.tools.TerminalInspector
import com.aliedu.kern.core.tools.devserver.DevServerRegistry
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
abstract class TerminalModule {
    @Binds
    @Singleton
    abstract fun bindTerminalRuntime(impl: LayeredTerminalRuntime): TerminalRuntime

    @Binds
    @Singleton
    abstract fun bindEnvironmentInstaller(impl: DistroInstaller): EnvironmentInstaller

    @Binds
    @Singleton
    abstract fun bindCatalogProvider(impl: DistroCatalogStore): DistroCatalogProvider

    @Binds
    @Singleton
    abstract fun bindCommandExecutor(impl: TerminalCommandExecutor): CommandExecutor

    @Binds
    @Singleton
    abstract fun bindDevServerRegistry(impl: DevServerManager): DevServerRegistry

    @Binds
    @Singleton
    abstract fun bindPortProbe(impl: SocketPortProbe): PortProbe

    @Binds
    @Singleton
    abstract fun bindTerminalInspector(impl: TerminalInspectorImpl): TerminalInspector
}
