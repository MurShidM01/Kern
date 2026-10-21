package com.aliedu.kern.core.terminal.implementation.install

import java.io.File
import java.security.MessageDigest
import javax.inject.Inject
import javax.inject.Singleton

/** Streaming SHA-256 verification of a downloaded component (PRD 12.4). */
@Singleton
class ChecksumVerifier @Inject constructor() {

    fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().buffered().use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    fun verify(file: File, expectedHex: String): Boolean =
        file.exists() && sha256(file).equals(expectedHex.trim(), ignoreCase = true)
}
