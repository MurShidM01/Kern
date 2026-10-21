package com.aliedu.kern.core.workspace.di

import com.aliedu.kern.core.workspace.ExecutionWorkspaceManager
import com.aliedu.kern.core.workspace.ExecutionWorkspaceResolver
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/** Exposes the workspace execution resolver as an interface so consumers avoid the mirror internals. */
@Module
@InstallIn(SingletonComponent::class)
abstract class WorkspaceModule {

    @Binds
    @Singleton
    abstract fun bindExecutionWorkspaceResolver(impl: ExecutionWorkspaceManager): ExecutionWorkspaceResolver
}
