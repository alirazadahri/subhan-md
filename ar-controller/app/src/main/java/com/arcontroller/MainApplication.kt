package com.arcontroller

import android.app.Application
import android.os.Build
import java.io.File

class MainApplication : Application() {

    override fun onCreate() {
        super.onCreate()
        // The crash screen runs in its own ":crash" process; don't install the handler there.
        if (processName().endsWith(":crash")) return
        CrashReporter.install(this)
    }

    private fun processName(): String =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) getProcessName()
        else runCatching { File("/proc/self/cmdline").readText().trim('\u0000', ' ') }.getOrDefault("")
}
