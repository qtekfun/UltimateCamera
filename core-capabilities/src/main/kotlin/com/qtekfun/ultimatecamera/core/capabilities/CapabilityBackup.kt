package com.qtekfun.ultimatecamera.core.capabilities

import java.io.File

/**
 * The capability results in a backup (D65). They describe one phone on one OS build, so an import keeps them only when
 * they were measured on this very build (same [DeviceIdentity.fingerprint]); anywhere else the phone measures itself.
 */
object CapabilityBackup {
    /** Why [restore] kept or skipped the backed-up catalogue. */
    enum class Outcome { RESTORED, OTHER_DEVICE, UNREADABLE }

    /**
     * Saves the catalogue in [candidate] (a staging file, possibly moved aside when unreadable) into [store] if it is a
     * readable catalogue of this schema measured on [identity]'s build.
     */
    fun restore(candidate: File, store: CapabilityStore, identity: DeviceIdentity): Outcome {
        val loaded = CapabilityStore(candidate).load() as? StoreLoad.Loaded ?: return Outcome.UNREADABLE
        val capabilities = loaded.capabilities
        if (!sameBuild(capabilities.identity, identity)) return Outcome.OTHER_DEVICE
        store.save(capabilities)
        return Outcome.RESTORED
    }

    /** Same phone and build; an empty fingerprint (seeds, unknown) never matches. */
    fun sameBuild(stored: DeviceIdentity, current: DeviceIdentity): Boolean = stored.fingerprint.isNotEmpty() &&
        stored.fingerprint == current.fingerprint &&
        stored.manufacturer == current.manufacturer &&
        stored.model == current.model &&
        stored.device == current.device
}
