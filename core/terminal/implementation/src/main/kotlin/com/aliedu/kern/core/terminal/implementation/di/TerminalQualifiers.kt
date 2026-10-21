package com.aliedu.kern.core.terminal.implementation.di

import javax.inject.Qualifier

/**
 * Qualifiers for the terminal module's own networking stack. The app already binds an unqualified
 * [okhttp3.OkHttpClient] in `:core:providers:openai`; the terminal uses a distinct, longer-read-timeout
 * client for large bundle downloads, so it must be qualified to avoid a duplicate binding (PRD 12.4).
 */
@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class TerminalHttpClient

@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class TerminalJson
