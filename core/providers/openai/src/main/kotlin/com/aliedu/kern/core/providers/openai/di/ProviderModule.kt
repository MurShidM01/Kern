package com.aliedu.kern.core.providers.openai.di

import com.aliedu.kern.core.providers.api.AiProviderAdapter
import com.aliedu.kern.core.providers.openai.OpenAiCompatibleAdapter
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object ProviderNetworkModule {

    /**
     * `encodeDefaults = true` keeps non-null defaults like `stream`/`type` in the payload while
     * `explicitNulls = false` omits null optionals the API would reject (e.g. max_tokens).
     */
    @Provides
    @Singleton
    fun provideJson(): Json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        explicitNulls = false
        isLenient = true
    }

    @Provides
    @Singleton
    fun provideOkHttpClient(): OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .build()
}

@Module
@InstallIn(SingletonComponent::class)
abstract class ProviderBindingModule {
    @Binds
    @Singleton
    abstract fun bindAiProviderAdapter(impl: OpenAiCompatibleAdapter): AiProviderAdapter
}
