package com.ridevault.karoo.ui

import android.annotation.SuppressLint
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
                    // Use desktop user agent to avoid mobile "get the app" prompts
                    userAgentString = "Mozilla/5.0 (Linux; Android) AppleWebKit/537.36 " +
                        "(KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"
                }

                // Enable cookies for Strava login session
                CookieManager.getInstance().apply {
                    setAcceptCookie(true)
                    setAcceptThirdPartyCookies(this@apply, true)
                }

                webViewClient = object : WebViewClient() {
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
