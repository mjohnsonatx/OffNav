package com.example.offnav.region

class RegionBootstrap(private val store: RegionStore) {

    fun resolve(): RegionSelection {
        store.ensureDirs()
        val requested = store.readSelection()
        val resolved = requested.mapNotNull { pointer ->
            if (pointer == RegionSnapshot.BuiltIn.pointerValue) RegionSnapshot.BuiltIn
            else store.readSnapshot(pointer)
        }
            .distinctBy { it.regionId }
            .ifEmpty { listOf(RegionSnapshot.BuiltIn) }
        val selection = RegionSelection(resolved)

        if (requested != selection.snapshots.map { it.pointerValue }) {
            runCatching { store.publishSelection(selection.snapshots.map { it.pointerValue }) }
        }
        runCatching { store.pruneOrphans() }
        runCatching { store.pruneSuperseded(selection) }
        return selection
    }
}
