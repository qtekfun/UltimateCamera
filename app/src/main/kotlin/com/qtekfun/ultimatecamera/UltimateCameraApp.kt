package com.qtekfun.ultimatecamera

import android.app.Application
import com.qtekfun.ultimatecamera.core.camera.DiagnosticLog
import com.qtekfun.ultimatecamera.core.storage.crash.CrashLog
import com.qtekfun.ultimatecamera.core.storage.crash.CrashRecorder
import com.qtekfun.ultimatecamera.core.storage.crash.CrashReports
import java.io.File

/**
 * Installs the crash recorder before anything else runs (D77): an uncaught exception on any thread is written to
 * `filesDir/crashes` (last 5, 64 KiB each, no personal data) and then handed to the platform handler as before. The
 * owner shares it from Diagnostics › Crash reports. The optional diagnostic log (D113) starts here too when it was left on.
 */
class UltimateCameraApp : Application() {
    override fun onCreate() {
        CrashRecorder.install(CrashLog(File(filesDir, CrashLog.DIR_NAME)), CrashReports.environment(BuildConfig.VERSION_NAME))
        DiagnosticLog.init(File(filesDir, DIAGNOSTICS_DIR))
        super.onCreate()
    }
}

/** App-private folder of the diagnostic log; Settings shares a copy of it from the cache. */
const val DIAGNOSTICS_DIR = "diagnostics"
