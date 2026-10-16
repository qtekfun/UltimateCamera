package com.qtekfun.ultimatecamera.core.audio

/** An input device as the source logic sees it; built from `AudioDeviceInfo` by the platform layer. */
data class AudioSource(val id: Int, val kind: AudioInputKind, val name: String = "")

/** Something the UI must tell the user about the audio source (REQ-AUD-002). */
sealed interface AudioSourceNotice {
    /** A device was connected during the take; the take keeps its source and the user may switch after it. */
    data class NewDeviceDuringTake(val device: AudioSource) : AudioSourceNotice

    /** The selected device disappeared during the take; recording goes on with whatever the system routes now. */
    data class SourceLostDuringTake(val device: AudioSource) : AudioSourceNotice

    /** The system records from another device than the one selected (preferred device not honoured). */
    data class RoutedElsewhere(val selected: AudioSource, val routedId: Int) : AudioSourceNotice

    /** The user's chosen device disappeared while idle; [fallback] was preselected instead. */
    data class SelectionReplaced(val lost: AudioSource, val fallback: AudioSource?) : AudioSourceNotice
}

/**
 * Source selection state (REQ-AUD-001, REQ-AUD-002), a pure value: every event returns the next state.
 *
 * - Idle: the best available device (USB > Bluetooth > wired > built-in, as [preferredOrder]) is preselected until the
 *   user picks one; a manual pick is kept while that device exists. A newly connected better device replaces an
 *   automatic preselection, never a manual one.
 * - Recording: the selection never changes. Connections, disconnections and routing differences only add notices.
 * - After the take: a lost source is replaced by the best available device (with a notice); notices stay until
 *   [dismiss]ed, so the user can still switch to a device that arrived during the take.
 */
data class AudioSourceSelection(
    val available: List<AudioSource> = emptyList(),
    val selected: AudioSource? = null,
    val chosenByUser: Boolean = false,
    val recording: Boolean = false,
    val notices: List<AudioSourceNotice> = emptyList()
) {
    /** The device list changed (AudioDeviceCallback). */
    fun onDevicesChanged(devices: List<AudioSource>): AudioSourceSelection {
        val ordered = devices.sortedBy { it.kind.priority } // Stable: system order within a kind.
        if (recording) {
            val added = ordered.filter { d -> d.id != selected?.id && available.none { it.id == d.id } }.map { AudioSourceNotice.NewDeviceDuringTake(it) }
            val current = selected
            val lost = if (current != null && ordered.none { it.id == current.id } && available.any { it.id == current.id }) {
                listOf(AudioSourceNotice.SourceLostDuringTake(current))
            } else {
                emptyList()
            }
            return copy(available = ordered, notices = notices + added + lost)
        }
        return copy(available = ordered).reselect()
    }

    /** The user picked a device in the selector. Ignored during a take (the selector is locked then). */
    fun select(id: Int): AudioSourceSelection {
        if (recording) return this
        val device = available.firstOrNull { it.id == id } ?: return this
        return copy(selected = device, chosenByUser = true)
    }

    fun onRecordingStarted(): AudioSourceSelection = copy(recording = true)

    fun onRecordingStopped(): AudioSourceSelection = copy(recording = false).reselect()

    /** The device AudioRecord really uses (`getRoutedDevice`, `OnRoutingChangedListener`) during a take. */
    fun onRouted(routedId: Int): AudioSourceSelection {
        val current = selected ?: return this
        if (!recording || routedId == current.id) return this
        val notice = AudioSourceNotice.RoutedElsewhere(current, routedId)
        return if (notice in notices) this else copy(notices = notices + notice)
    }

    fun dismiss(notice: AudioSourceNotice): AudioSourceSelection = copy(notices = notices - notice)

    private fun reselect(): AudioSourceSelection {
        val best = available.firstOrNull()
        val current = selected
        return when {
            current == null -> copy(selected = best, chosenByUser = false)
            available.none { it.id == current.id } -> copy(
                selected = best,
                chosenByUser = false,
                notices = if (chosenByUser) notices + AudioSourceNotice.SelectionReplaced(current, best) else notices
            )
            chosenByUser -> this
            else -> copy(selected = best)
        }
    }
}
