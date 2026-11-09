package com.arcontroller

import android.app.Application
import android.os.Build
import com.facebook.react.PackageList
import com.facebook.react.ReactApplication
import com.facebook.react.ReactHost
import com.facebook.react.ReactNativeApplicationEntryPoint.loadReactNative
import com.facebook.react.defaults.DefaultReactHost.getDefaultReactHost
import java.io.File

class MainApplication : Application(), ReactApplication {

  override val reactHost: ReactHost by lazy {
    getDefaultReactHost(
      context = applicationContext,
      packageList =
        PackageList(this).packages.apply {
          add(ARControllerPackage())
        },
    )
  }

  override fun onCreate() {
    super.onCreate()
    // The crash screen runs in its own ":crash" process and must not boot React Native.
    if (processName().endsWith(":crash")) return
    CrashReporter.install(this)
    loadReactNative(this)
  }

  private fun processName(): String =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) getProcessName()
    else runCatching { File("/proc/self/cmdline").readText().trim('\u0000', ' ') }.getOrDefault("")
}
