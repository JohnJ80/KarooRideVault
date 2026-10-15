package com.ridevault.karoo.ui

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.net.Uri
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import timber.log.Timber

/**
 * In-app WebView for Strava OAuth.
 *
 * Opens the Strava authorization page inside the app, handles
 * login, and intercepts the redirect to extract the auth code.
 * This avoids issues with the Karoo's system browser and
 * eliminates the need for a phone.
 */
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun StravaWebViewAuth(
    authUrl: String,
    onCodeReceived: (String) -> Unit,
    onError: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    AndroidView(
        modifier = modifier.fillMaxSize(),
        factory = { context ->
            WebView(context).apply {
                layoutParams = ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT,
                )

                settings.apply {
                    javaScriptEnabled = true
                    domStorageEnabled = true
                    // Full desktop user agent — no "Android" or "Mobile" so Strava
                    // serves the desktop login page without "get the app" banners
                    userAgentString = "Mozilla/5.0 (X11; Linux x86_64) " +
                        "AppleWebKit/537.36 (KHTML, like Gecko) " +
                        "Chrome/120.0.0.0 Safari/537.36"
                    // Desktop viewport so the page lays out at desktop width
                    useWideViewPort = true
                    loadWithOverviewMode = true
                    // Allow pinch-zoom on the small Karoo screen
                    builtInZoomControls = true
                    displayZoomControls = false
                }

                // Enable cookies for Strava login session
                val webView = this
                CookieManager.getInstance().apply {
                    setAcceptCookie(true)
                    setAcceptThirdPartyCookies(webView, true)
                }

                webViewClient = object : WebViewClient() {
                    override fun onPageStarted(
                        view: WebView?,
                        url: String?,
                        favicon: Bitmap?,
                    ) {
                        super.onPageStarted(view, url, favicon)
                        // Inject CSS early to hide app-download banners
                        view?.evaluateJavascript(
                            """
                            (function() {
                                var style = document.createElement('style');
                                style.textContent = '[class*="app-banner"], [class*="AppBanner"], ' +
                                    '[class*="smart-banner"], [class*="SmartBanner"], ' +
                                    '[id*="app-banner"], [id*="branch-banner"], ' +
                                    '.btn-get-app, .mobile-app-banner { display: none !important; }';
                                document.head.appendChild(style);
                            })();
                            """.trimIndent(),
                            null,
                        )
                    }

                    override fun onPageFinished(view: WebView?, url: String?) {
                        super.onPageFinished(view, url)
                        // Remove app-download banners after page loads
                        view?.evaluateJavascript(
                            """
                            (function() {
                                var selectors = [
                                    '[class*="app-banner"]', '[class*="AppBanner"]',
                                    '[class*="smart-banner"]', '[class*="SmartBanner"]',
                                    '[id*="app-banner"]', '[id*="branch-banner"]',
                                    '.btn-get-app', '.mobile-app-banner'
                                ];
                                selectors.forEach(function(sel) {
                                    document.querySelectorAll(sel).forEach(function(el) {
                                        el.remove();
                                    });
                                });
                            })();
                            """.trimIndent(),
                            null,
                        )
                    }

                    override fun shouldOverrideUrlLoading(
                        view: WebView?,
                        request: WebResourceRequest?,
                    ): Boolean {
                        val url = request?.url?.toString() ?: return false
                        return handleUrl(url)
                    }

                    @Deprecated("For older API levels")
                    override fun shouldOverrideUrlLoading(
                        view: WebView?,
                        url: String?,
                    ): Boolean {
                        return url?.let { handleUrl(it) } ?: false
                    }

                    private fun handleUrl(url: String): Boolean {
                        // Intercept the redirect to localhost
                        if (url.startsWith("http://localhost/ridevault")) {
                            val uri = Uri.parse(url)
                            val code = uri.getQueryParameter("code")
                            val error = uri.getQueryParameter("error")

                            if (code != null) {
                                Timber.i("Intercepted Strava auth code from WebView")
                                onCodeReceived(code)
                            } else {
                                val msg = error ?: "No authorization code received"
                                Timber.e("Strava WebView auth error: $msg")
                                onError(msg)
                            }
                            return true // Don't load the localhost URL
                        }
                        return false // Let normal URLs load
                    }
                }

                loadUrl(authUrl)
            }
        },
    )
}
