package com.qtekfun.ultimatecamera

import android.content.Intent
import android.os.Bundle
import android.view.KeyEvent
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.qtekfun.ultimatecamera.core.net.NetworkLink
import com.qtekfun.ultimatecamera.core.net.role.RoleSources
import com.qtekfun.ultimatecamera.core.settings.AppSettings
import com.qtekfun.ultimatecamera.core.settings.DataStoreSettingsRepository
import com.qtekfun.ultimatecamera.core.settings.MulticamRoleRepository
import com.qtekfun.ultimatecamera.feature.camera.AutoConfigScreen
import com.qtekfun.ultimatecamera.feature.camera.AutoConfigViewModel
import com.qtekfun.ultimatecamera.feature.camera.CameraScreen
import com.qtekfun.ultimatecamera.feature.camera.CameraViewModel
import com.qtekfun.ultimatecamera.feature.camera.VolumeKeys
import com.qtekfun.ultimatecamera.feature.multicam.roles.MulticamRoleSource
import com.qtekfun.ultimatecamera.feature.multicam.roles.MulticamScreen
import com.qtekfun.ultimatecamera.feature.multicam.roles.MulticamViewModel
import com.qtekfun.ultimatecamera.feature.settings.SettingsScreen
import com.qtekfun.ultimatecamera.feature.settings.SettingsViewModel
import com.qtekfun.ultimatecamera.feature.spike.AutorunRequest
import com.qtekfun.ultimatecamera.feature.spike.SpikeScreen
import com.qtekfun.ultimatecamera.feature.spike.SpikeTest
import com.qtekfun.ultimatecamera.feature.spike.SpikeViewModel
import com.qtekfun.ultimatecamera.ui.AppRoot
import com.qtekfun.ultimatecamera.ui.Destination
import com.qtekfun.ultimatecamera.ui.theme.UltimateCameraTheme
import kotlinx.coroutines.flow.first

/** Hosts [AppRoot]: the camera is the main screen; the Phase 0 bench stays under Diagnostics. */
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        // Debug builds only: `am start --ei forceOrientation <ActivityInfo.SCREEN_ORIENTATION_*>` pins the orientation, so
        // every display rotation can be tested over adb without turning the phone (the activity follows the sensor).
        if (BuildConfig.DEBUG && intent.hasExtra(EXTRA_FORCE_ORIENTATION)) {
            requestedOrientation = intent.getIntExtra(EXTRA_FORCE_ORIENTATION, requestedOrientation)
        }
        // Only a fresh launch through the DUMP-protected alias may autorun; a recreated activity must not start again.
        val autorun = if (savedInstanceState == null) autorunRequest(intent) else null
        val repository = DataStoreSettingsRepository.getInstance(this)
        // The camera screen reads the multicamera role, pairing code and sealed pair keys from here (D55, D56).
        RoleSources.current = MulticamRoleSource(applicationContext, repository as MulticamRoleRepository)
        setContent {
            UltimateCameraTheme {
                val settings by repository.settings.collectAsStateWithLifecycle(initialValue = AppSettings.DEFAULT)
                // Decided from what is on disk (not the initial default), and never on an automation launch.
                val offerAutoConfig by produceState(false) { value = autorun == null && !repository.settings.first().autoConfigOffered }
                // The automation alias goes straight to the bench; a normal launch opens the camera.
                AppRoot(
                    start = if (autorun != null) Destination.DIAGNOSTICS else Destination.CAMERA,
                    maxBrightness = settings.maxBrightness,
                    camera = { open ->
                        CameraScreen(
                            viewModel { CameraViewModel(application, repository) },
                            onOpenSettings = { open(Destination.SETTINGS) },
                            onOpenDiagnostics = { open(Destination.DIAGNOSTICS) },
                            onOpenMulticam = { open(Destination.MULTICAM) }
                        )
                    },
                    settings = { open ->
                        SettingsScreen(viewModel { SettingsViewModel(application, repository) }, onOpenAutoConfig = { open(Destination.AUTOCONFIG) })
                    },
                    diagnostics = {
                        val vm = viewModel { SpikeViewModel(application, BuildConfig.VERSION_NAME) }
                        SpikeScreen(vm, autorun)
                    },
                    offerAutoConfig = offerAutoConfig,
                    autoConfig = { close -> AutoConfigScreen(viewModel { AutoConfigViewModel(application, repository) }, onDone = close) },
                    multicam = { close -> MulticamScreen(viewModel { MulticamViewModel(application, repository as MulticamRoleRepository) }, onClose = close) }
                )
            }
        }
    }

    /** Volume keys start/stop recording while the camera screen has installed its handler (D47). */
    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean = VolumeKeys.dispatch(event) || super.onKeyDown(keyCode, event)

    override fun onKeyUp(keyCode: Int, event: KeyEvent): Boolean = VolumeKeys.dispatch(event) || super.onKeyUp(keyCode, event)

    private fun autorunRequest(intent: Intent): AutorunRequest? {
        if (intent.component?.className != "$packageName.SpikeAutomation" && intent.component?.className?.endsWith(".SpikeAutomation") != true) return null
        val tests = SpikeTest.parse(intent.getStringExtra("tests") ?: "caps,matrix,concurrent,audio")
        return AutorunRequest(
            tests = tests,
            seconds = intent.getIntExtra("seconds", 20).coerceIn(5, 600),
            sustainedSeconds = intent.getIntExtra("sustainedSeconds", 300).coerceIn(10, 3_600),
            netHost = intent.getStringExtra("netHost"),
            netLink = when (intent.getStringExtra("netLink")) {
                "wifi" -> NetworkLink.WIFI_ROUTER
                "hotspot" -> NetworkLink.PHONE_HOTSPOT
                else -> null
            },
            tryUnadvertised = intent.getBooleanExtra("tryUnadvertised", false)
        )
    }

    private companion object {
        const val EXTRA_FORCE_ORIENTATION = "forceOrientation"
    }
}
