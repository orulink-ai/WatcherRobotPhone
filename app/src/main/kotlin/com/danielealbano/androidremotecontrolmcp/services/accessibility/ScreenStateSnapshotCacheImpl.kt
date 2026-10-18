package com.danielealbano.androidremotecontrolmcp.services.accessibility

import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ScreenStateSnapshotCacheImpl
    @Inject
    constructor() : ScreenStateSnapshotCache {
        @Volatile
        private var snapshot: ScreenStateSnapshot? = null

        override fun store(snapshot: ScreenStateSnapshot) {
            this.snapshot = snapshot
        }

        override fun get(id: String): ScreenStateSnapshot? {
            val current = snapshot
            return if (current != null && current.id == id) current else null
        }

        override fun clear() {
            snapshot = null
        }
    }
