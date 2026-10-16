package com.qtekfun.ultimatecamera

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.lifecycle.viewmodel.compose.viewModel
import com.qtekfun.ultimatecamera.feature.spike.AutorunRequest
import com.qtekfun.ultimatecamera.feature.spike.SpikeScreen
import com.qtekfun.ultimatecamera.feature.spike.SpikeTest
import com.qtekfun.ultimatecamera.feature.spike.SpikeViewModel
import com.qtekfun.ultimatecamera.ui.theme.UltimateCameraTheme

/** Phase 0: the app is the spike test bench. Later phases replace this screen with the camera. */
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        // Only a fresh launch through the DUMP-protected alias may autorun; a recreated activity must not start again.
        val autorun = if (savedInstanceState == null) autorunRequest(intent) else null
        setContent {
            UltimateCameraTheme {
                val vm = viewModel { SpikeViewModel(application, BuildConfig.VERSION_NAME) }
                SpikeScreen(vm, autorun)
            }
        }
    }

    private fun autorunRequest(intent: Intent): AutorunRequest? {
        if (intent.component?.className != "$packageName.SpikeAutomation" && intent.component?.className?.endsWith(".SpikeAutomation") != true) return null
        val tests = SpikeTest.parse(intent.getStringExtra("tests") ?: "caps,matrix,concurrent,audio")
        return AutorunRequest(
            tests = tests,
            seconds = intent.getIntExtra("seconds", 20).coerceIn(5, 600),
            sustainedSeconds = intent.getIntExtra("sustainedSeconds", 300).coerceIn(10, 3_600),
            netHost = intent.getStringExtra("netHost")
        )
    }
}
