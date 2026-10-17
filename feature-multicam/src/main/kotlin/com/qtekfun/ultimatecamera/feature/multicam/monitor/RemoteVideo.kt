package com.qtekfun.ultimatecamera.feature.multicam.monitor

import android.content.Context
import android.os.VibrationEffect
import android.os.VibratorManager
import android.view.SurfaceHolder
import android.view.SurfaceView
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import com.qtekfun.ultimatecamera.core.net.monitor.MonitorLinkState
import com.qtekfun.ultimatecamera.core.net.monitor.MonitorReceiver
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/** Where the monitor video of one camera is. */
enum class VideoState { CONNECTING, LIVE, UNAVAILABLE }

/** Closing a receiver waits for libwebrtc threads; it must outlive the composition that drops it. */
private val videoCleanup = CoroutineScope(SupervisorJob() + Dispatchers.IO)

private const val VIDEO_RETRY_MS = 3_000L
private const val VIDEO_ASPECT = 16f / 9f

/**
 * The camera's WebRTC monitor stream (core-net [MonitorReceiver], rendered by libwebrtc into a SurfaceView) at 16:9,
 * centred. It redials every 3 s while [enabled] and reports its state; with no video the screen keeps the status
 * overlay, since control does not depend on the stream (D31).
 */
@Composable
fun RemoteVideo(host: String?, enabled: Boolean, onState: (VideoState) -> Unit, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val report by rememberUpdatedState(onState)
    var receiver by remember { mutableStateOf<MonitorReceiver?>(null) }
    var surface by remember { mutableStateOf<android.view.Surface?>(null) }

    LaunchedEffect(host, enabled) {
        if (host == null || !enabled) {
            report(VideoState.UNAVAILABLE)
            return@LaunchedEffect
        }
        while (true) {
            report(VideoState.CONNECTING)
            val r = openReceiver(context, host)
            if (r != null) {
                receiver = r
                surface?.let { r.setOutputSurface(it) }
                try {
                    if (r.start()) {
                        report(VideoState.LIVE)
                        r.state.first { it == MonitorLinkState.FAILED || it == MonitorLinkState.CLOSED }
                    }
                } finally {
                    receiver = null
                    videoCleanup.launch {
                        r.setOutputSurface(null)
                        r.close()
                    }
                }
            }
            report(VideoState.UNAVAILABLE)
            delay(VIDEO_RETRY_MS)
        }
    }

    Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        AndroidView(
            factory = { ctx ->
                SurfaceView(ctx).apply {
                    holder.addCallback(object : SurfaceHolder.Callback {
                        override fun surfaceCreated(holder: SurfaceHolder) {
                            surface = holder.surface
                            receiver?.setOutputSurface(holder.surface)
                        }

                        override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) = Unit

                        override fun surfaceDestroyed(holder: SurfaceHolder) {
                            surface = null
                            receiver?.setOutputSurface(null)
                        }
                    })
                }
            },
            modifier = Modifier.aspectRatio(VIDEO_ASPECT)
        )
    }
    DisposableEffect(Unit) { onDispose { receiver?.setOutputSurface(null) } }
}

/** libwebrtc may be missing on an odd device (native library): no video then, status only. */
private fun openReceiver(context: Context, host: String): MonitorReceiver? = try {
    MonitorReceiver(context.applicationContext, host, stampDecoder = null)
} catch (_: IllegalStateException) {
    null
} catch (_: UnsatisfiedLinkError) {
    null
}

/** One short vibration (a critical alert on a remote camera), like the camera's own (D52). */
fun vibrateOnce(context: Context) {
    context.getSystemService(
        VibratorManager::class.java
    )?.defaultVibrator?.vibrate(VibrationEffect.createOneShot(VIBRATION_MS, VibrationEffect.DEFAULT_AMPLITUDE))
}

private const val VIBRATION_MS = 250L
