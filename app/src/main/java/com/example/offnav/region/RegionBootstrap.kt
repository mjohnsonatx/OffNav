package com.example.offnav.region

/** Resolves exactly one [RegionSnapshot] at cold start, then sweeps everything it isn't. */
class RegionBootstrap(private val store: RegionStore) {

    fun resolve(): RegionSnapshot {
        store.ensureDirs()
        val pointer = store.readPointer()
        val snapshot: RegionSnapshot =
            if (pointer == RegionSnapshot.BuiltIn.pointerValue) RegionSnapshot.BuiltIn
            else store.readSnapshot(pointer) ?: RegionSnapshot.BuiltIn   // torn/missing => fall back

        // If we fell back, make the pointer agree so we don't retry forever.
        if (snapshot is RegionSnapshot.BuiltIn && pointer != snapshot.pointerValue) {
            runCatching { store.publishPointer(snapshot.pointerValue) }
        }

        runCatching { store.gc(snapshot) }
        return snapshot
    }
}