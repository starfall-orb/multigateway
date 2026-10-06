package org.starfall.multigateway.ui.auth

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.TextView
import androidx.browser.customtabs.CustomTabsIntent
import net.openid.appauth.AuthorizationService
import kotlinx.coroutines.*
import org.starfall.multigateway.R
import org.starfall.multigateway.data.adapter.common.OAuthBrowser
import org.starfall.multigateway.data.adapter.common.OAuthBrowserKeepAliveService

/** Hosts a browser Custom Tab in the app's task, so completing OAuth can close it automatically. */
class OAuthBrowserActivity : Activity() {
    private var sessionId = ""
    private var authorizationService: AuthorizationService? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var stoppedForBrowser = false
    private var launched = false
    private var completing = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        sessionId = intent.getStringExtra(SESSION).orEmpty()
        if (intent.getBooleanExtra(COMPLETE, false) || !OAuthBrowser.isActive(sessionId)) { finish(); return }
        setContentView(TextView(this).apply { text = getString(R.string.oauth_opening_browser); setPadding(32, 64, 32, 32) })
        launched = savedInstanceState?.getBoolean("launched") == true
        if (launched) { stoppedForBrowser = true; return }
        val url = Uri.parse(intent.getStringExtra(URL).orEmpty())
        if (url.scheme != "https" || url.host.isNullOrBlank()) { OAuthBrowser.cancel(sessionId); finish(); return }
        val service = AuthorizationService(this)
        authorizationService = service
        val browser = service.browserDescriptor
        if (browser == null) {
            android.widget.Toast.makeText(this, R.string.oauth_browser_unavailable, android.widget.Toast.LENGTH_LONG).show()
            OAuthBrowser.cancel(sessionId); finish(); return
        }
        scope.launch {
            // AppAuth owns browser service binding/warmup. Building the session
            // can wait for its service connection, so do that off the UI thread.
            val tab = withContext(Dispatchers.IO) {
                service.createCustomTabsIntentBuilder(url).setShowTitle(true)
                    .setShareState(CustomTabsIntent.SHARE_STATE_OFF).build()
            }
            if (isFinishing || !OAuthBrowser.isActive(sessionId)) return@launch
            launched = true
            tab.intent.setPackage(browser.packageName)
            tab.intent.putExtra("android.support.customtabs.extra.KEEP_ALIVE", Intent(this@OAuthBrowserActivity, OAuthBrowserKeepAliveService::class.java))
            try { tab.launchUrl(this@OAuthBrowserActivity, url) }
            catch (_: Exception) { OAuthBrowser.cancel(sessionId); finish() }
        }
    }

    override fun onStop() {
        super.onStop()
        if (launched) stoppedForBrowser = true
    }

    override fun onResume() {
        super.onResume()
        if (stoppedForBrowser && !completing) {
            // Resume is not evidence that authorization was cancelled. It can
            // happen during a browser handoff or before token exchange/device
            // polling finishes. Return to the app; its explicit Cancel action
            // and the authorization timeout remain authoritative.
            finish()
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        if (intent.getBooleanExtra(COMPLETE, false) && intent.getStringExtra(SESSION) == sessionId) {
            completing = true
            finish()
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putBoolean("launched", launched)
        super.onSaveInstanceState(outState)
    }

    override fun onDestroy() {
        scope.cancel()
        authorizationService?.dispose()
        super.onDestroy()
    }

    companion object {
        const val SESSION = "oauth_session"
        const val URL = "oauth_url"
        const val COMPLETE = "oauth_complete"
    }
}
