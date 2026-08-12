package com.example.offnav.region

import android.content.Context
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/** Everything the UI needs to know about one installed region. Primitives + value types only. */
data class RegionInfo(
    val installId: String,
    val regionId: String,
    val displayName: String,
    val version: String,
    val bounds: RegionBounds?,
    /** Null when the size could not be determined safely. */
    val installedBytes: Long?,
    /** Currently loaded by MapLibre / GraphHopper / SQLite in this process. */
    val isActive: Boolean,
    /** Selected by the cold-start pointer, whether or not it is already active. */
    val isSelectedForNextLaunch: Boolean,
) {
    val isPendingActivation: Boolean get() = isSelectedForNextLaunch != isActive
    val isPendingRemoval: Boolean get() = isActive && !isSelectedForNextLaunch
    val isBuiltIn: Boolean get() = installId == RegionSnapshot.BuiltIn.pointerValue
    val canDelete: Boolean get() = !isBuiltIn && !isActive && !isSelectedForNextLaunch
}

/**
 * Reads the metadata of every region on disk and labels it against the two pieces of
 * process state that matter: the immutable [active] snapshot, and the cold-start pointer.
 */
class RegionCatalog(
    context: Context,
    private val store: RegionStore,
    private val active: RegionSelection,
    private val scope: CoroutineScope,
) {
    private val filesDir = context.applicationContext.filesDir

    private val _regions = MutableStateFlow<List<RegionInfo>>(emptyList())
    val regions: StateFlow<List<RegionInfo>> = _regions.asStateFlow()

    /** True when a restart will change which region is loaded. */
    val pendingActivation: StateFlow<Boolean> get() = _pending.asStateFlow()
    private val _pending = MutableStateFlow(false)

    init { refresh() }

    fun refresh() {
        scope.launch(Dispatchers.IO) {
            val list = runCatching { scan() }.getOrElse {
                Log.e(TAG, "Region scan failed", it); emptyList()
            }
            _regions.value = list
            _pending.value = list.any { it.isPendingActivation }
        }
    }

    private fun scan(): List<RegionInfo> {
        val selectedIds = store.readSelection().toSet()
        val activeIds = active.pointerValues

        val builtIn = RegionSnapshot.BuiltIn.let { b ->
            RegionInfo(
                installId = b.pointerValue,
                regionId = b.regionId,
                displayName = b.displayName,
                version = b.version,
                bounds = b.bounds,
                installedBytes = builtInBytes(),
                isActive = b.pointerValue in activeIds,
                isSelectedForNextLaunch = b.pointerValue in selectedIds,
            )
        }

        val installed = store.listInstallIds().mapNotNull { id ->
            val d = store.readDescriptor(id) ?: return@mapNotNull null
            RegionInfo(
                installId = id,
                regionId = d.regionId,
                displayName = d.displayName,
                version = d.version,
                bounds = d.bounds,
                // descriptor value is authoritative; fall back to a bounded, symlink-safe walk
                installedBytes = d.installedBytes ?: safeDiskUsage(store.installDir(id)),
                isActive = id in activeIds,
                isSelectedForNextLaunch = id in selectedIds,
            )
        }

        return (listOf(builtIn) + installed)
            .sortedWith(compareByDescending<RegionInfo> { it.isActive }
                .thenByDescending { it.isPendingActivation }
                .thenBy { it.displayName.lowercase() }
                .thenBy { it.version })
    }

    /** The APK-seeded copies, if they have been materialised yet. */
    private fun builtInBytes(): Long? {
        val parts = listOf(
            File(filesDir, "region.mbtiles"),
            File(filesDir, "austin_places.db"),
            File(filesDir, "graphhopper"),
        ).filter { it.exists() }
        if (parts.isEmpty()) return null
        var total = 0L
        for (p in parts) total += (safeDiskUsage(p) ?: return null)
        return total
    }

    // ── mutations: pointer only; nothing on disk is touched ──────────────

    /** Adds or removes [installId] from the next cold start as one atomic selection change. */
    fun setSelected(installId: String, selected: Boolean, onResult: (Result<Unit>) -> Unit = {}) {
        scope.launch(Dispatchers.IO) {
            val result = runCatching {
                val targetRegionId = checkNotNull(store.regionIdFor(installId)) {
                    "That region is no longer installed"
                }
                val next = store.readSelection().toMutableList()
                if (selected) {
                    next.removeAll { pointer -> store.regionIdFor(pointer) == targetRegionId }
                    next += installId
                } else {
                    next.remove(installId)
                    check(next.isNotEmpty()) { "At least one offline region must remain loaded" }
                }
                store.publishSelection(next)
            }
            refresh()
            withContext(Dispatchers.Main) { onResult(result) }
        }
    }

    fun delete(installId: String, onResult: (Result<Unit>) -> Unit = {}) {
        scope.launch(Dispatchers.IO) {
            val result = runCatching {
                store.deleteInstall(
                    installId = installId,
                    selectedPointers = store.readSelection().toSet(),
                    activeSnapshotIds = active.pointerValues,
                )
            }
            refresh()
            withContext(Dispatchers.Main) { onResult(result) }
        }
    }

    private companion object { const val TAG = "RegionCatalog" }
}
