package com.arcontroller

import android.Manifest
import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.media.AudioManager
import android.net.Uri
import android.os.Build
import android.provider.AlarmClock
import android.provider.ContactsContract
import android.provider.Settings
import android.provider.MediaStore
import android.view.KeyEvent
import android.widget.Toast
import androidx.core.content.ContextCompat

/** Executes an [AICommand] on the device and returns the sentence AR should speak back. Main thread only. */
class CommandExecutor(private val ctx: Context, private val agentListener: ScreenAgent.Listener? = null) {

    /** Used when commands come from the app screen (no overlay to show progress). */
    private class ToastAgentListener(private val ctx: Context) : ScreenAgent.Listener {
        override fun onAgentProgress(text: String) = Toast.makeText(ctx, "AR: $text", Toast.LENGTH_SHORT).show()
        override fun onAgentDone(reply: String) = Toast.makeText(ctx, "AR: $reply", Toast.LENGTH_LONG).show()
    }

    private fun floatMessage(label: String, mode: FloatingAppLauncher.Mode): String? = when (mode) {
        FloatingAppLauncher.Mode.FREEFORM -> null
        FloatingAppLauncher.Mode.WEB -> "$label floating window mein"
        FloatingAppLauncher.Mode.NEEDS_FREEFORM ->
            "$label ko floating box mein kholne ke liye Freeform on karein (app > Floating windows)"
        else -> "$label open nahi ho saki"
    }

    fun floatMessageFor(label: String, mode: FloatingAppLauncher.Mode) = floatMessage(label, mode)

    fun execute(cmd: AICommand): String {
        val reply = cmd.reply
        return try {
            extraAction(cmd)?.let { return it }
            when (cmd.action) {
                "open_app" -> openApp(cmd.target, floating = false) ?: reply ?: "Opening ${cmd.target}"
                "float_app" -> openApp(cmd.target, floating = true) ?: reply ?: "${cmd.target} floating window mein"
                "float_website" -> {
                    FloatingAppLauncher.floatWebsite(ctx, cmd.text ?: cmd.target ?: "google.com")
                    reply ?: "Floating window mein khol diya"
                }
                "close_floating" -> {
                    FloatingWebWindow.closeAll()
                    reply ?: "Floating windows band"
                }
                "agent_task" -> {
                    val goal = cmd.text ?: cmd.target ?: ""
                    ScreenAgent.start(ctx, goal, agentListener ?: ToastAgentListener(ctx))
                    reply ?: "Theek hai, kar raha hoon…"
                }
                "call" -> call(cmd.target) ?: reply ?: "Calling ${cmd.target}"
                "send_sms" -> sms(cmd.target, cmd.text) ?: reply ?: "Message ready hai, send dabayein"
                "whatsapp_message" -> whatsapp(cmd.target, cmd.text) ?: reply ?: "WhatsApp message ready hai"
                "flashlight_on" -> torch(true) ?: reply ?: "Torch on"
                "flashlight_off" -> torch(false) ?: reply ?: "Torch off"
                "volume_up" -> volume(AudioManager.ADJUST_RAISE).let { reply ?: "Volume up" }
                "volume_down" -> volume(AudioManager.ADJUST_LOWER).let { reply ?: "Volume down" }
                "mute" -> volume(AudioManager.ADJUST_MUTE).let { reply ?: "Muted" }
                "home" -> global(AccessibilityService.GLOBAL_ACTION_HOME) ?: reply ?: "Home"
                "back" -> global(AccessibilityService.GLOBAL_ACTION_BACK) ?: reply ?: "Back"
                "recents" -> global(AccessibilityService.GLOBAL_ACTION_RECENTS) ?: reply ?: "Recent apps"
                "notifications" -> global(AccessibilityService.GLOBAL_ACTION_NOTIFICATIONS) ?: reply ?: "Notifications"
                "quick_settings" -> global(AccessibilityService.GLOBAL_ACTION_QUICK_SETTINGS) ?: reply ?: "Quick settings"
                "split_screen" -> global(AccessibilityService.GLOBAL_ACTION_TOGGLE_SPLIT_SCREEN) ?: reply ?: "Split screen"
                "lock_screen" -> if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                    global(AccessibilityService.GLOBAL_ACTION_LOCK_SCREEN) ?: reply ?: "Screen locked"
                } else "Lock ke liye Android 9 ya naya chahiye"
                "screenshot" -> if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                    global(AccessibilityService.GLOBAL_ACTION_TAKE_SCREENSHOT) ?: reply ?: "Screenshot le liya"
                } else "Screenshot ke liye Android 9 ya naya chahiye"
                "open_settings" -> openSettings(cmd.target).let { reply ?: "Settings khul gayi" }
                "web_search" -> view("https://www.google.com/search?q=" + Uri.encode(cmd.text ?: cmd.target ?: "")).let {
                    reply ?: "Searching"
                }
                "youtube_search" -> view("https://www.youtube.com/results?search_query=" + Uri.encode(cmd.text ?: cmd.target ?: "")).let {
                    reply ?: "YouTube par dhoond raha hoon"
                }
                "set_alarm" -> alarm(cmd.target) ?: reply ?: "Alarm set"
                "type_text" -> withA11y { it.typeText(cmd.text ?: "") }
                    ?.let { ok -> if (ok) reply ?: "Likh diya" else "Koi text box nahi mila" } ?: A11Y_MISSING
                "click" -> withA11y { it.clickByText(cmd.target ?: "") }
                    ?.let { ok -> if (ok) reply ?: "Click kar diya" else "\"${cmd.target}\" screen par nahi mila" } ?: A11Y_MISSING
                "scroll_down" -> withA11y { it.scroll(true) }?.let { reply ?: "Scrolled" } ?: A11Y_MISSING
                "scroll_up" -> withA11y { it.scroll(false) }?.let { reply ?: "Scrolled" } ?: A11Y_MISSING
                "read_screen" -> withA11y { it.screenText(400) }?.ifBlank { "Screen par koi text nahi" } ?: A11Y_MISSING
                else -> reply ?: "Ji?"
            }
        } catch (t: Throwable) {
            "Error: ${t.message}"
        }
    }

    // Helpers return null on success (caller then uses the AI's reply) or an error sentence.

    private fun openApp(name: String?, floating: Boolean): String? {
        if (name.isNullOrBlank()) return "Kaunsi app kholun?"
        val app = AppRepository.findByName(ctx, name) ?: return "\"$name\" app nahi mili"
        if (!floating) {
            val mode = FloatingAppLauncher.launchFullscreen(ctx, app.packageName)
            return if (mode == FloatingAppLauncher.Mode.FAILED) "${app.label} open nahi ho saki" else null
        }
        return floatMessage(app.label, FloatingAppLauncher.launchFloating(ctx, app.packageName, app.label))
    }

    private fun call(target: String?): String? {
        if (target.isNullOrBlank()) return "Kisko call karun?"
        val number = resolveNumber(target) ?: return "\"$target\" contacts mein nahi mila"
        val canCall = ContextCompat.checkSelfPermission(ctx, Manifest.permission.CALL_PHONE) == PackageManager.PERMISSION_GRANTED
        val action = if (canCall) Intent.ACTION_CALL else Intent.ACTION_DIAL
        ctx.startActivity(Intent(action, Uri.parse("tel:" + Uri.encode(number))).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        return null
    }

    private fun sms(target: String?, body: String?): String? {
        val number = target?.let(::resolveNumber) ?: return "\"$target\" contacts mein nahi mila"
        val i = Intent(Intent.ACTION_SENDTO, Uri.parse("smsto:" + Uri.encode(number)))
            .putExtra("sms_body", body ?: "")
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        ctx.startActivity(i)
        return null
    }

    private fun whatsapp(target: String?, body: String?): String? {
        val number = target?.let(::resolveNumber) ?: return "\"$target\" contacts mein nahi mila"
        // wa.me needs international format without "+", e.g. 923001234567. Pakistani local 03xx -> 923xx.
        var digits = number.filter { it.isDigit() }
        if (digits.startsWith("00")) digits = digits.drop(2)
        if (digits.startsWith("0")) digits = "92" + digits.drop(1)
        view("https://wa.me/$digits?text=" + Uri.encode(body ?: ""))
        return null
    }

    private fun resolveNumber(target: String): String? {
        val trimmed = target.trim()
        if (trimmed.count { it.isDigit() } >= 5 && trimmed.all { it.isDigit() || it in "+-() " }) return trimmed
        if (ContextCompat.checkSelfPermission(ctx, Manifest.permission.READ_CONTACTS) != PackageManager.PERMISSION_GRANTED) {
            return null
        }
        val projection = arrayOf(
            ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
            ContactsContract.CommonDataKinds.Phone.NUMBER,
        )
        ctx.contentResolver.query(
            ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
            projection,
            "${ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME} LIKE ?",
            arrayOf("%$trimmed%"),
            null,
        )?.use { c ->
            var best: String? = null
            while (c.moveToNext()) {
                val name = c.getString(0) ?: continue
                val num = c.getString(1) ?: continue
                if (name.equals(trimmed, ignoreCase = true)) return num
                if (best == null) best = num
            }
            return best
        }
        return null
    }

    private fun torch(on: Boolean): String? {
        val cm = ctx.getSystemService(Context.CAMERA_SERVICE) as CameraManager
        val id = cm.cameraIdList.firstOrNull {
            cm.getCameraCharacteristics(it).get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true
        } ?: return "Is phone mein flash nahi hai"
        cm.setTorchMode(id, on)
        torchOn = on
        return null
    }

    fun toggleTorch(): String = (torch(!torchOn) ?: if (torchOn) "Torch on" else "Torch off")

    private fun volume(direction: Int) {
        val am = ctx.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        am.adjustStreamVolume(AudioManager.STREAM_MUSIC, direction, AudioManager.FLAG_SHOW_UI)
    }

    private fun global(action: Int): String? {
        val svc = ARAccessibilityService.instance ?: return A11Y_MISSING
        return if (svc.global(action)) null else "Ye action is phone par support nahi"
    }

    private fun <T> withA11y(block: (ARAccessibilityService) -> T): T? = ARAccessibilityService.instance?.let(block)

    fun openSettings(target: String?) {
        val action = when (target?.lowercase()) {
            "wifi", "wi-fi" -> if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) Settings.Panel.ACTION_WIFI else Settings.ACTION_WIFI_SETTINGS
            "bluetooth" -> Settings.ACTION_BLUETOOTH_SETTINGS
            "location", "gps" -> Settings.ACTION_LOCATION_SOURCE_SETTINGS
            "display", "brightness" -> Settings.ACTION_DISPLAY_SETTINGS
            "sound", "volume" -> Settings.ACTION_SOUND_SETTINGS
            "battery" -> Settings.ACTION_BATTERY_SAVER_SETTINGS
            "apps" -> Settings.ACTION_APPLICATION_SETTINGS
            "data", "internet", "mobile data" ->
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) Settings.Panel.ACTION_INTERNET_CONNECTIVITY else Settings.ACTION_DATA_ROAMING_SETTINGS
            else -> Settings.ACTION_SETTINGS
        }
        ctx.startActivity(Intent(action).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    private fun alarm(time: String?): String? {
        val parts = time?.split(":")?.mapNotNull { it.trim().toIntOrNull() }
        if (parts.isNullOrEmpty()) return "Alarm ka time batayein"
        ctx.startActivity(
            Intent(AlarmClock.ACTION_SET_ALARM)
                .putExtra(AlarmClock.EXTRA_HOUR, parts[0])
                .putExtra(AlarmClock.EXTRA_MINUTES, parts.getOrElse(1) { 0 })
                .putExtra(AlarmClock.EXTRA_SKIP_UI, true)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
        return null
    }

    private fun view(url: String) {
        ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    /** Device functions added for the control panel; returns null when [cmd] isn't one of them. */
    private fun extraAction(cmd: AICommand): String? {
        val r = cmd.reply
        return when (cmd.action) {
            "torch_toggle" -> toggleTorch()
            "media_play_pause" -> media(KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE).let { r ?: "Play / pause" }
            "media_next" -> media(KeyEvent.KEYCODE_MEDIA_NEXT).let { r ?: "Agla gaana" }
            "media_previous" -> media(KeyEvent.KEYCODE_MEDIA_PREVIOUS).let { r ?: "Pichla gaana" }
            "brightness_up" -> brightness(+48) ?: r ?: "Brightness barha di"
            "brightness_down" -> brightness(-48) ?: r ?: "Brightness kam kar di"
            "brightness_max" -> brightness(255, absolute = true) ?: r ?: "Full brightness"
            "rotate_toggle" -> rotation() ?: r ?: "Auto-rotate badal diya"
            "dnd_toggle" -> dnd() ?: r ?: "Do Not Disturb badal diya"
            "ringer_cycle" -> ringerCycle()
            "silent" -> ringer(AudioManager.RINGER_MODE_SILENT) ?: r ?: "Silent mode"
            "vibrate" -> ringer(AudioManager.RINGER_MODE_VIBRATE) ?: r ?: "Vibrate mode"
            "ring" -> ringer(AudioManager.RINGER_MODE_NORMAL) ?: r ?: "Ringer on"
            "power_menu" -> global(AccessibilityService.GLOBAL_ACTION_POWER_DIALOG) ?: r ?: "Power menu"
            "battery" -> "Battery ${batteryPercent()}%" + if (isCharging()) " (charging)" else ""
            "time" -> java.text.SimpleDateFormat("h:mm a, EEEE d MMMM", java.util.Locale.getDefault()).format(java.util.Date())
            "open_camera" -> open(Intent(MediaStore.INTENT_ACTION_STILL_IMAGE_CAMERA)) ?: r ?: "Camera"
            "open_dialer" -> open(Intent(Intent.ACTION_DIAL)) ?: r ?: "Dialer"
            "open_messages" -> openCategory(Intent.CATEGORY_APP_MESSAGING) ?: r ?: "Messages"
            "open_contacts" -> openCategory(Intent.CATEGORY_APP_CONTACTS) ?: r ?: "Contacts"
            "open_calendar" -> openCategory(Intent.CATEGORY_APP_CALENDAR) ?: r ?: "Calendar"
            "open_calculator" -> openCategory(Intent.CATEGORY_APP_CALCULATOR)?.let { openApp("calculator", false) } ?: r ?: "Calculator"
            "open_gallery" -> openCategory(Intent.CATEGORY_APP_GALLERY) ?: r ?: "Gallery"
            "open_music" -> openCategory(Intent.CATEGORY_APP_MUSIC) ?: r ?: "Music"
            "open_maps" -> openCategory(Intent.CATEGORY_APP_MAPS) ?: r ?: "Maps"
            "open_email" -> openCategory(Intent.CATEGORY_APP_EMAIL) ?: r ?: "Email"
            "open_browser" -> openCategory(Intent.CATEGORY_APP_BROWSER) ?: r ?: "Browser"
            "open_clock" -> open(Intent(AlarmClock.ACTION_SHOW_ALARMS)) ?: r ?: "Clock"
            else -> null
        }
    }

    private fun media(code: Int) {
        val am = ctx.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        am.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, code))
        am.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_UP, code))
    }

    private fun needWriteSettings(): String? {
        if (Bridge.canWriteSettings(ctx)) return null
        open(Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS, Uri.parse("package:${ctx.packageName}")))
        return "Is ke liye \"Modify system settings\" allow karein"
    }

    private fun brightness(delta: Int, absolute: Boolean = false): String? {
        needWriteSettings()?.let { return it }
        val cr = ctx.contentResolver
        Settings.System.putInt(cr, Settings.System.SCREEN_BRIGHTNESS_MODE, Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL)
        val current = Settings.System.getInt(cr, Settings.System.SCREEN_BRIGHTNESS, 128)
        val value = (if (absolute) delta else current + delta).coerceIn(8, 255)
        Settings.System.putInt(cr, Settings.System.SCREEN_BRIGHTNESS, value)
        return null
    }

    private fun rotation(): String? {
        needWriteSettings()?.let { return it }
        val cr = ctx.contentResolver
        val on = Settings.System.getInt(cr, Settings.System.ACCELEROMETER_ROTATION, 0) == 1
        Settings.System.putInt(cr, Settings.System.ACCELEROMETER_ROTATION, if (on) 0 else 1)
        return if (on) "Auto-rotate off" else "Auto-rotate on"
    }

    private fun dnd(): String? {
        val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as android.app.NotificationManager
        if (!nm.isNotificationPolicyAccessGranted) {
            open(Intent(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS))
            return "Do Not Disturb ke liye access allow karein"
        }
        val on = nm.currentInterruptionFilter != android.app.NotificationManager.INTERRUPTION_FILTER_ALL
        nm.setInterruptionFilter(
            if (on) android.app.NotificationManager.INTERRUPTION_FILTER_ALL else android.app.NotificationManager.INTERRUPTION_FILTER_PRIORITY,
        )
        return if (on) "Do Not Disturb off" else "Do Not Disturb on"
    }

    private fun ringer(mode: Int): String? {
        val am = ctx.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        return try {
            am.ringerMode = mode
            null
        } catch (_: SecurityException) {
            open(Intent(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS))
            "Silent mode ke liye Do Not Disturb access allow karein"
        }
    }

    private fun ringerCycle(): String {
        val am = ctx.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        return when (am.ringerMode) {
            AudioManager.RINGER_MODE_NORMAL -> ringer(AudioManager.RINGER_MODE_VIBRATE) ?: "Vibrate mode"
            AudioManager.RINGER_MODE_VIBRATE ->
                if (Bridge.hasDndAccess(ctx)) ringer(AudioManager.RINGER_MODE_SILENT) ?: "Silent mode"
                else ringer(AudioManager.RINGER_MODE_NORMAL) ?: "Ringer on"
            else -> ringer(AudioManager.RINGER_MODE_NORMAL) ?: "Ringer on"
        }
    }

    private fun batteryPercent() =
        (ctx.getSystemService(Context.BATTERY_SERVICE) as android.os.BatteryManager)
            .getIntProperty(android.os.BatteryManager.BATTERY_PROPERTY_CAPACITY)

    private fun isCharging() =
        (ctx.getSystemService(Context.BATTERY_SERVICE) as android.os.BatteryManager).isCharging

    private fun open(intent: Intent): String? = try {
        ctx.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        null
    } catch (_: Throwable) {
        "Ye phone par available nahi"
    }

    private fun openCategory(category: String): String? =
        open(Intent.makeMainSelectorActivity(Intent.ACTION_MAIN, category))

    companion object {
        const val A11Y_MISSING = "Pehle AR Controller ki Accessibility permission on karein"

        @Volatile
        private var torchOn = false
    }
}
