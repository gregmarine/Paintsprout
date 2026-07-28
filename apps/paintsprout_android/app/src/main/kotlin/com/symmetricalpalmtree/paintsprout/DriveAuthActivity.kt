package com.symmetricalpalmtree.paintsprout

import android.annotation.SuppressLint
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.google.android.material.button.MaterialButton
import com.symmetricalpalmtree.paintsprout.data.backup.DriveApiClient
import com.symmetricalpalmtree.paintsprout.data.backup.DriveAuth
import com.symmetricalpalmtree.paintsprout.data.backup.DriveTokenStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt

/**
 * Google's consent page, in a WebView, with the redirect caught on its way out.
 *
 * There is no server at `http://localhost/oauth2callback` and there does not need
 * to be: the WebView is asked to load it, we intercept the request, and the
 * authorization code is sitting in the query string. That is the whole reason
 * this can work with no Play Services and no listening socket.
 *
 * Returns `RESULT_OK` with [EXTRA_EMAIL] on success; `RESULT_CANCELED` on
 * anything else, including a back press.
 */
class DriveAuthActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_EMAIL = "drive_auth_email"
    }

    private var webView: WebView? = null
    private var codeVerifier: String? = null

    /** The redirect can fire more than once; the code is only good the first time. */
    private var handled = false

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        if (!DriveAuth.isConfigured()) {
            Toast.makeText(this, getString(R.string.backup_drive_unconfigured), Toast.LENGTH_LONG).show()
            setResult(RESULT_CANCELED)
            finish()
            return
        }

        setContentView(buildUi())

        val verifier = DriveAuth.generateCodeVerifier()
        codeVerifier = verifier
        webView?.loadUrl(DriveAuth.buildAuthUrl(DriveAuth.generateCodeChallenge(verifier)))
    }

    private fun buildUi(): View {
        val web = WebView(this).apply {
            settings.javaScriptEnabled = true
            // Google refuses OAuth in a WebView that identifies itself as one
            // (`disallowed_useragent`), so it is told it is Chrome. The flow is
            // otherwise unchanged — this is about the string, not the engine.
            settings.userAgentString =
                "Mozilla/5.0 (Linux; Android 10; Mobile) AppleWebKit/537.36 " +
                    "(KHTML, like Gecko) Chrome/124.0.0.0 Mobile Safari/537.36"
            webViewClient = object : WebViewClient() {
                override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                    val url = request.url
                    if (url.host != "localhost" || url.path != "/oauth2callback") return false
                    if (!handled) {
                        handled = true
                        val code = url.getQueryParameter("code")
                        if (code != null) {
                            exchange(code)
                        } else {
                            Toast.makeText(
                                this@DriveAuthActivity,
                                getString(
                                    R.string.backup_drive_auth_failed,
                                    url.getQueryParameter("error") ?: "",
                                ),
                                Toast.LENGTH_LONG,
                            ).show()
                            setResult(RESULT_CANCELED)
                            finish()
                        }
                    }
                    return true
                }
            }
        }
        webView = web

        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(12), dp(12), dp(20), dp(4))
            addView(
                MaterialButton(
                    this@DriveAuthActivity, null, com.google.android.material.R.attr.borderlessButtonStyle,
                ).apply {
                    text = getString(android.R.string.cancel)
                    setOnClickListener {
                        setResult(RESULT_CANCELED)
                        finish()
                    }
                },
            )
            addView(
                TextView(this@DriveAuthActivity).apply {
                    text = getString(R.string.backup_drive_connect)
                    textSize = 20f
                    setTextColor(Color.BLACK)
                },
            )
        }

        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(0xFFFAF9F6.toInt())
            addView(header)
            addView(web, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        }
    }

    private fun exchange(code: String) {
        val verifier = codeVerifier ?: run {
            setResult(RESULT_CANCELED)
            finish()
            return
        }
        lifecycleScope.launch {
            val tokens = withContext(Dispatchers.IO) { DriveAuth.exchangeCodeForTokens(code, verifier) }
            if (tokens == null) {
                Toast.makeText(
                    this@DriveAuthActivity,
                    getString(R.string.backup_drive_auth_failed, ""),
                    Toast.LENGTH_LONG,
                ).show()
                setResult(RESULT_CANCELED)
                finish()
                return@launch
            }
            val (accessToken, refreshToken) = tokens
            if (refreshToken != null) {
                DriveTokenStore.storeRefreshToken(this@DriveAuthActivity, refreshToken)
            } else {
                // Shouldn't happen with prompt=consent, and is survivable if it
                // does: backup works until the access token expires, then asks
                // to reconnect.
                Toast.makeText(
                    this@DriveAuthActivity,
                    getString(R.string.backup_drive_no_refresh),
                    Toast.LENGTH_LONG,
                ).show()
            }

            // The email is a label, not a credential — but a null one reads as
            // "not connected" and silently disables Drive backup, so a transient
            // failure fetching it must not be allowed to null it out.
            val email = withContext(Dispatchers.IO) { DriveApiClient(accessToken).accountEmail() }
                ?: getString(R.string.backup_drive_connected_generic)
            setResult(RESULT_OK, Intent().putExtra(EXTRA_EMAIL, email))
            finish()
        }
    }

    override fun onDestroy() {
        // Explicitly, or it keeps loading — and leaking — after the flow is over.
        webView?.let {
            it.stopLoading()
            it.destroy()
        }
        webView = null
        super.onDestroy()
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).roundToInt()
}
