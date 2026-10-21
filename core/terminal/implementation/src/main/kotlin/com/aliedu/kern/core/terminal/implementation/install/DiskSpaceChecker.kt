package com.aliedu.kern.core.terminal.implementation.install

/** Free-space checks before and after install (PRD 12.4). The provider is injectable for tests. */
class DiskSpaceChecker(private val freeBytesProvider: () -> Long) {

    fun freeBytes(): Long = freeBytesProvider()

    fun hasRoomFor(requiredBytes: Long, safetyMarginBytes: Long = 0): Boolean =
        freeBytes() >= requiredBytes + safetyMarginBytes
}
