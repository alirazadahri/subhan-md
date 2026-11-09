package com.arcontroller

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.view.ContextThemeWrapper
import android.view.Gravity
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.view.animation.OvershootInterpolator
import android.webkit.CookieManager
import android.webkit.JsPromptResult
import android.webkit.JsResult
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast

/** Web versions of popular apps, so they can float even where Android's freeform mode is unavailable. */
object WebApps {
    data class Site(val url: String, val desktop: Boolean = false)

    private val byPackage = mapOf(
        "com.google.android.youtube" to Site("https://m.youtube.com"),
        "com.google.android.apps.youtube.music" to Site("https://music.youtube.com"),
        "com.facebook.katana" to Site("https://m.facebook.com"),
        "com.facebook.lite" to Site("https://m.facebook.com"),
        "com.facebook.orca" to Site("https://www.messenger.com", desktop = true),
        "com.facebook.mlite" to Site("https://www.messenger.com", desktop = true),
        "com.instagram.android" to Site("https://www.instagram.com"),
        "com.instagram.lite" to Site("https://www.instagram.com"),
        "com.instagram.barcelona" to Site("https://www.threads.net"),
        // WhatsApp Web: link with "Link with phone number instead" (no QR scan needed).
        "com.whatsapp" to Site("https://web.whatsapp.com", desktop = true),
        "com.whatsapp.w4b" to Site("https://web.whatsapp.com", desktop = true),
        "com.zhiliaoapp.musically" to Site("https://www.tiktok.com"),
        "com.ss.android.ugc.trill" to Site("https://www.tiktok.com"),
        "com.twitter.android" to Site("https://x.com"),
        "com.snapchat.android" to Site("https://web.snapchat.com", desktop = true),
        "org.telegram.messenger" to Site("https://web.telegram.org/k/"),
        "org.telegram.messenger.web" to Site("https://web.telegram.org/k/"),
        "com.google.android.gm" to Site("https://mail.google.com"),
        "com.google.android.apps.maps" to Site("https://www.google.com/maps"),
        "com.google.android.googlequicksearchbox" to Site("https://www.google.com"),
        "com.android.chrome" to Site("https://www.google.com"),
        "com.google.android.apps.docs" to Site("https://drive.google.com"),
        "com.google.android.apps.photos" to Site("https://photos.google.com"),
        "com.google.android.apps.translate" to Site("https://translate.google.com"),
        "com.google.android.apps.bard" to Site("https://gemini.google.com"),
        "com.google.android.calendar" to Site("https://calendar.google.com"),
        "com.google.android.keep" to Site("https://keep.google.com"),
        "com.android.vending" to Site("https://play.google.com/store"),
        "com.linkedin.android" to Site("https://www.linkedin.com"),
        "com.reddit.frontpage" to Site("https://www.reddit.com"),
        "com.pinterest" to Site("https://www.pinterest.com"),
        "com.netflix.mediaclient" to Site("https://www.netflix.com"),
        "com.spotify.music" to Site("https://open.spotify.com"),
        "com.openai.chatgpt" to Site("https://chatgpt.com"),
        "com.daraz.android" to Site("https://www.daraz.pk"),
        "org.wikipedia" to Site("https://en.m.wikipedia.org"),
        "com.amazon.mShop.android.shopping" to Site("https://www.amazon.com"),
        "com.microsoft.office.outlook" to Site("https://outlook.live.com"),
        "com.discord" to Site("https://discord.com/app", desktop = true),
        "com.imo.android.imoim" to Site("https://imo.im"),
        "com.dailymotion.dailymotion" to Site("https://www.dailymotion.com"),
    )

    fun forPackage(pkg: String, label: String): Site? {
        byPackage[pkg]?.let { return it }
        val l = label.lowercase()
        return when {
            "browser" in l || "chrome" in l || "opera" in l || "firefox" in l || "edge" in l -> Site("https://www.google.com")
            else -> null
        }
    }

    /** "google.com" / "youtube" → a loadable URL. */
    fun normalizeUrl(input: String): String {
        val s = input.trim()
        return when {
            s.startsWith("http://") || s.startsWith("https://") -> s
            s.contains('.') && !s.contains(' ') -> "https://$s"
            else -> "https://www.google.com/search?q=" + Uri.encode(s)
        }
    }
}

/**
 * A draggable, resizable floating box drawn over every app (needs only "Display over other apps").
 * Title bar: drag to move · ◀ back · ⟳ reload · ⤢ maximise · – minimise to a chip · ✕ close.
 * Bottom-right corner: drag to resize. Must be used on the main thread.
 */
@SuppressLint("ClickableViewAccessibility", "SetJavaScriptEnabled")
class FloatingWebWindow private constructor(
    base: Context,
    private val title: String,
    private val startUrl: String,
    private val icon: Drawable?,
    private val desktop: Boolean,
    private val packageName: String?,
) {
    companion object {
        private val open = mutableListOf<FloatingWebWindow>()
        private const val DESKTOP_UA =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0 Safari/537.36"

        fun show(ctx: Context, title: String, url: String, icon: Drawable? = null, desktop: Boolean = false, packageName: String? = null) {
            // Re-focus an already open window for the same app instead of stacking duplicates.
            open.firstOrNull { it.startUrl == url }?.let { it.restore(); it.bringToFront(); return }
            FloatingWebWindow(ctx.applicationContext, title, url, icon, desktop, packageName).attach()
        }

        fun closeAll() = open.toList().forEach { it.close() }
        fun count() = open.size
    }

    private val ctx = ContextThemeWrapper(base, android.R.style.Theme_DeviceDefault)
    private val wm = base.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private val density = ctx.resources.displayMetrics.density
    private fun dp(v: Int) = (v * density).toInt()
    private val screenW get() = ctx.resources.displayMetrics.widthPixels
    private val screenH get() = ctx.resources.displayMetrics.heightPixels

    private lateinit var root: WindowRoot
    private lateinit var params: WindowManager.LayoutParams
    private lateinit var web: WebView
    private lateinit var progressBar: ProgressBar
    private var chip: View? = null
    private var chipParams: WindowManager.LayoutParams? = null
    private var maximized = false
    private var restoreRect = IntArray(4)

    private fun overlayType() =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        else @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_PHONE

    private fun attach() {
        val w = (screenW * 0.88f).toInt()
        val h = (screenH * 0.58f).toInt()
        params = WindowManager.LayoutParams(
            w, h, overlayType(),
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH or
                WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            // Cascade multiple windows slightly.
            x = (screenW - w) / 2 + dp(14) * open.size
            y = (screenH - h) / 2 + dp(14) * open.size
            softInputMode = WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE
        }

        root = WindowRoot()
        val card = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#0F1528"))
                cornerRadius = 20f * density
                setStroke(dp(1), Color.parseColor("#3B4A75"))
            }
            clipToOutline = true
            elevation = dp(16).toFloat()
        }
        card.addView(buildTitleBar(), LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(44)))

        progressBar = ProgressBar(ctx, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 100
            progressTintList = android.content.res.ColorStateList.valueOf(Color.parseColor("#8B5CF6"))
        }
        card.addView(progressBar, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(3)))

        web = buildWebView()
        card.addView(web, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))

        root.addView(card, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        root.addView(buildResizeHandle(), FrameLayout.LayoutParams(dp(34), dp(34), Gravity.BOTTOM or Gravity.END))

        wm.addView(root, params)
        open += this
        web.loadUrl(startUrl)

        card.scaleX = 0.6f; card.scaleY = 0.6f; card.alpha = 0f
        card.animate().scaleX(1f).scaleY(1f).alpha(1f).setDuration(260).setInterpolator(OvershootInterpolator(0.8f)).start()
    }

    private fun buildTitleBar(): View {
        val bar = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(10), 0, dp(4), 0)
            background = GradientDrawable(
                GradientDrawable.Orientation.LEFT_RIGHT,
                intArrayOf(Color.parseColor("#4C1D95"), Color.parseColor("#1E3A8A")),
            )
        }
        if (icon != null) {
            bar.addView(ImageView(ctx).apply { setImageDrawable(icon) }, LinearLayout.LayoutParams(dp(22), dp(22)))
        }
        bar.addView(TextView(ctx).apply {
            text = title
            setTextColor(Color.WHITE)
            textSize = 14f
            typeface = Typeface.DEFAULT_BOLD
            maxLines = 1
            setPadding(dp(8), 0, 0, 0)
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))

        fun btn(label: String, onTap: () -> Unit) = TextView(ctx).apply {
            text = label
            setTextColor(Color.WHITE)
            textSize = 16f
            gravity = Gravity.CENTER
            setOnClickListener { onTap() }
        }.also { bar.addView(it, LinearLayout.LayoutParams(dp(36), dp(40))) }

        btn("◀") { if (web.canGoBack()) web.goBack() }
        btn("⟳") { web.reload() }
        if (packageName != null) btn("↗") { openInRealApp() }
        btn("⤢") { toggleMaximize() }
        btn("–") { minimize() }
        btn("✕") { close() }

        // Drag the window by its title bar.
        var downX = 0f; var downY = 0f; var startX = 0; var startY = 0
        bar.setOnTouchListener { _, e ->
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> { downX = e.rawX; downY = e.rawY; startX = params.x; startY = params.y }
                MotionEvent.ACTION_MOVE -> {
                    params.x = (startX + e.rawX - downX).toInt()
                    params.y = (startY + e.rawY - downY).toInt().coerceIn(0, screenH - dp(44))
                    wm.updateViewLayout(root, params)
                }
            }
            true
        }
        return bar
    }

    private fun buildResizeHandle(): View = TextView(ctx).apply {
        text = "◢"
        setTextColor(Color.parseColor("#A78BFA"))
        textSize = 18f
        gravity = Gravity.BOTTOM or Gravity.END
        setPadding(0, 0, dp(6), dp(2))
        var downX = 0f; var downY = 0f; var startW = 0; var startH = 0
        setOnTouchListener { _, e ->
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> { downX = e.rawX; downY = e.rawY; startW = params.width; startH = params.height }
                MotionEvent.ACTION_MOVE -> {
                    params.width = (startW + e.rawX - downX).toInt().coerceIn(dp(220), screenW)
                    params.height = (startH + e.rawY - downY).toInt().coerceIn(dp(240), screenH)
                    maximized = false
                    wm.updateViewLayout(root, params)
                }
            }
            true
        }
    }

    private fun buildWebView(): WebView = WebView(ctx).apply {
        settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            databaseEnabled = true
            mediaPlaybackRequiresUserGesture = false
            useWideViewPort = true
            loadWithOverviewMode = true
            setSupportZoom(true)
            builtInZoomControls = true
            displayZoomControls = false
            if (desktop) userAgentString = DESKTOP_UA
        }
        CookieManager.getInstance().setAcceptCookie(true)
        CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)

        webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                val uri = request.url
                if (uri.scheme == "http" || uri.scheme == "https") return false
                // intent://, tel:, mailto:, whatsapp: … hand off to the system.
                try {
                    val intent = if (uri.scheme == "intent") Intent.parseUri(uri.toString(), Intent.URI_INTENT_SCHEME)
                    else Intent(Intent.ACTION_VIEW, uri)
                    ctx.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                } catch (_: Throwable) {
                }
                return true
            }
        }
        webChromeClient = object : WebChromeClient() {
            override fun onProgressChanged(view: WebView, newProgress: Int) {
                progressBar.progress = newProgress
                progressBar.visibility = if (newProgress >= 100) View.INVISIBLE else View.VISIBLE
            }

            // Default JS dialogs need an Activity window and would crash inside an overlay.
            override fun onJsAlert(view: WebView, url: String, message: String, result: JsResult): Boolean {
                Toast.makeText(ctx, message, Toast.LENGTH_SHORT).show()
                result.confirm()
                return true
            }

            override fun onJsConfirm(view: WebView, url: String, message: String, result: JsResult): Boolean {
                result.confirm()
                return true
            }

            override fun onJsPrompt(view: WebView, url: String, message: String, defaultValue: String?, result: JsPromptResult): Boolean {
                result.confirm(defaultValue ?: "")
                return true
            }
        }
    }

    private fun setFocusable(focusable: Boolean) {
        if (!root.isAttachedToWindow) return
        val wasFocusable = params.flags and WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE == 0
        if (wasFocusable == focusable) return
        params.flags = if (focusable) params.flags and WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE.inv()
        else params.flags or WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
        wm.updateViewLayout(root, params)
    }

    private fun toggleMaximize() {
        if (!maximized) {
            restoreRect = intArrayOf(params.x, params.y, params.width, params.height)
            params.x = 0; params.y = 0; params.width = screenW; params.height = screenH
        } else {
            params.x = restoreRect[0]; params.y = restoreRect[1]; params.width = restoreRect[2]; params.height = restoreRect[3]
        }
        maximized = !maximized
        wm.updateViewLayout(root, params)
    }

    private fun bringToFront() {
        if (!root.isAttachedToWindow) return
        wm.removeView(root)
        wm.addView(root, params)
    }

    /** Collapse to a small draggable chip; tap it to restore. */
    private fun minimize() {
        if (chip != null) return
        setFocusable(false)
        root.visibility = View.GONE
        val size = dp(52)
        val c = FrameLayout(ctx).apply {
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(Color.parseColor("#1E1B4B"))
                setStroke(dp(2), Color.parseColor("#A78BFA"))
            }
            elevation = dp(8).toFloat()
            if (icon != null) {
                addView(ImageView(ctx).apply { setImageDrawable(icon) }, FrameLayout.LayoutParams(dp(30), dp(30), Gravity.CENTER))
            } else {
                addView(TextView(ctx).apply {
                    text = title.take(1).uppercase()
                    setTextColor(Color.WHITE); textSize = 18f; gravity = Gravity.CENTER
                }, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
            }
        }
        val p = WindowManager.LayoutParams(
            size, size, overlayType(),
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE, PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = dp(8)
            y = screenH / 2 + dp(70) * open.indexOf(this@FloatingWebWindow).coerceAtLeast(0)
        }
        var downX = 0f; var downY = 0f; var sx = 0; var sy = 0; var moved = false
        c.setOnTouchListener { v, e ->
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> { downX = e.rawX; downY = e.rawY; sx = p.x; sy = p.y; moved = false }
                MotionEvent.ACTION_MOVE -> {
                    if (kotlin.math.abs(e.rawX - downX) + kotlin.math.abs(e.rawY - downY) > dp(8)) moved = true
                    p.x = (sx + e.rawX - downX).toInt(); p.y = (sy + e.rawY - downY).toInt()
                    wm.updateViewLayout(v, p)
                }
                MotionEvent.ACTION_UP -> if (!moved) restore()
            }
            true
        }
        wm.addView(c, p)
        chip = c
        chipParams = p
    }

    private fun restore() {
        chip?.let { runCatching { wm.removeView(it) } }
        chip = null
        root.visibility = View.VISIBLE
    }

    fun close() {
        chip?.let { runCatching { wm.removeView(it) } }
        chip = null
        open -= this
        runCatching { wm.removeView(root) }
        web.stopLoading()
        web.destroy()
    }

    /**
     * Root view: takes keyboard focus only while the user is touching the window (so typing works),
     * and gives it back to the app underneath on any outside tap.
     */
    private inner class WindowRoot : FrameLayout(ctx) {
        override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
            if (ev.actionMasked == MotionEvent.ACTION_OUTSIDE) {
                setFocusable(false)
                return true
            }
            if (ev.actionMasked == MotionEvent.ACTION_DOWN) setFocusable(true)
            return super.dispatchTouchEvent(ev)
        }

        override fun dispatchKeyEvent(event: KeyEvent): Boolean {
            if (event.keyCode == KeyEvent.KEYCODE_BACK) {
                if (event.action == KeyEvent.ACTION_UP) {
                    if (web.canGoBack()) web.goBack() else setFocusable(false)
                }
                return true
            }
            return super.dispatchKeyEvent(event)
        }
    }

    private fun openInRealApp() {
        val pkg = packageName ?: return
        FloatingAppLauncher.launchFullscreen(ctx, pkg)
        close()
    }
}
