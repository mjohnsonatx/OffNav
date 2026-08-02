package com.example.offnav.region

class RegionBootstrap(private val store: RegionStore) {

    fun resolve(): RegionSnapshot {
        store.ensureDirs()
        val pointer = store.readPointer()
        val snapshot: RegionSnapshot =
            if (pointer == RegionSnapshot.BuiltIn.pointerValue) RegionSnapshot.BuiltIn
            else store.readSnapshot(pointer) ?: RegionSnapshot.BuiltIn

        if (snapshot is RegionSnapshot.BuiltIn && pointer != snapshot.pointerValue) {
            runCatching { store.publishPointer(snapshot.pointerValue) }   // pointer pointed at nothing
        }

        runCatching { store.pruneOrphans() }   // staging + torn dirs only; installed regions are retained
        return snapshot
    }
}