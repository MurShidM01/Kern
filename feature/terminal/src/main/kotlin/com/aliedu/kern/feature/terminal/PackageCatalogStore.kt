package com.aliedu.kern.feature.terminal

import javax.inject.Inject
import javax.inject.Singleton

/**
 * Process-wide cache of the last package catalog.
 *
 * Listing the apt index prints ~60k names through the PTY and takes tens of seconds, and
 * [PackageManagerViewModel] is recreated every time the screen is opened. Keeping the result here
 * means the list is on screen instantly on the next visit while a fresh listing runs in the
 * background, instead of showing an empty screen and re-paying the cost.
 *
 * The catalog only changes when the index is updated, so it is refreshed by pull-to-refresh, the
 * Update action, and after an install/removal (which only touches [installed]).
 */
@Singleton
class PackageCatalogStore @Inject constructor() {

    @Volatile
    var available: List<String> = emptyList()
        private set

    @Volatile
    var installed: List<String> = emptyList()
        private set

    /** True once a full catalog has been listed in this process. */
    val isLoaded: Boolean get() = available.isNotEmpty()

    fun putCatalog(available: List<String>, installed: List<String>) {
        if (available.isNotEmpty()) this.available = available
        this.installed = installed
    }

    fun putInstalled(installed: List<String>) {
        this.installed = installed
    }

    fun clear() {
        available = emptyList()
        installed = emptyList()
    }
}
