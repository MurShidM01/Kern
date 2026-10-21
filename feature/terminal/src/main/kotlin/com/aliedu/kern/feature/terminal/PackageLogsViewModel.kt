package com.aliedu.kern.feature.terminal

import androidx.lifecycle.ViewModel
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.StateFlow
import javax.inject.Inject

/**
 * Backs [PackageLogsScreen]. It only reads the shared [PackageLogStore], so the log keeps streaming
 * while this screen is open — even if the install that is producing it was started from the package
 * list, which stays on the back stack.
 */
@HiltViewModel
class PackageLogsViewModel @Inject constructor(
    private val logStore: PackageLogStore
) : ViewModel() {

    val lines: StateFlow<List<String>> = logStore.lines

    fun clear() = logStore.clear()
}
