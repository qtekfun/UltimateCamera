package com.qtekfun.ultimatecamera.feature.settings

import com.qtekfun.ultimatecamera.core.settings.AppSettings
import com.qtekfun.ultimatecamera.core.settings.CaptureModePrefs
import com.qtekfun.ultimatecamera.core.settings.CaptureModeRepository
import com.qtekfun.ultimatecamera.core.settings.ControlOption
import com.qtekfun.ultimatecamera.core.settings.FeatureGroup
import com.qtekfun.ultimatecamera.core.settings.HdrPrefs
import com.qtekfun.ultimatecamera.core.settings.HdrRepository
import com.qtekfun.ultimatecamera.core.settings.LookSlot
import com.qtekfun.ultimatecamera.core.settings.MulticamPrefs
import com.qtekfun.ultimatecamera.core.settings.MulticamRepository
import com.qtekfun.ultimatecamera.core.settings.PhotoHdrDefault
import com.qtekfun.ultimatecamera.core.settings.PhotoHdrRepository
import com.qtekfun.ultimatecamera.core.settings.SecondCameraRepository
import com.qtekfun.ultimatecamera.core.settings.SettingsRepository
import com.qtekfun.ultimatecamera.core.settings.ShutterMode
import com.qtekfun.ultimatecamera.core.settings.StartMode
import com.qtekfun.ultimatecamera.core.settings.TimecodeMode
import com.qtekfun.ultimatecamera.core.storage.OutputFile
import com.qtekfun.ultimatecamera.core.storage.RecordingLocation
import com.qtekfun.ultimatecamera.core.storage.SafTree
import com.qtekfun.ultimatecamera.core.storage.StorageBackend
import com.qtekfun.ultimatecamera.core.storage.VolumeInfo
import com.qtekfun.ultimatecamera.core.storage.VolumeKind
import java.io.FileDescriptor
import java.io.IOException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update

/** In-memory settings with every optional section the DataStore repository has. */
class FakeSettings :
    SettingsRepository,
    CaptureModeRepository,
    HdrRepository,
    MulticamRepository,
    SecondCameraRepository,
    PhotoHdrRepository {
    override val settings = MutableStateFlow(AppSettings.DEFAULT)
    override val captureModePrefs = MutableStateFlow(CaptureModePrefs())
    override val hdrPrefs = MutableStateFlow(HdrPrefs())
    override val multicamPrefs = MutableStateFlow(MulticamPrefs())
    override val secondCameraPair = MutableStateFlow<String?>(null)
    override val photoHdrDefault = MutableStateFlow(PhotoHdrDefault.AUTO)
    var resets = 0
    var autoConfigOffered = false

    override suspend fun setAutoConfigOffered(offered: Boolean) {
        autoConfigOffered = offered
    }

    override suspend fun setFeatureGroupEnabled(group: FeatureGroup, enabled: Boolean) = settings.update {
        it.copy(enabledGroups = if (enabled) it.enabledGroups + group else it.enabledGroups - group)
    }

    override suspend fun setMaxBrightness(enabled: Boolean) = settings.update { it.copy(maxBrightness = enabled) }

    override suspend fun setControlOption(option: ControlOption, enabled: Boolean) = settings.update {
        when (option) {
            ControlOption.VOLUME_KEYS_RECORD -> it.copy(volumeKeysRecord = enabled)
            ControlOption.ALERT_VIBRATION -> it.copy(alertVibration = enabled)
            ControlOption.BACKGROUND_RECORDING -> it.copy(backgroundRecording = enabled)
            ControlOption.THERMAL_STEP_DOWN -> it.copy(thermalStepDown = enabled)
        }
    }

    override suspend fun setRecordingTreeUri(uri: String?) = settings.update { it.copy(recordingTreeUri = uri) }

    override suspend fun setLook(slot: LookSlot, token: String?) = settings.update {
        if (slot == LookSlot.RECORDING) it.copy(recordingLook = token) else it.copy(monitorLook = token)
    }

    override suspend fun setRecordingLocationMode(mode: String?) = settings.update { it.copy(recordingLocationMode = mode) }

    override suspend fun setAudioInputKey(key: String?) = settings.update { it.copy(audioInputKey = key) }

    override suspend fun setAudioGainDb(gainDb: Float) = settings.update { it.copy(audioGainDb = gainDb) }

    override suspend fun setSeparateWav(enabled: Boolean) = settings.update { it.copy(separateWav = enabled) }

    override suspend fun resetToDefaults() {
        resets++
        settings.value = AppSettings.DEFAULT
    }

    override suspend fun setStartMode(mode: StartMode) = captureModePrefs.update { it.copy(startMode = mode) }

    override suspend fun setLastMode(mode: ShutterMode) = captureModePrefs.update { it.copy(lastMode = mode) }

    override suspend fun setTimecodeMode(mode: TimecodeMode) = captureModePrefs.update { it.copy(timecode = mode) }

    override suspend fun setHdrPrefs(prefs: HdrPrefs) {
        hdrPrefs.value = prefs
    }

    override suspend fun setMulticamPrefs(prefs: MulticamPrefs) {
        multicamPrefs.value = prefs
    }

    override suspend fun setSecondCameraPair(pair: String?) {
        secondCameraPair.value = pair
    }

    override suspend fun setPhotoHdrDefault(mode: PhotoHdrDefault) {
        photoHdrDefault.value = mode
    }
}

/** In-memory storage: the phone plus any drives and picked folders the test adds. */
class FakeStorage : StorageBackend {
    val grants = mutableSetOf<String>()
    val offeredGrants = mutableSetOf<String>()
    val volumes = mutableMapOf(SafTree.PRIMARY_VOLUME_ID to VolumeInfo(SafTree.PRIMARY_VOLUME_ID, VolumeKind.INTERNAL, "Internal", true))
    val existing = mutableSetOf<RecordingLocation>(RecordingLocation.Default)
    val free = mutableMapOf<RecordingLocation, Long?>()
    val removable = mutableListOf<String>()
    var failCreate = false
    val written = mutableMapOf<String, Long>()

    override fun hasPersistedPermission(treeUri: String) = treeUri in grants

    override fun takePersistedPermission(treeUri: String) = (treeUri in offeredGrants).also { if (it) grants += treeUri }

    override fun releasePersistedPermission(treeUri: String) {
        grants -= treeUri
    }

    override fun persistedTreeUris() = grants.toList()

    override fun removableVolumeNames() = removable.toList()

    override fun volume(volumeId: String) = volumes[volumeId]

    override fun folderExists(location: RecordingLocation) = location in existing

    override fun freeBytes(location: RecordingLocation): Long? = if (location in free) free[location] else GIB

    override fun listFileNames(location: RecordingLocation) = emptySet<String>()

    override fun createFile(location: RecordingLocation, displayName: String, mimeType: String, temporary: Boolean): OutputFile {
        if (failCreate) throw IOException("cannot create $displayName")
        written[displayName] = 0
        return object : OutputFile {
            override val uri = "fake://$displayName"
            override val fileDescriptor = FileDescriptor()

            override fun write(buffer: ByteArray, offset: Int, length: Int) {
                written[displayName] = written.getValue(displayName) + length
            }

            override fun sync() = Unit

            override fun close() = Unit

            override fun delete() {
                written.remove(displayName)
            }
        }
    }

    override fun deleteIfExists(location: RecordingLocation, displayName: String) {
        written.remove(displayName)
    }

    fun addDrive(name: String, freeBytes: Long?): RecordingLocation.Volume {
        volumes[name] = VolumeInfo(name, VolumeKind.REMOVABLE, "USB drive $name", true)
        removable += name
        return RecordingLocation.Volume(name).also {
            existing += it
            free[it] = freeBytes
        }
    }

    fun offerFolder(treeUri: String, volumeId: String): RecordingLocation.Folder {
        volumes[volumeId] = VolumeInfo(volumeId, VolumeKind.REMOVABLE, "USB drive", true)
        return RecordingLocation.Folder(treeUri).also {
            existing += it
            offeredGrants += treeUri
        }
    }

    companion object {
        const val GIB = 1024L * 1024 * 1024
    }
}
