package com.aliedu.kern.core.terminal.implementation.di

import android.content.Context
import android.os.StatFs
import com.aliedu.kern.core.terminal.implementation.install.DiskSpaceChecker
import com.aliedu.kern.core.terminal.implementation.install.TerminalPaths
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import java.io.File
import javax.inject.Singleton

/** Installer plumbing: the environment lives under `filesDir/terminal` (PRD 12.3). */
@Module
@InstallIn(SingletonComponent::class)
object TerminalInstallModule {

    @Provides
    @Singleton
    fun provideTerminalPaths(@ApplicationContext context: Context): TerminalPaths =
        TerminalPaths(
            root = File(context.filesDir, "terminal"),
            nativeLibraryDir = File(context.applicationInfo.nativeLibraryDir)
        )

    @Provides
    @Singleton
    fun provideDiskSpaceChecker(paths: TerminalPaths): DiskSpaceChecker {
        val dir = (paths.root.parentFile ?: paths.root).absolutePath
        return DiskSpaceChecker {
            runCatching { StatFs(dir).availableBytes }.getOrDefault(0L)
        }
    }
}
