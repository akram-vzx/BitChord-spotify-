package com.music.bitchord.auth

import android.annotation.SuppressLint
import android.view.View
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import com.music.bitchord.data.DebugLog as Log
import com.music.bitchord.data.settings.AppSettings

private const val SPOTIFY_LOGIN_TAG = "SpotifyLogin"

/** Spotify's own login page; after signing in it sends you on to the web player. */
private const val SPOTIFY_LOGIN_URL =
    "https://accounts.spotify.com/en/login?continue=https%3A%2F%2Fopen.spotify.com%2F"

private val SPOTIFY_COOKIE_URLS = listOf(
    "https://open.spotify.com/",
    "https://accounts.spotify.com/",
)

private val SPOTIFY_WEBVIEW_VERSION_TOKEN = Regex("""Version/\d+(\.\d+)*\s*""")

private const val SPOTIFY_FALLBACK_USER_AGENT =
    "Mozilla/5.0 (Linux; Android 14; SM-S921U; Build/UP1A.231005.007) " +
        "AppleWebKit/537.36 (KHTML, like Gecko) Chrome/129.0.0.0 Mobile Safari/537.36"

/**
 * Makes the WebView look like a normal mobile Chrome. Login pages (and Google's
 * "Continue with Google" button) often refuse or break on the "; wv" and
 * "Version/4.0" markers that every Android WebView carries.
 */
private fun spotifyBrowserUserAgent(platformUserAgent: String?): String {
    val stripped = platformUserAgent
        ?.replace("; wv", "")
        ?.replace(SPOTIFY_WEBVIEW_VERSION_TOKEN, "")
        ?.replace("  ", " ")
        ?.trim()
    return stripped?.takeIf { it.contains("Chrome/") } ?: SPOTIFY_FALLBACK_USER_AGENT
}

/** Pulls the `sp_dc` value out of the WebView's cookie jar, or null if absent. */
private fun readSpotifySpdc(): String? {
    val manager = CookieManager.getInstance()
    for (url in SPOTIFY_COOKIE_URLS) {
        val raw = manager.getCookie(url) ?: continue
        val value = raw.split(";")
            .map { it.trim() }
            .firstOrNull { it.startsWith("sp_dc=") }
            ?.removePrefix("sp_dc=")
            ?.trim()
        if (!value.isNullOrBlank()) return value
    }
    return null
}

/**
 * Expires any old sp_dc left in the shared cookie jar (for example one pasted
 * by hand earlier), so the screen waits for a genuinely new sign-in instead of
 * "succeeding" instantly with a stale cookie. Only sp_dc is touched; Google /
 * YouTube cookies in the same jar are left alone.
 */
private fun expireStaleSpotifySpdc() {
    val manager = CookieManager.getInstance()
    for (url in SPOTIFY_COOKIE_URLS) {
        manager.setCookie(url, "sp_dc=; Max-Age=0; Domain=.spotify.com; Path=/; Secure")
    }
    manager.flush()
}

/**
 * In-app Spotify sign-in.
 *
 * Opens Spotify's real login page, so passwords, 2FA and everything else are
 * handled by Spotify itself. As soon as the session cookie (`sp_dc`) exists it
 * is saved through [AppSettings.setSpotifySpdcToken] — the same place the
 * manual "paste your cookie" box writes to, which is what the Canvas code and
 * any future Spotify library features read from — and [onSignedIn] is called
 * once.
 */
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun SpotifyLoginScreen(
    onSignedIn: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var webView by remember { mutableStateOf<WebView?>(null) }
    var canGoBack by remember { mutableStateOf(false) }

    AndroidView(
        modifier = modifier.fillMaxSize(),
        factory = { context ->
            WebView(context).apply {
                layoutParams = ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT,
                )

                settings.javaScriptEnabled = true
                settings.domStorageEnabled = true
                settings.userAgentString = spotifyBrowserUserAgent(settings.userAgentString)

                CookieManager.getInstance().setAcceptCookie(true)
                CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)
                expireStaleSpotifySpdc()

                var done = false

                fun checkLogin() {
                    if (done) return
                    val spdc = readSpotifySpdc() ?: return
                    done = true
                    Log.d(SPOTIFY_LOGIN_TAG, "sp_dc cookie captured")
                    AppSettings.setSpotifySpdcToken(spdc)
                    this@apply.visibility = View.GONE
                    onSignedIn()
                }

                webViewClient = object : WebViewClient() {
                    override fun doUpdateVisitedHistory(
                        view: WebView?,
                        url: String?,
                        isReload: Boolean,
                    ) {
                        checkLogin()
                    }

                    override fun onPageFinished(view: WebView?, url: String?) {
                        canGoBack = view?.canGoBack() == true
                        checkLogin()
                    }
                }

                webView = this
                loadUrl(SPOTIFY_LOGIN_URL)
            }
        },
    )

    BackHandler(enabled = canGoBack) {
        webView?.goBack()
    }
}
