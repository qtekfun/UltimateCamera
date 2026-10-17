package com.qtekfun.ultimatecamera.feature.multicam.session

import android.content.Context
import com.qtekfun.ultimatecamera.core.net.control.ControlMessage
import com.qtekfun.ultimatecamera.core.net.control.ControlProtocol
import com.qtekfun.ultimatecamera.core.net.control.DeviceRole
import com.qtekfun.ultimatecamera.core.net.control.ErrorCode
import com.qtekfun.ultimatecamera.core.net.control.LocalIdentity
import com.qtekfun.ultimatecamera.core.net.control.Session
import com.qtekfun.ultimatecamera.core.net.discovery.NsdServiceDiscovery
import com.qtekfun.ultimatecamera.core.net.discovery.ServiceAdvert
import com.qtekfun.ultimatecamera.core.net.discovery.ServiceDiscovery
import com.qtekfun.ultimatecamera.core.net.pairing.Endpoint
import com.qtekfun.ultimatecamera.core.settings.MulticamRole
import com.qtekfun.ultimatecamera.core.settings.MulticamRoleRepository
import com.qtekfun.ultimatecamera.feature.camera.background.TakeMarker
import com.qtekfun.ultimatecamera.feature.multicam.pairing.PairedDevicesRepository
import com.qtekfun.ultimatecamera.feature.multicam.roles.MulticamRoleSource
import com.qtekfun.ultimatecamera.feature.multicam.roles.toDeviceRole
import java.io.Closeable
import java.io.File
import java.io.IOException
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** Roles were changed on this phone, by the user ([fromPeer] false) or by the other phone of the pair. */
data class RoleAssignment(val own: MulticamRole?, val peerId: String, val fromPeer: Boolean)

/** How choosing roles with a peer ended. */
sealed interface ChooseOutcome {
    data class Done(val own: MulticamRole?) : ChooseOutcome

    /** The other phone records: its role cannot change now. */
    data object PeerBusy : ChooseOutcome

    /** The other phone no longer knows this one: pair again. */
    data object NotPaired : ChooseOutcome

    /** The other phone did not answer (app closed, other network). */
    data object Unreachable : ChooseOutcome

    /** This phone records: its role cannot change now. */
    data object Busy : ChooseOutcome
}

/**
 * The multicamera session of this phone (D80): roles are chosen after pairing, on either phone, and the other phone
 * follows. While [start]ed (the app in the foreground) and with at least one paired phone, it listens on the session
 * socket ([SessionSocket]) and advertises it over DNS-SD; a request from a paired phone is applied with [SessionRoles]
 * and published in [assignments] (the app then shows the camera or the monitor). Choices made on this phone go through
 * [choose], which asks the other phone first and applies only what it accepted.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class MulticamSession(
    private val loadDevices: suspend () -> PairedDevicesRepository,
    private val roles: MulticamRoleRepository,
    private val deviceName: () -> String,
    private val newDiscovery: () -> ServiceDiscovery?,
    /** True while this phone records a take: its role then stays as it is. */
    private val recording: () -> Boolean,
    private val port: Int = SessionSocket.DEFAULT_PORT,
    /** Session port of the other phones (the same on every phone; tests on one machine need two). */
    private val dialPort: Int = port,
    private val io: kotlinx.coroutines.CoroutineDispatcher = Dispatchers.IO
) {
    private val lock = Mutex()
    private val _assignments = MutableSharedFlow<RoleAssignment>(extraBufferCapacity = EVENTS)
    private val _openControl = MutableStateFlow(false)
    private val _listening = MutableStateFlow(false)
    private var job: Job? = null

    /** Every role change, for navigation (camera screen or monitor). */
    val assignments: SharedFlow<RoleAssignment> = _assignments.asSharedFlow()

    /** Set when the other phone made this one a monitor or master: the multicamera screen opens its control page. */
    val openControl: StateFlow<Boolean> = _openControl.asStateFlow()

    /** True while the session socket is bound. */
    val listening: StateFlow<Boolean> = _listening.asStateFlow()

    fun consumeOpenControl() {
        _openControl.value = false
    }

    /** Listens while [scope] lives and this phone has paired phones; [stop] ends it. Idempotent. */
    @Synchronized
    fun start(scope: CoroutineScope) {
        if (job?.isActive == true) return
        job = scope.launch(io) {
            val repo = loadDevices()
            repo.devices.map { it.isNotEmpty() }.distinctUntilChanged().collectLatest { paired -> if (paired) listen(repo) }
        }
    }

    @Synchronized
    fun stop() {
        job?.cancel()
        job = null
    }

    @Suppress("TooGenericExceptionCaught") // Network boundary: the app keeps working without the session socket.
    private suspend fun listen(repo: PairedDevicesRepository) {
        var advert: Closeable? = null
        try {
            SessionSocket.serve(
                port = port,
                local = { identity(repo, null) },
                pairKey = repo::pairKey,
                onRoles = { session, host, request -> answer(repo, session, host, request) },
                onBound = { bound ->
                    _listening.value = true
                    advert = runCatching {
                        newDiscovery()?.advertise(ServiceAdvert(repo.ownId, deviceName(), DeviceRole.MONITOR, bound, ControlProtocol.VERSION))
                    }.getOrNull()
                }
            )
        } catch (e: CancellationException) {
            throw e
        } catch (_: IOException) {
            // Port taken (another build of the app) or no network: choices on the other phone answer "unreachable".
        } catch (_: RuntimeException) {
            // Same: never take the app down for the session socket.
        } finally {
            _listening.value = false
            advert?.let { runCatching { it.close() } }
        }
    }

    /** On the session socket the handshake role means nothing (the roles travel in the request); both sides accept any. */
    private fun identity(repo: PairedDevicesRepository, own: MulticamRole?): LocalIdentity =
        LocalIdentity(repo.ownId, deviceName(), own?.toDeviceRole() ?: SessionRoles.NEUTRAL)

    /** A request from a paired phone: accepted (applied, then acknowledged) or refused. */
    private suspend fun answer(repo: PairedDevicesRepository, session: Session, host: String, request: ControlMessage.Roles): ControlMessage = lock.withLock {
        val own = roles.multicamRole.first()
        val plan = SessionRoles.accept(session.peerId, request.sender, request.receiver, own, repo.storedRoles(), recording())
            .getOrElse { error ->
                val code = if ((error as? RoleException)?.reason == RoleRefusal.BUSY) ErrorCode.BUSY else ErrorCode.INVALID_ARGUMENT
                return@withLock ControlMessage.Error(request.id, code, error.message)
            }
        // Where it reached us is where to dial it next time (its control port; the session port is derived).
        withContext(io) { repo.touch(session.peerId, listOf(Endpoint(host, ControlProtocol.DEFAULT_PORT))) }
        apply(repo, plan)
        if (plan.own != null && plan.own != MulticamRole.CAMERA) _openControl.value = true
        _assignments.tryEmit(RoleAssignment(plan.own, session.peerId, fromPeer = true))
        ControlMessage.Ack(request.id)
    }

    /**
     * This phone takes [choice] with [peerId]; [hosts] are the addresses to try first (discovered now). The other phone
     * is asked first; nothing changes here unless it agreed, except ending a session, which also ends here when the
     * other phone cannot be reached.
     */
    suspend fun choose(peerId: String, choice: RoleChoice, hosts: List<String> = emptyList()): ChooseOutcome = lock.withLock {
        val repo = loadDevices()
        val own = roles.multicamRole.first()
        val plan = SessionRoles.plan(choice, peerId, own, repo.storedRoles())
        if (recording() && plan.own != own) return@withLock ChooseOutcome.Busy
        val key = repo.pairKey(peerId) ?: return@withLock ChooseOutcome.NotPaired
        val stored = repo.devices.value.firstOrNull { it.id == peerId }?.endpoints.orEmpty().map { it.host }
        val request = ControlMessage.Roles(UUID.randomUUID().toString(), plan.sender, plan.receiver)
        val result = withContext(io) { SessionSocket.send(identity(repo, plan.own), peerId, key, hosts + stored, request, dialPort) }
        val outcome = when (result) {
            SessionSocket.SendResult.Ok -> null
            SessionSocket.SendResult.NotPaired -> ChooseOutcome.NotPaired
            SessionSocket.SendResult.Unreachable -> ChooseOutcome.Unreachable
            is SessionSocket.SendResult.Refused -> if (result.code == ErrorCode.BUSY) ChooseOutcome.PeerBusy else ChooseOutcome.Unreachable
        }
        if (outcome != null && choice != RoleChoice.END) return@withLock outcome
        apply(repo, plan)
        _assignments.tryEmit(RoleAssignment(plan.own, peerId, fromPeer = false))
        ChooseOutcome.Done(plan.own)
    }

    private suspend fun apply(repo: PairedDevicesRepository, plan: RolePlan) {
        if (plan.peers.isNotEmpty()) withContext(io) { repo.setRoles(plan.peers) }
        roles.setMulticamRole(plan.own)
    }

    companion object {
        private const val EVENTS = 8

        @Volatile private var instance: MulticamSession? = null

        /** The process-wide session (the app starts it with the activity). */
        fun getInstance(context: Context, roles: MulticamRoleRepository): MulticamSession = instance ?: synchronized(this) {
            instance ?: run {
                val app = context.applicationContext
                val marker = File(app.filesDir, TakeMarker.FILE_NAME)
                MulticamSession(
                    loadDevices = { withContext(Dispatchers.IO) { PairedDevicesRepository.getInstance(app) } },
                    roles = roles,
                    deviceName = { MulticamRoleSource.deviceName(app) },
                    newDiscovery = { NsdServiceDiscovery(app) },
                    recording = marker::exists
                ).also { instance = it }
            }
        }
    }
}
