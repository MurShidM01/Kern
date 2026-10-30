package com.aliedu.kern.core.data.di

import android.content.Context
import androidx.room.Room
import com.aliedu.kern.core.common.coroutines.DefaultDispatcherProvider
import com.aliedu.kern.core.common.coroutines.DispatcherProvider
import com.aliedu.kern.core.data.local.KernDatabase
import com.aliedu.kern.core.data.local.dao.CredentialRecordDao
import com.aliedu.kern.core.data.local.dao.LocalPreferenceDao
import com.aliedu.kern.core.data.local.dao.MessageDao
import com.aliedu.kern.core.data.local.dao.PendingPatchDao
import com.aliedu.kern.core.data.local.dao.ProviderProfileDao
import com.aliedu.kern.core.data.local.dao.SessionDao
import com.aliedu.kern.core.data.local.dao.TodoDao
import com.aliedu.kern.core.data.local.dao.ToolEventDao
import com.aliedu.kern.core.data.local.dao.WorkspaceDao
import com.aliedu.kern.core.data.preferences.DataStoreSettingsRepository
import com.aliedu.kern.core.data.preferences.SettingsRepository
import com.aliedu.kern.core.data.repository.DefaultMessageRepository
import com.aliedu.kern.core.data.repository.DefaultPatchRepository
import com.aliedu.kern.core.data.repository.DefaultProviderRepository
import com.aliedu.kern.core.data.repository.DefaultSessionRepository
import com.aliedu.kern.core.data.repository.DefaultTodoRepository
import com.aliedu.kern.core.data.repository.DefaultToolEventRepository
import com.aliedu.kern.core.data.repository.DefaultWorkspaceRepository
import com.aliedu.kern.core.data.repository.MessageRepository
import com.aliedu.kern.core.data.repository.PatchRepository
import com.aliedu.kern.core.data.repository.ProviderRepository
import com.aliedu.kern.core.data.repository.SessionRepository
import com.aliedu.kern.core.data.repository.TodoRepository
import com.aliedu.kern.core.data.repository.ToolEventRepository
import com.aliedu.kern.core.data.repository.WorkspaceRepository
import com.aliedu.kern.core.data.security.AndroidKeystoreCredentialCipher
import com.aliedu.kern.core.data.security.CredentialCipher
import com.aliedu.kern.core.data.security.CredentialStore
import com.aliedu.kern.core.data.security.RoomKeystoreCredentialStore
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object DatabaseModule {

    @Provides
    @Singleton
    fun provideDatabase(@ApplicationContext context: Context): KernDatabase =
        Room.databaseBuilder(context, KernDatabase::class.java, KernDatabase.DATABASE_NAME)
            .addMigrations(
                KernDatabase.MIGRATION_1_2,
                KernDatabase.MIGRATION_2_3,
                KernDatabase.MIGRATION_3_4,
                KernDatabase.MIGRATION_4_5
            )
            .build()

    @Provides
    fun provideWorkspaceDao(db: KernDatabase): WorkspaceDao = db.workspaceDao()

    @Provides
    fun provideProviderProfileDao(db: KernDatabase): ProviderProfileDao = db.providerProfileDao()

    @Provides
    fun provideCredentialRecordDao(db: KernDatabase): CredentialRecordDao = db.credentialRecordDao()

    @Provides
    fun provideSessionDao(db: KernDatabase): SessionDao = db.sessionDao()

    @Provides
    fun provideMessageDao(db: KernDatabase): MessageDao = db.messageDao()

    @Provides
    fun provideToolEventDao(db: KernDatabase): ToolEventDao = db.toolEventDao()

    @Provides
    fun provideTodoDao(db: KernDatabase): TodoDao = db.todoDao()

    @Provides
    fun providePendingPatchDao(db: KernDatabase): PendingPatchDao = db.pendingPatchDao()

    @Provides
    fun provideLocalPreferenceDao(db: KernDatabase): LocalPreferenceDao = db.localPreferenceDao()
}

@Module
@InstallIn(SingletonComponent::class)
object DispatcherModule {
    @Provides
    @Singleton
    fun provideDispatcherProvider(): DispatcherProvider = DefaultDispatcherProvider()
}

@Module
@InstallIn(SingletonComponent::class)
abstract class RepositoryModule {

    @Binds
    abstract fun bindWorkspaceRepository(impl: DefaultWorkspaceRepository): WorkspaceRepository

    @Binds
    abstract fun bindProviderRepository(impl: DefaultProviderRepository): ProviderRepository

    @Binds
    abstract fun bindSessionRepository(impl: DefaultSessionRepository): SessionRepository

    @Binds
    abstract fun bindMessageRepository(impl: DefaultMessageRepository): MessageRepository

    @Binds
    abstract fun bindToolEventRepository(impl: DefaultToolEventRepository): ToolEventRepository

    @Binds
    abstract fun bindTodoRepository(impl: DefaultTodoRepository): TodoRepository

    @Binds
    abstract fun bindPatchRepository(impl: DefaultPatchRepository): PatchRepository

    @Binds
    abstract fun bindSettingsRepository(impl: DataStoreSettingsRepository): SettingsRepository

    @Binds
    abstract fun bindCredentialCipher(impl: AndroidKeystoreCredentialCipher): CredentialCipher

    @Binds
    abstract fun bindCredentialStore(impl: RoomKeystoreCredentialStore): CredentialStore
}
