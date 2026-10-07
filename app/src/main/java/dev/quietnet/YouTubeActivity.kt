package dev.quietnet

import android.annotation.SuppressLint
import android.content.Intent
import android.content.pm.ActivityInfo
import android.graphics.Bitmap
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import java.io.ByteArrayInputStream

/**
 * YouTube's mobile site with ads removed by our own page script (assets/youtube.js)
 * and ad requests refused, playing on with the screen off.
 */
class YouTubeActivity : ComponentActivity() {
    companion object {
        private const val HOME = "https://m.youtube.com/"
        private val allowedHost = Regex("""(^|\.)(youtube\.com|youtu\.be|google\.[a-z.]+|gstatic\.com|googleusercontent\.com|ytimg\.com|ggpht\.com|googleapis\.com)$""")
        private val adPaths = listOf("/api/stats/ads", "/pagead/", "/ptracking", "/get_midroll_info", "/youtubei/v1/player/ad_break")
        private val urlInText = Regex("""https?://\S+""")
    }

    private lateinit var root: FrameLayout
    private lateinit var web: WebView
    private var fullscreen: View? = null
    private var fullscreenCallback: WebChromeClient.CustomViewCallback? = null
    private var injectOnPageStart = false
    private val script by lazy { assets.open("youtube.js").bufferedReader().use { it.readText() } }
    private val bars by lazy { WindowCompat.getInsetsController(window, root) }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        if (BuildConfig.DEBUG) WebView.setWebContentsDebuggingEnabled(true)

        root = FrameLayout(this).apply { setBackgroundColor(Color.BLACK) }
        web = BackgroundWebView(this).apply { setBackgroundColor(Color.BLACK) }
        root.addView(web, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        setContentView(root)
        bars.isAppearanceLightStatusBars = false
        bars.isAppearanceLightNavigationBars = false
        root.setOnApplyWindowInsetsListener { v, insets ->
            if (fullscreen == null) {
                val i = WindowInsetsCompat.toWindowInsetsCompat(insets, v)
                    .getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.ime())
                v.setPadding(i.left, i.top, i.right, i.bottom)
            } else {
                v.setPadding(0, 0, 0, 0)
            }
            insets
        }

        with(web.settings) {
            javaScriptEnabled = true
            domStorageEnabled = true
            mediaPlaybackRequiresUserGesture = false
            loadWithOverviewMode = true
            useWideViewPort = true
            setSupportMultipleWindows(false)
            // Google refuses sign-in from pages that announce themselves as an app's web view.
            userAgentString = userAgentString.replace("; wv", "").replace(Regex("""Version/[\d.]+ """), "")
        }
        CookieManager.getInstance().setAcceptThirdPartyCookies(web, true)
        web.addJavascriptInterface(Bridge(), "QuietNetBridge")
        if (WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) {
            WebViewCompat.addDocumentStartJavaScript(web, script, setOf("https://*.youtube.com", "https://youtube.com"))
        } else {
            injectOnPageStart = true
        }
        web.webViewClient = Client()
        web.webChromeClient = Chrome()

        Player.controller = { cmd ->
            web.evaluateJavascript("window.__qnControl && window.__qnControl(${quote(cmd)})", null)
        }

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                when {
                    fullscreen != null -> exitFullscreen()
                    web.canGoBack() -> web.goBack()
                    // Leaving shouldn't stop what's playing; it keeps going in the background.
                    Player.state.value.playing -> moveTaskToBack(true)
                    else -> finish()
                }
            }
        })

        if (savedInstanceState == null || web.restoreState(savedInstanceState) == null) {
            web.loadUrl(target(intent) ?: HOME)
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        target(intent)?.let { web.loadUrl(it) }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        web.saveState(outState)
    }

    // No onPause/onStop handling on purpose: pausing the web view would stop background playback.

    override fun onDestroy() {
        Player.reset()
        stopService(Intent(this, PlaybackService::class.java))
        web.destroy()
        super.onDestroy()
    }

    /** The YouTube page to open for a link or shared text, in its mobile form. */
    private fun target(intent: Intent?): String? {
        val raw = when (intent?.action) {
            Intent.ACTION_VIEW -> intent.dataString
            Intent.ACTION_SEND -> intent.getStringExtra(Intent.EXTRA_TEXT)?.let { urlInText.find(it)?.value }
            else -> null
        } ?: return null
        val uri = Uri.parse(raw)
        val host = uri.host ?: return null
        return when {
            host == "youtu.be" -> uri.lastPathSegment?.let { "https://m.youtube.com/watch?v=$it" }
            host.endsWith("youtube.com") -> uri.buildUpon().scheme("https").authority("m.youtube.com").build().toString()
            else -> null
        }
    }

    private fun enterFullscreen(view: View, callback: WebChromeClient.CustomViewCallback) {
        if (fullscreen != null) {
            callback.onCustomViewHidden()
            return
        }
        fullscreen = view
        fullscreenCallback = callback
        root.addView(view, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        web.visibility = View.GONE
        root.setPadding(0, 0, 0, 0)
        bars.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        bars.hide(WindowInsetsCompat.Type.systemBars())
        requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
    }

    private fun exitFullscreen() {
        val view = fullscreen ?: return
        fullscreen = null
        root.removeView(view)
        web.visibility = View.VISIBLE
        bars.show(WindowInsetsCompat.Type.systemBars())
        requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        root.requestApplyInsets()
        fullscreenCallback?.onCustomViewHidden()
        fullscreenCallback = null
    }

    private inner class Client : WebViewClient() {
        override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
            val url = request.url
            // "Open in the YouTube app" links and the like.
            if (url.scheme != "http" && url.scheme != "https") return true
            val host = url.host ?: return true
            if (allowedHost.containsMatchIn(host)) return false
            try {
                startActivity(Intent(Intent.ACTION_VIEW, url))
            } catch (_: Exception) {
            }
            return true
        }

        override fun onPageStarted(view: WebView, url: String?, favicon: Bitmap?) {
            if (injectOnPageStart) view.evaluateJavascript(script, null)
        }

        override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? {
            val url = request.url
            val host = url.host ?: return null
            val path = url.path ?: ""
            val ad = Rules.isBlocked(host) ||
                (host.endsWith("youtube.com") && adPaths.any { path.startsWith(it) })
            return if (ad) WebResourceResponse("text/plain", "utf-8", 204, "No Content", emptyMap(), ByteArrayInputStream(ByteArray(0))) else null
        }
    }

    private inner class Chrome : WebChromeClient() {
        override fun onShowCustomView(view: View, callback: CustomViewCallback) = enterFullscreen(view, callback)
        override fun onHideCustomView() = exitFullscreen()

        // Without this, a grey play icon flashes before each video.
        override fun getDefaultVideoPoster(): Bitmap = Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888)
    }

    /** Called by the page script; runs on a background thread. */
    private inner class Bridge {
        @JavascriptInterface
        fun state(playing: Boolean, title: String, videoId: String, position: Double, duration: Double) {
            runOnUiThread {
                Player.update(this@YouTubeActivity, Player.State(playing, title, videoId, position, duration))
            }
        }
    }

    private fun quote(s: String) = "'" + s.replace("\\", "\\\\").replace("'", "\\'") + "'"
}

/**
 * The web engine pauses video as soon as its window is hidden, which happens
 * when the user leaves the app or turns the screen off. Reporting the window as
 * still visible keeps the video playing.
 */
private class BackgroundWebView(context: android.content.Context) : WebView(context) {
    override fun onWindowVisibilityChanged(visibility: Int) {
        super.onWindowVisibilityChanged(View.VISIBLE)
    }
}
