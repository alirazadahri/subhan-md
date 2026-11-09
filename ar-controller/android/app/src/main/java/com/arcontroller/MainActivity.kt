package com.arcontroller

import android.content.Intent
import android.os.Bundle
import com.facebook.react.ReactActivity
import com.facebook.react.ReactActivityDelegate
import com.facebook.react.defaults.DefaultNewArchitectureEntryPoint.fabricEnabled
import com.facebook.react.defaults.DefaultReactActivityDelegate

class MainActivity : ReactActivity() {

  override fun getMainComponentName(): String = "ARController"

  override fun createReactActivityDelegate(): ReactActivityDelegate =
      DefaultReactActivityDelegate(this, mainComponentName, fabricEnabled)

  override fun onCreate(savedInstanceState: Bundle?) {
    // If the previous run crashed (including native crashes the Java handler can't see), show the
    // report instead of starting React Native straight into the same crash.
    if (savedInstanceState == null && CrashReporter.captureLastExit(this)) {
      super.onCreate(null)
      startActivity(Intent(this, CrashActivity::class.java))
      finish()
      return
    }
    super.onCreate(savedInstanceState)
  }
}
