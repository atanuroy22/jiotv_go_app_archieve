package com.skylake.skytv.jgorunner.activities

import android.annotation.SuppressLint
import android.content.Intent
import android.content.res.Configuration
import android.graphics.Bitmap
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.KeyEvent
import android.view.View
import android.webkit.ConsoleMessage
import android.webkit.PermissionRequest
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.ProgressBar
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.core.view.WindowCompat
import com.skylake.skytv.jgorunner.R
import com.skylake.skytv.jgorunner.core.data.JTVConfigurationManager
import com.skylake.skytv.jgorunner.data.SkySharedPref
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.util.Locale

class WebPlayerActivity : ComponentActivity() {
    companion object {
        private const val TAG = "WebTVActivity"
        private const val DEFAULT_URL_TEMPLATE = "http://localhost:%d"
    }

    private var webView: WebView? = null
    private var loadingSpinner: ProgressBar? = null
    private var url: String? = null

    private var channelNumbers: List<String>? = null
    private var initURL: String? = null

    private var currentPlayId: String? = null
    private var currentLogoUrl: String? = null
    private var currentChannelName: String? = null
    private var targetChannelId: String? = null

    private val recentChannels: MutableList<Channel> = ArrayList()

    private val prefManager = SkySharedPref.getInstance(this)
    private val jtvConfigManager by lazy { JTVConfigurationManager.getInstance(this) }

    private class Channel(var playId: String?, var logoUrl: String?, var channelName: String?)

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_web_player)

        val savedPortNumber = prefManager.myPrefs.jtvGoServerPort
        val filterQ = prefManager.myPrefs.filterQ
        val filterL = prefManager.myPrefs.filterL
        val filterC = prefManager.myPrefs.filterC
//        val extraFilterUrl = "/?q=$filterQ&language=$filterL&category=$filterC" // "/?q=low&language=6&category=7"
        val extraFilterUrl = buildString {
            append("/")

            if (!filterQ.isNullOrEmpty()) append("?q=$filterQ")

            if (!filterL.isNullOrEmpty()) {
                if (isNotEmpty()) append("&")
                append("language=$filterL")
            }

            if (!filterC.isNullOrEmpty()) {
                if (isNotEmpty()) append("&")
                append("category=$filterC")
            }
        }

        val defaultUrl = String.format(
            Locale.getDefault(),
            DEFAULT_URL_TEMPLATE,
            savedPortNumber
        ) + extraFilterUrl

        val startupUrl = intent?.getStringExtra("startup_url")?.trim().orEmpty()
        targetChannelId = intent?.getStringExtra("target_channel_id")?.trim().orEmpty()?.takeIf { it.isNotBlank() }
        val resolvedStartupUrl = if (startupUrl.isNotEmpty()) {
            if (startupUrl.startsWith("http://", ignoreCase = true) || startupUrl.startsWith("https://", ignoreCase = true)) {
                startupUrl
            } else {
                if (startupUrl.startsWith("/")) {
                    String.format(Locale.getDefault(), DEFAULT_URL_TEMPLATE, savedPortNumber) + startupUrl
                } else {
                    String.format(Locale.getDefault(), DEFAULT_URL_TEMPLATE, savedPortNumber) + "/$startupUrl"
                }
            }
        } else {
            defaultUrl
        }

        url = rewriteDrmPlayUrlToMpdIfNeeded(resolvedStartupUrl)

        Log.d("DIX", url!!)

        Log.d(TAG, "URL: $url")

        setupBackPressedCallback()
        setupFullScreenMode()

        webView = findViewById(R.id.webview)
        loadingSpinner = findViewById(R.id.loading_spinner)

        setupWebView()
        loadUrl()
    }

    private var playerUrlCount = 0

    private fun setupBackPressedCallback() {
        val callback: OnBackPressedCallback = object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (webView != null) {
                    val currentUrl = webView!!.url

                    if (currentUrl != null && currentUrl.contains("/player/")) {
                        playerUrlCount++
                        if (playerUrlCount >= 3) {
                            webView!!.loadUrl(initURL!!)
                        } else {
                            webView!!.goBack()
                        }
                    } else if (webView!!.canGoBack()) {
                        playerUrlCount++
                        if (playerUrlCount >= 6) {
                            finish()
                        } else {
                            webView!!.goBack()
                        }
                    } else {
                        finish()
                    }
                } else {
                    finish()
                }
            }
        }
        onBackPressedDispatcher.addCallback(this, callback)
    }


    private fun setupFullScreenMode() {
//        setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE);
                WindowCompat.setDecorFitsSystemWindows(window, false)
        updateSystemUiVisibility()
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        updateSystemUiVisibility() // This maintains full-screen mode in both orientations
                refreshShakaViewportLayout()
    }

        private fun refreshShakaViewportLayout() {
                val currentUrl = webView?.url.orEmpty()
                val isPlayerLikeUrl = currentUrl.contains("/player/") ||
                                currentUrl.contains("/mpd/", ignoreCase = true) ||
                                currentUrl.contains(".mpd", ignoreCase = true)
                if (!isPlayerLikeUrl) return

                webView?.evaluateJavascript(
                        """
                        (function() {
                            try {
                                var cssId = 'webui-shaka-center';
                                var css = `
                                    html, body {
                                        width: 100% !important;
                                        height: 100% !important;
                                        min-height: 100vh !important;
                                        min-height: 100dvh !important;
                                        margin: 0 !important;
                                        padding: 0 !important;
                                        background: black !important;
                                        overflow: hidden !important;
                                    }
                                    .shaka-video-container,
                                    .shaka-player-container,
                                    .player,
                                    .video-container,
                                    iframe {
                                        width: 100% !important;
                                        height: 100% !important;
                                        min-height: 100vh !important;
                                        min-height: 100dvh !important;
                                        max-width: 100% !important;
                                        max-height: 100% !important;
                                        margin: 0 !important;
                                        padding: 0 !important;
                                        overflow: hidden !important;
                                    }
                                    video {
                                        width: 100% !important;
                                        height: 100% !important;
                                        max-width: 100% !important;
                                        max-height: 100% !important;
                                        object-fit: contain !important;
                                        object-position: center center !important;
                                        display: block !important;
                                        margin: 0 auto !important;
                                        opacity: 1 !important;
                                        visibility: visible !important;
                                        background: black !important;
                                    }
                                `;

                                var style = document.getElementById(cssId);
                                if (!style) {
                                    style = document.createElement('style');
                                    style.id = cssId;
                                    document.head.appendChild(style);
                                }
                                style.textContent = css;

                                function viewportHeightPx() {
                                    if (window.visualViewport && window.visualViewport.height) {
                                        return Math.round(window.visualViewport.height);
                                    }
                                    return Math.round(window.innerHeight || document.documentElement.clientHeight || 0);
                                }

                                function applyViewportFix() {
                                    try {
                                        var vh = viewportHeightPx();
                                        if (!vh) return;
                                        var px = vh + 'px';
                                        var html = document.documentElement;
                                        var body = document.body;
                                        if (!html || !body) return;

                                        html.style.height = px;
                                        html.style.minHeight = px;
                                        html.style.maxHeight = px;
                                        html.style.overflow = 'hidden';

                                        body.style.height = px;
                                        body.style.minHeight = px;
                                        body.style.maxHeight = px;
                                        body.style.overflow = 'hidden';

                                        var selectors = ['.shaka-video-container','.shaka-player-container','.player','.video-container','#player','iframe'];
                                        selectors.forEach(function(sel) {
                                            document.querySelectorAll(sel).forEach(function(el) {
                                                el.style.height = px;
                                                el.style.minHeight = px;
                                                el.style.maxHeight = px;
                                                el.style.width = '100%';
                                                el.style.maxWidth = '100%';
                                            });
                                        });
                                    } catch (e) {}
                                }

                                window.__webUiApplyViewportFix = applyViewportFix;
                                if (!window.__webUiViewportFixInstalled) {
                                    window.__webUiViewportFixInstalled = true;
                                    window.addEventListener('resize', applyViewportFix, true);
                                    window.addEventListener('orientationchange', function() {
                                        setTimeout(applyViewportFix, 60);
                                        setTimeout(applyViewportFix, 220);
                                    }, true);
                                    if (window.visualViewport) {
                                        window.visualViewport.addEventListener('resize', applyViewportFix, true);
                                        window.visualViewport.addEventListener('scroll', applyViewportFix, true);
                                    }
                                }

                                setTimeout(applyViewportFix, 0);
                                setTimeout(applyViewportFix, 120);
                                setTimeout(applyViewportFix, 320);

                                window.dispatchEvent(new Event('resize'));
                                setTimeout(function() {
                                    window.dispatchEvent(new Event('resize'));
                                }, 120);
                            } catch (e) {}
                        })();
                        """.trimIndent(),
                        null
                )
        }


    private fun updateSystemUiVisibility() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            window.insetsController?.let { controller ->
                controller.hide(WindowInsets.Type.statusBars() or WindowInsets.Type.navigationBars())
                controller.systemBarsBehavior =
                    WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            }
        } else {
            @Suppress("deprecation")
            window.decorView.systemUiVisibility = (
                    View.SYSTEM_UI_FLAG_FULLSCREEN
                            or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                            or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                    )
        }
    }


    @SuppressLint("SetJavaScriptEnabled")
    private fun setupWebView() {
        webView!!.webViewClient = CustomWebViewClient()
        webView!!.webChromeClient = object : WebChromeClient() {
            override fun onCreateWindow(
                view: WebView?,
                isDialog: Boolean,
                isUserGesture: Boolean,
                resultMsg: android.os.Message?
            ): Boolean {
                val transport = resultMsg?.obj as? WebView.WebViewTransport
                transport?.webView = view
                resultMsg?.sendToTarget()
                return true
            }

            override fun onPermissionRequest(request: PermissionRequest?) {
                // Shaka/Widevine in WebView may request protected media. Granting on the UI
                // thread prevents silent DRM-denied fallback to non-DRM playback paths.
                if (request == null) return
                runOnUiThread {
                    try {
                        request.grant(request.resources)
                        Log.d(TAG, "Granted WebView permission request: ${request.resources.joinToString()}")
                    } catch (e: Exception) {
                        Log.e(TAG, "Failed to grant WebView permission request", e)
                        request.deny()
                    }
                }
            }

            override fun onConsoleMessage(consoleMessage: ConsoleMessage): Boolean {
                if (consoleMessage.messageLevel() == ConsoleMessage.MessageLevel.ERROR ||
                    consoleMessage.message().contains("drm", ignoreCase = true) ||
                    consoleMessage.message().contains("widevine", ignoreCase = true) ||
                    consoleMessage.message().contains("eme", ignoreCase = true)
                ) {
                    Log.e(
                        TAG,
                        "WebConsole ${consoleMessage.messageLevel()}: ${consoleMessage.message()} @${consoleMessage.sourceId()}:${consoleMessage.lineNumber()}"
                    )
                }
                return super.onConsoleMessage(consoleMessage)
            }
        }

        val webSettings = webView!!.settings
        webSettings.javaScriptEnabled = true
        webSettings.domStorageEnabled = true
        webSettings.loadWithOverviewMode = true
        webSettings.useWideViewPort = true
        webSettings.defaultTextEncodingName = "utf-8"
        webSettings.mixedContentMode = 0
        webSettings.mediaPlaybackRequiresUserGesture = false // Allow autoplay
        webSettings.javaScriptCanOpenWindowsAutomatically = true
        webSettings.setSupportMultipleWindows(true)

        // Ensure hardware accelerated rendering path is used for video/DRM playback.
        webView!!.setLayerType(View.LAYER_TYPE_HARDWARE, null)
        webView!!.isFocusable = true
        webView!!.isFocusableInTouchMode = true
        webView!!.requestFocus(View.FOCUS_DOWN)
    }

    private fun loadUrl() {
        if (url != null) {
            webView!!.loadUrl(url!!)
        }
    }

    private fun setDarkTheme() {
        if (webView != null) {
            val jsCode =
                "document.getElementsByTagName('html')[0].setAttribute('data-theme', 'dark');" +
                        "localStorage.setItem('theme', 'dark');"
            webView!!.evaluateJavascript(jsCode, null)
        }
    }

    override fun onPause() {
        super.onPause()
        webView!!.onPause()
    }

    override fun onResume() {
        super.onResume()
        webView!!.onResume()
        webView!!.requestFocus(View.FOCUS_DOWN)
//        webView!!.loadUrl(url!!)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        val startupUrl = intent.getStringExtra("startup_url")?.trim().orEmpty()
        if (startupUrl.isNotEmpty()) {
            val localPort = prefManager.myPrefs.jtvGoServerPort
            val resolved = if (startupUrl.startsWith("http://", ignoreCase = true) || startupUrl.startsWith("https://", ignoreCase = true)) {
                startupUrl
            } else {
                val base = String.format(Locale.getDefault(), DEFAULT_URL_TEMPLATE, localPort)
                if (startupUrl.startsWith("/")) "$base$startupUrl" else "$base/$startupUrl"
            }
            val rewritten = rewriteDrmPlayUrlToMpdIfNeeded(resolved)
            url = rewritten
            webView?.loadUrl(rewritten)
        }
    }

    private fun rewriteDrmPlayUrlToMpdIfNeeded(input: String): String {
        val drmEnabled = try {
            jtvConfigManager.jtvConfiguration.drm
        } catch (_: Exception) {
            false
        }
        if (!drmEnabled) return input

        val playRegex = Regex(".*/play/(\\d+)(?:[/?].*)?$")
        val match = playRegex.find(input) ?: return input
        val playId = match.groupValues.getOrNull(1).orEmpty()
        if (playId.isBlank()) return input

        val normalizedQuality = prefManager.myPrefs.filterQX?.trim()?.lowercase()
        val quality = when (normalizedQuality) {
            "auto", "low", "medium", "high" -> normalizedQuality
            else -> "auto"
        }
        val localPort = prefManager.myPrefs.jtvGoServerPort
        val base = String.format(Locale.getDefault(), DEFAULT_URL_TEMPLATE, localPort)
        val rewritten = "$base/mpd/$playId?q=$quality&pm=hd"
        Log.d(TAG, "Rewriting DRM /play/ URL to direct MPD URL: $rewritten")
        return rewritten
    }

    private fun isPlayerLikeUrl(currentUrl: String?): Boolean {
        if (currentUrl.isNullOrBlank()) return false
        return currentUrl.contains("/player/") ||
                currentUrl.contains("/mpd/", ignoreCase = true) ||
                currentUrl.contains(".mpd", ignoreCase = true) ||
                currentUrl.contains("/pind", ignoreCase = true)
    }

    private fun forwardDpadToWebView(event: KeyEvent): Boolean {
        val target = webView ?: return false
        val dpadCodes = setOf(
            KeyEvent.KEYCODE_DPAD_LEFT,
            KeyEvent.KEYCODE_DPAD_RIGHT,
            KeyEvent.KEYCODE_DPAD_UP,
            KeyEvent.KEYCODE_DPAD_DOWN,
            KeyEvent.KEYCODE_DPAD_CENTER,
            KeyEvent.KEYCODE_ENTER,
            KeyEvent.KEYCODE_NUMPAD_ENTER
        )
        if (!dpadCodes.contains(event.keyCode)) return false

        return try {
            target.isFocusable = true
            target.isFocusableInTouchMode = true
            target.requestFocus(View.FOCUS_DOWN)
            target.dispatchKeyEvent(event)
            true
        } catch (_: Exception) {
            false
        }
    }


    @SuppressLint("RestrictedApi")
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        val currentUrl = webView?.url
        if (isPlayerLikeUrl(currentUrl)) {
            if (forwardDpadToWebView(event)) {
                return true
            }

            if (event.action == KeyEvent.ACTION_DOWN) {
                when (event.keyCode) {
                    KeyEvent.KEYCODE_CHANNEL_UP, KeyEvent.KEYCODE_PAGE_UP -> {
                        navigateToNextChannel()
                        return true
                    }

                    KeyEvent.KEYCODE_CHANNEL_DOWN, KeyEvent.KEYCODE_PAGE_DOWN -> {
                        navigateToPreviousChannel()
                        return true
                    }
                }
            }
        }
        return super.dispatchKeyEvent(event)
    }

    private fun navigateToNextChannel() {
        navigateChannel(1)
    }

    private fun navigateToPreviousChannel() {
        navigateChannel(-1)
    }

    private fun navigateChannel(direction: Int) {
        if (channelNumbers == null || channelNumbers!!.isEmpty()) {
            Log.d(TAG, "No channel numbers available.")
            return
        }

        Log.d(TAG, "Total channels available: " + channelNumbers!!.size)

        val currentUrl = checkNotNull(webView!!.url)
        val queryIndex = currentUrl.indexOf('?')

        val currentNumber = if (queryIndex != -1) {
            currentUrl.substring(currentUrl.lastIndexOf('/') + 1, queryIndex)
        } else {
            currentUrl.substring(currentUrl.lastIndexOf('/') + 1)
        }

        val index = channelNumbers!!.indexOf(currentNumber)

        if (index >= 0) {
            val newIndex = (index + direction + channelNumbers!!.size) % channelNumbers!!.size
            val newNumber = channelNumbers!![newIndex]
            val newUrl = if (queryIndex != -1) {
                currentUrl.replace("/$currentNumber?", "/$newNumber?")
            } else {
                currentUrl.replace("/$currentNumber", "/$newNumber")
            }

            Log.d(TAG, "Navigating to Channel: $newUrl")
            webView!!.loadUrl(newUrl)
        } else {
            Log.d(TAG, "Current number not found in channel numbers.")
        }
    }

    private inner class CustomWebViewClient : WebViewClient() {
        @Deprecated("Deprecated in Java")
        override fun shouldOverrideUrlLoading(view: WebView, url: String): Boolean {
            val isAllowedRoute =
                url.contains("localhost", ignoreCase = true) ||
                    url.contains("allinonereborn.online/tplay/", ignoreCase = true) ||
                    url.contains("mini.allinonereborn.fun/tplay/", ignoreCase = true) ||
                    url.contains("jtvxweb.pages.dev", ignoreCase = true) ||
                    url.contains("/pind", ignoreCase = true) ||
                    url.contains("/player/", ignoreCase = true) ||
                    url.contains("/mpd/", ignoreCase = true) ||
                    url.contains(".mpd", ignoreCase = true) ||
                    url.contains("/play/", ignoreCase = true)

            if (!isAllowedRoute) {
                Log.d(TAG, "Blocked non-player navigation: $url")
            return true
            }

            val isDrmLikeUrl = url.contains("/play/", ignoreCase = true) ||
                    url.contains("/mpd/", ignoreCase = true) ||
                    url.contains(".mpd", ignoreCase = true) ||
                    url.contains("render.dash", ignoreCase = true) ||
                    url.contains("widevine", ignoreCase = true)

            if (isDrmLikeUrl) {
                val drmEnabled = try {
                    jtvConfigManager.jtvConfiguration.drm
                } catch (_: Exception) {
                    false
                }

                if (drmEnabled) {
                    val rewrittenUrl = rewriteDrmPlayUrlToMpdIfNeeded(url)
                    if (rewrittenUrl != url) {
                        Log.d(TAG, "DRM enabled, forcing direct MPD route: $rewrittenUrl")
                        view.loadUrl(rewrittenUrl)
                        return true
                    }
                    // Keep DRM routes in WebView so the server-side Shaka player handles playback.
                    Log.d(TAG, "DRM enabled, keeping URL in WebView: $url")
                    return false
                }
            }

            // Keep Tata Play /play/ URLs inside the WebView browser flow.
            if (url.contains("/play/")) {
                initURL = webView!!.url
                Log.d(TAG, "Keeping browser flow in WebView: $url")
                return false
            }
            return false
        }


        override fun onPageStarted(view: WebView, url: String, favicon: Bitmap?) {
            if (prefManager.myPrefs.darkMODE) {
                setDarkTheme()
            }
            loadingSpinner!!.visibility = View.VISIBLE
        }

        override fun onPageFinished(view: WebView, url: String) {
            loadingSpinner!!.visibility = View.GONE
            val isPlayerLikeUrl = url.contains("/player/") ||
                    url.contains("/mpd/", ignoreCase = true) ||
                    url.contains(".mpd", ignoreCase = true)
            if (isPlayerLikeUrl) {
                Log.d(TAG, "Playing: $url")
                setupFullScreenMode()
                centerPlayerInWebUi(view)
                forceUnmutePlayer(view)
                view.requestFocus(View.FOCUS_DOWN)
                view.evaluateJavascript(
                    """
                    (function() {
                        try {
                            if (document && document.body) {
                                if (document.body.tabIndex < 0) {
                                    document.body.tabIndex = 0;
                                }
                                document.body.focus();
                            }
                        } catch (e) {}
                    })();
                    """.trimIndent(),
                    null
                )
            } else if (url.contains("/tplay/", ignoreCase = true) && !targetChannelId.isNullOrBlank()) {
                val channelId = targetChannelId.orEmpty()
                
                // Hide the list immediately
                view.evaluateJavascript(
                    """
                    (function() {
                        var style = document.createElement('style');
                        style.innerHTML = 'body { background: black !important; } .container, .grid, .channel-card, header, footer { display: none !important; }';
                        document.head.appendChild(style);
                    })();
                    """.trimIndent(),
                    null
                )

                view.postDelayed({
                    view.evaluateJavascript(
                        """
                        (function() {
                            try {
                                window.open = function(u) { window.location.href = u; return null; };
                                var card = document.querySelector('.channel-card[data-id="$channelId"]');
                                if (card) {
                                    card.click();
                                    return 'clicked';
                                }
                                return 'missing';
                            } catch (e) {
                                return 'error';
                            }
                        })();
                        """.trimIndent(),
                        null
                    )
                }, 500)
            } else if (url.contains("jio", ignoreCase = true) || url.contains("jtvxweb", ignoreCase = true)) {
                 // Auto-click "Only India Stream" button for Jio
                 view.evaluateJavascript(
                    """
                    (function() {
                        try {
                            var checkInterval = setInterval(function() {
                                var buttons = Array.from(document.querySelectorAll('button, .btn, [role="button"]'));
                                var indiaButton = buttons.find(b => b.textContent.toLowerCase().includes('only india'));
                                if (indiaButton) {
                                    indiaButton.click();
                                    clearInterval(checkInterval);
                                }
                            }, 500);
                            setTimeout(() => clearInterval(checkInterval), 10000);
                        } catch (e) {}
                    })();
                    """.trimIndent(),
                    null
                 )
            } else {
                moveSearchInput(view)
                extractChannelNumbers()
                loadRecentChannels()
            }
        }

        private fun forceUnmutePlayer(view: WebView) {
            view.evaluateJavascript(
                """
                (function() {
                    try {
                        function doUnmute() {
                            var videos = document.querySelectorAll('video');
                            videos.forEach(function(v) {
                                v.muted = false;
                                v.volume = 1.0;
                                v.removeAttribute('muted');
                                if (v.paused && v.readyState >= 2) {
                                    try { v.play(); } catch(e) {}
                                }
                            });
                            var buttons = Array.from(document.querySelectorAll('button,[role="button"],i,svg,a,span'));
                            buttons.forEach(function(el) {
                                var text = (el.getAttribute && (el.getAttribute('aria-label') || el.getAttribute('title'))) || el.innerText || '';
                                text = String(text).toLowerCase();
                                if (text.indexOf('mute') >= 0 || text.indexOf('unmute') >= 0 || text.indexOf('volume') >= 0 || text.indexOf('sound') >= 0) {
                                    try { el.click(); } catch (e) {}
                                }
                            });
                        }
                        
                        doUnmute();
                        // Repeat a few times as some players initialize late
                        setTimeout(doUnmute, 1000);
                        setTimeout(doUnmute, 2500);
                        setTimeout(doUnmute, 5000);
                        
                        return 'ok';
                    } catch (e) {
                        return 'error';
                    }
                })();
                """.trimIndent(),
                null
            )
        }

        fun centerPlayerInWebUi(view: WebView) {
            view.evaluateJavascript(
                """
                (function() {
                    try {
                        var cssId = 'webui-shaka-center';
                        var css = `
                            html, body {
                                width: 100% !important;
                                height: 100% !important;
                                min-height: 100vh !important;
                                min-height: 100dvh !important;
                                margin: 0 !important;
                                padding: 0 !important;
                                background: black !important;
                                overflow: hidden !important;
                            }
                            .shaka-video-container,
                            .shaka-player-container,
                            .player,
                            .video-container,
                            iframe {
                                width: 100% !important;
                                height: 100% !important;
                                min-height: 100vh !important;
                                min-height: 100dvh !important;
                                max-width: 100% !important;
                                max-height: 100% !important;
                                margin: 0 !important;
                                padding: 0 !important;
                                overflow: hidden !important;
                            }
                            video {
                                width: 100% !important;
                                height: 100% !important;
                                max-width: 100% !important;
                                max-height: 100% !important;
                                object-fit: contain !important;
                                object-position: center center !important;
                                display: block !important;
                                margin: 0 auto !important;
                                opacity: 1 !important;
                                visibility: visible !important;
                                background: black !important;
                            }
                        `;

                        var style = document.getElementById(cssId);
                        if (!style) {
                            style = document.createElement('style');
                            style.id = cssId;
                            document.head.appendChild(style);
                        }
                        style.textContent = css;

                        function viewportHeightPx() {
                            if (window.visualViewport && window.visualViewport.height) {
                                return Math.round(window.visualViewport.height);
                            }
                            return Math.round(window.innerHeight || document.documentElement.clientHeight || 0);
                        }

                        function applyViewportFix() {
                            try {
                                var vh = viewportHeightPx();
                                if (!vh) return;
                                var px = vh + 'px';
                                var html = document.documentElement;
                                var body = document.body;
                                if (!html || !body) return;

                                html.style.height = px;
                                html.style.minHeight = px;
                                html.style.maxHeight = px;
                                html.style.overflow = 'hidden';

                                body.style.height = px;
                                body.style.minHeight = px;
                                body.style.maxHeight = px;
                                body.style.overflow = 'hidden';

                                var selectors = ['.shaka-video-container','.shaka-player-container','.player','.video-container','#player','iframe'];
                                selectors.forEach(function(sel) {
                                    document.querySelectorAll(sel).forEach(function(el) {
                                        el.style.height = px;
                                        el.style.minHeight = px;
                                        el.style.maxHeight = px;
                                        el.style.width = '100%';
                                        el.style.maxWidth = '100%';
                                    });
                                });
                            } catch (e) {}
                        }

                        window.__webUiApplyViewportFix = applyViewportFix;
                        if (!window.__webUiViewportFixInstalled) {
                            window.__webUiViewportFixInstalled = true;
                            window.addEventListener('resize', applyViewportFix, true);
                            window.addEventListener('orientationchange', function() {
                                setTimeout(applyViewportFix, 60);
                                setTimeout(applyViewportFix, 220);
                            }, true);
                            if (window.visualViewport) {
                                window.visualViewport.addEventListener('resize', applyViewportFix, true);
                                window.visualViewport.addEventListener('scroll', applyViewportFix, true);
                            }
                        }

                        setTimeout(applyViewportFix, 0);
                        setTimeout(applyViewportFix, 120);
                        setTimeout(applyViewportFix, 320);
                        window.dispatchEvent(new Event('resize'));
                        setTimeout(function() { window.dispatchEvent(new Event('resize')); }, 120);
                    } catch (e) {}
                })();
                """.trimIndent(),
                null
            )
        }

        fun extractChannelNumbers() {
            webView!!.evaluateJavascript(
                "Array.from(document.querySelectorAll('.card')).map(card => card.getAttribute('href').match(/\\/play\\/(\\d+)/)[1])"
            ) { it: String? ->
                var result = it
                if (!result.isNullOrEmpty()) {
                    result = result.replace("[", "").replace("]", "").replace("\"", "")
                    channelNumbers = listOf(
                        *result.split(",".toRegex()).dropLastWhile { it.isEmpty() }
                            .toTypedArray())
                    Log.d(
                        TAG,
                        "Channel Numbers: $channelNumbers"
                    )
                }
            }
        }

        fun moveSearchInput(view: WebView) {
            view.loadUrl(
                "javascript:(function() { " +
                        "var searchButton = document.getElementById('portexe-search-button'); " +
                        "var searchInput = document.getElementById('portexe-search-input'); " +
                        "if (searchButton && searchInput) { " +
                        "  searchButton.parentNode.insertBefore(searchInput, searchButton.nextSibling); " +
                        "} " +
                        "})()"
            )
        }
    }

    private fun injectTVChannel(channelName: String?, playId: String, logoUrl: String?) {
        val jsCode = "javascript:(function() {" +
                "console.log('Starting channel injection process...');" +
                "var channelGrid = document.querySelector('.grid.grid-cols-2');" +
                "console.log('Attempting to find the channel grid:', channelGrid);" +
                "if (channelGrid) {" +
                "  console.log('Channel grid found:', channelGrid);" +
                "  var existingChannel = document.querySelector('a[href=\"/play/" + playId + "\"]');" +
                "  console.log('Checking for existing channel with playId:', '" + playId + "');" +
                "  if (existingChannel) {" +
                "    console.log('Channel with playId ' + '" + playId + "' + ' already exists, skipping injection.');" +
                "  } else {" +
                "    console.log('Channel does not exist. Proceeding with channel injection...');" +
                "    var newChannel = document.createElement('a');" +
                "    newChannel.href = '/play/" + playId + "';" +
                "    newChannel.className = 'card border-2 border-gold shadow-lg hover:shadow-xl hover:bg-base-300 transition-all duration-200 ease-in-out scale-100 hover:scale-105';" +
                "    var cardContent = `<div class=\"flex flex-col items-center p-2 sm:p-4\">" +
                "      <img src=\"" + logoUrl + "\" loading=\"lazy\" alt=\"" + channelName + "\" class=\"h-14 w-14 sm:h-16 sm:w-16 md:h-18 md:w-18 lg:h-20 lg:w-20 rounded-full bg-gray-200\" />" +
                "      <span class=\"text-lg font-bold mt-2\">" + channelName + "</span>" +
                "      <div class=\"absolute top-2 right-2\">" +
                "        <svg xmlns=\"http://www.w3.org/2000/svg\" width=\"16\" height=\"16\" fill=\"gold\" viewBox=\"0 -960 960 960\">" +
                "        <path d=\"M480-269 314-169q-11 7-23 6t-21-8q-9-7-14-17.5t-2-23.5l44-189-147-127q-10-9-12.5-20.5T140-571q4-11 12-18t22-9l194-17 75-178q5-12 15.5-18t21.5-6q11 0 21.5 6t15.5 18l75 178 194 17q14 2 22 9t12 18q4 11 1.5 22.5T809-528L662-401l44 189q3 13-2 23.5T690-171q-9 7-21 8t-23-6L480-269Z\"/>  " +
                "        </svg>" +
                "      </div>" +
                "    </div>`;" +
                "    newChannel.innerHTML = cardContent;" +
                "    channelGrid.insertBefore(newChannel, channelGrid.firstChild);" +
                "    console.log('Successfully injected new channel:', newChannel);" +
                "  }" +
                "} else {" +
                "  console.log('Failed to find the channel grid. Injection skipped.');" +
                "}" +
                "})()"

        webView!!.evaluateJavascript(jsCode, null)
        Log.d("ChannelInjection", "JavaScript code injected into the WebView.")
    }


    private fun saveRecentChannel(playId: String?, logoUrl: String?, channelName: String?) {
        // Load existing recent channels from preferenceManager
        val recentChannelsJson = prefManager.myPrefs.recentChannelsJson
        if (!recentChannelsJson.isNullOrEmpty()) {
            try {
                val jsonArray = JSONArray(recentChannelsJson)
                recentChannels.clear()
                for (i in 0 until jsonArray.length()) {
                    val jsonObject = jsonArray.getJSONObject(i)
                    recentChannels.add(
                        Channel(
                            jsonObject.getString("playId"),
                            jsonObject.getString("logoUrl"),
                            jsonObject.getString("channelName")
                        )
                    )
                }
            } catch (e: JSONException) {
                Log.d(
                    TAG,
                    "Error loading recent channels: $e"
                )
            }
        }

        // Check if the channel with the given channelName already exists and remove it if found
        val iterator = recentChannels.iterator()
        while (iterator.hasNext()) {
            val channel = iterator.next()
            if (channel.channelName == channelName) {
                iterator.remove()
            }
        }

        recentChannels.add(0, Channel(playId, logoUrl, channelName))

        // Keep only the latest 5 channels
        if (recentChannels.size > 5) {
            recentChannels.removeAt(recentChannels.size - 1)
        }

        // Convert updated list to JSON array and save back to preferences
        val jsonArray = JSONArray()
        for (channel in recentChannels) {
            try {
                val jsonObject = JSONObject()
                jsonObject.put("playId", channel.playId)
                jsonObject.put("logoUrl", channel.logoUrl)
                jsonObject.put("channelName", channel.channelName)
                jsonArray.put(jsonObject)
            } catch (e: JSONException) {
                Log.d(TAG, e.toString())
            }
        }

        prefManager.myPrefs.recentChannelsJson = jsonArray.toString()
        prefManager.savePreferences()
    }

    private fun loadRecentChannels() {
        val channelData = prefManager.myPrefs.recentChannelsJson

        Log.d(
            TAG,
            "Channel Data from Shared Preferences: $channelData"
        )

        if (!channelData.isNullOrEmpty()) {
            recentChannels.clear() // Clear existing list
            try {
                val jsonArray = JSONArray(channelData)

                // Iterate in reverse
                for (i in jsonArray.length() - 1 downTo 0) {
                    val jsonObject = jsonArray.getJSONObject(i)
                    val playId = jsonObject.getString("playId")
                    val logoUrl = jsonObject.getString("logoUrl")
                    val channelName = jsonObject.getString("channelName")

                    // Log each channel's details to confirm parsing
                    Log.d(
                        TAG,
                        "Parsed Channel - Play ID: $playId, Logo URL: $logoUrl, Name: $channelName"
                    )

                    recentChannels.add(Channel(playId, logoUrl, channelName))
                }
            } catch (e: JSONException) {
                Log.e(TAG, "JSON parsing error in loadRecentChannels: " + e.message)
            }
        }

        for (channel in recentChannels) {
            val formattedPlayId = if (!channel.playId!!.endsWith("//")) {
                channel.playId + "//"
            } else {
                channel.playId
            }

            Log.d(
                TAG,
                "Injecting Channel into WebView - Name: ${channel.channelName}, Play ID: $formattedPlayId"
            )

            if (formattedPlayId != null) {
                Log.d(TAG,"injectTVChannel:null")
//                injectTVChannel(channel.channelName, formattedPlayId, channel.logoUrl)
            }
        }

    }
}
