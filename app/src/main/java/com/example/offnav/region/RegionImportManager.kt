package com.example.offnav.region

import android.content.Context
import android.net.Uri
import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

sealed interface ImportState {
    data object Idle : ImportState
    data object Reading : ImportState
    data class Copying(val copiedBytes: Long, val totalBytes: Long) : ImportState {
        val fraction: Float get() = if (totalBytes > 0) (copiedBytes.toFloat() / totalBytes).coerceIn(0f, 1f) else 0f
    }
    data object Validating : ImportState
    /** Published only after the cold-start selection update. */
    data class RestartRequired(val displayName: String, val version: String) : ImportState
    data class Failed(val message: String) : ImportState
}

class RegionImportManager(
    private val context: Context,
    private val store: RegionStore,
    private val scope: CoroutineScope,
) {
    private val _state = MutableStateFlow<ImportState>(ImportState.Idle)
    val state: StateFlow<ImportState> = _state.asStateFlow()

    private var job: Job? = null

    val isBusy: Boolean
        get() = _state.value.let { it is ImportState.Reading || it is ImportState.Copying || it is ImportState.Validating }

    fun import(uri: Uri) {
        if (job?.isActive == true) return
        job = scope.launch(Dispatchers.IO) {
            _state.value = ImportState.Reading
            try {
                var lastEmit = 0L
                val snapshot = RegionImporter(context, store).import(uri) { copied, total ->
                    // throttle UI churn; the last frame is forced by Validating
                    if (copied - lastEmit >= PROGRESS_STEP || copied == total) {
                        lastEmit = copied
                        _state.value = ImportState.Copying(copied, total)
                    }
                    if (copied == total) _state.value = ImportState.Validating
                }
                _state.value = ImportState.RestartRequired(snapshot.displayName, snapshot.version)
            } catch (c: CancellationException) {
                _state.value = ImportState.Idle
                throw c
            } catch (t: Throwable) {
                Log.e(TAG, "Region import failed", t)
                _state.value = ImportState.Failed(
                    (t as? RegionImportException)?.userMessage ?: "Region import failed"
                )
            }
        }
    }

    fun cancel() { job?.cancel() }

    fun acknowledge() {
        if (!isBusy) _state.value = ImportState.Idle
    }

    private companion object {
        const val TAG = "RegionImportManager"
        const val PROGRESS_STEP = 4L * 1024 * 1024
    }
}
