package com.aliedu.kern.core.common.logging

import android.util.Log

/**
 * Single logging entry point for the app (PRD Part 1 "logging policy").
 *
 * Rules:
 *  - Verbose/debug output is gated behind [enabled], which the app sets from BuildConfig.DEBUG.
 *  - Callers MUST NOT pass secrets. Use [com.aliedu.kern.core.common.redaction.Redactor] first.
 *  - Nothing here writes to a remote endpoint.
 */
object KernLog {

    /** Set once from the application (BuildConfig.DEBUG). Defaults to quiet for safety. */
    @Volatile
    var enabled: Boolean = false

    private const val PREFIX = "Kern"

    fun d(tag: String, message: String) {
        if (enabled) Log.d("$PREFIX:$tag", message)
    }

    fun i(tag: String, message: String) {
        if (enabled) Log.i("$PREFIX:$tag", message)
    }

    fun w(tag: String, message: String) {
        Log.w("$PREFIX:$tag", message)
    }

    fun e(tag: String, message: String, throwable: Throwable? = null) {
        Log.e("$PREFIX:$tag", message, throwable)
    }
}
