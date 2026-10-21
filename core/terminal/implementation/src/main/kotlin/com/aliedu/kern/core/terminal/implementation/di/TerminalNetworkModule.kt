package com.aliedu.kern.core.terminal.implementation.di

import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import android.os.Build
import com.aliedu.kern.core.terminal.implementation.install.AbiSupport
import java.util.concurrent.TimeUnit
import javax.inject.Singleton

/**
 * Networking for the terminal environment installer. Uses a dedicated, qualified client with a
 * long read timeout (bundle downloads can be slow) so it never collides with the provider client.
 */
@Module
@InstallIn(SingletonComponent::class)
object TerminalNetworkModule {

    @Provides
    @Singleton
    fun provideAbiSupport(): AbiSupport =
        AbiSupport(Build.SUPPORTED_64_BIT_ABIS.toList() + Build.SUPPORTED_ABIS.toList())

    @Provides
    @Singleton
    @TerminalHttpClient
    fun provideTerminalHttpClient(): OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .build()

    @Provides
    @Singleton
    @TerminalJson
    fun provideTerminalJson(): Json = Json {
        ignoreUnknownKeys = true
        isLenient = true
    }
}
