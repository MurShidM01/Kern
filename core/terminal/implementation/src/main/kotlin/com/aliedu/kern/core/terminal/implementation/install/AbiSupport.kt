package com.aliedu.kern.core.terminal.implementation.install

import javax.inject.Inject
import javax.inject.Singleton

/**
 * Device ABI reporting for the environment setup flow (PRD 12.3 CHECK_DEVICE_ABI, 12.4). The ABI list
 * is injected so the class stays unit-testable off-device.
 */
@Singleton
class AbiSupport @Inject constructor(private val abis: List<String>) {

    val primaryAbi: String
        get() = abis.firstOrNull() ?: "unknown"

    /** ABIs for which a bundle exists at all. */
    fun deviceSupported(): Boolean = primaryAbi in SUPPORTED_ABIS

    /**
     * ABIs on which the bundled proot runtime executes. Kern vendors proot for **arm64-v8a** (real
     * devices) and **x86_64** (emulators); any other ABI falls back to the built-in device shell.
     */
    fun runtimeSupported(): Boolean = primaryAbi in SUPPORTED_ABIS

    companion object {
        /** ABIs Kern publishes both an Alpine rootfs and a proot launcher for. */
        val SUPPORTED_ABIS = setOf("arm64-v8a", "x86_64")
    }
}
