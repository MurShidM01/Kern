package com.aliedu.kern.core.agent

import android.content.Context
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import java.io.File
import javax.inject.Singleton

/** Provides the agent's per-workspace notes rooted at the app's private `~/.kern` directory. */
@Module
@InstallIn(SingletonComponent::class)
object NotesModule {

    @Provides
    @Singleton
    fun provideWorkspaceNotes(@ApplicationContext context: Context): WorkspaceNotes =
        WorkspaceNotes(File(context.filesDir, ".kern"))
}
