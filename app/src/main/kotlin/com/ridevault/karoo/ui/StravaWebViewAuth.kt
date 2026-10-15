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
                    // Desktop user agent so Strava skips "get the app" banners,
                    // but keep mobile viewport so the page fits the Karoo screen
                    userAgentString = "Mozilla/5.0 (X11; Linux x86_64) " +
                        "AppleWebKit/537.36 (KHTML, like Gecko) " +
                        "Chrome/120.0.0.0 Safari/537.36"
                    useWideViewPort = false
                    loadWithOverviewMode = false
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
                        // Remove banners/headers/footers and make the login form
                        // fill the small Karoo screen
                        view?.evaluateJavascript(
                            """
                            (function() {
                                // Remove app-download banners
                                var selectors = [
                                    '[class*="app-banner"]', '[class*="AppBanner"]',
                                    '[class*="smart-banner"]', '[class*="SmartBanner"]',
                                    '[id*="app-banner"]', '[id*="branch-banner"]',
                                    '.btn-get-app', '.mobile-app-banner',
                                    // Also hide nav/header/footer to save screen space
                                    'header', 'nav', 'footer',
                                    '[class*="Header"]', '[class*="header"]',
                                    '[class*="Footer"]', '[class*="footer"]',
                                    '[class*="Nav"]'
                                ];
                                selectors.forEach(function(sel) {
                                    document.querySelectorAll(sel).forEach(function(el) {
                                        el.style.display = 'none';
                                    });
                                });
                                // Scroll to the first input field
                                var firstInput = document.querySelector('input[type="email"], input[type="text"], input[name="email"]');
                                if (firstInput) {
                                    firstInput.scrollIntoView({behavior: 'smooth', block: 'center'});
                                }
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
