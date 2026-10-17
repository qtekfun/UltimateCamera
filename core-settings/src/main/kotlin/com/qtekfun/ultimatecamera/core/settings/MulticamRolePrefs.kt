package com.qtekfun.ultimatecamera.core.settings

import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.flow.Flow

/**
 * The role this phone plays in a multicamera setup (REQ-NET-003, D55). [key] is persisted: never rename it. No role
 * stored means a standalone camera (multicamera off).
 */
enum class MulticamRole(val key: String) {
    /** Records locally and obeys its paired monitors and master. */
    CAMERA("camera"),

    /** Shows one camera and starts/stops it. */
    MONITOR("monitor"),

    /** A monitor that controls several cameras at once. */
    MASTER("master");

    companion object {
        fun fromKey(key: String?): MulticamRole? = entries.firstOrNull { it.key == key }
    }
}

/** Implemented by [DataStoreSettingsRepository]; kept apart from [SettingsRepository] so fakes need not change. */
interface MulticamRoleRepository {
    /** The chosen role, or null when multicamera is off. */
    val multicamRole: Flow<MulticamRole?>

    suspend fun setMulticamRole(role: MulticamRole?)
}

/** Additive optional key: its absence means "off", so the schema version does not change. */
internal object MulticamRoleKeys {
    val ROLE = stringPreferencesKey("multicam_role")

    fun read(preferences: Preferences): MulticamRole? = MulticamRole.fromKey(preferences[ROLE])

    fun write(preferences: MutablePreferences, role: MulticamRole?) {
        if (role == null) preferences.remove(ROLE) else preferences[ROLE] = role.key
    }
}
