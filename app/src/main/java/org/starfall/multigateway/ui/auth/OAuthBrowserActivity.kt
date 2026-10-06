package org.starfall.multigateway.ui.auth

import android.app.Activity
import android.content.ComponentName
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.widget.TextView
import androidx.browser.customtabs.*
import org.starfall.multigateway.R
import org.starfall.multigateway.data.adapter.common.OAuthBrowser
import org.starfall.multigateway.data.adapter.common.OAuthBrowserKeepAliveService

/** Hosts a browser Custom Tab in the app's task, so completing OAuth can close it automatically. */
class OAuthBrowserActivity : Activity() {
    private var sessionId = ""
    private var connection: CustomTabsServiceConnection? = null
    private var stoppedForBrowser = false
    private var launched = false
    private val handler = Handler(Looper.getMainLooper())
    private var fallback: Runnable? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        sessionId = intent.getStringExtra(SESSION).orEmpty()
        if (intent.getBooleanExtra(COMPLETE, false) || !OAuthBrowser.isActive(sessionId)) { finish(); return }
        setContentView(TextView(this).apply { text = getString(R.string.oauth_opening_browser); setPadding(32, 64, 32, 32) })
        launched = savedInstanceState?.getBoolean("launched") == true
        if (launched) { stoppedForBrowser = true; return }
        val url = Uri.parse(intent.getStringExtra(URL).orEmpty())
        if (url.scheme != "https" || url.host.isNullOrBlank()) { OAuthBrowser.cancel(sessionId); finish(); return }
        // Prefer Chrome to reuse its existing sign-in cookies; otherwise use a Custom Tabs browser.
        val browser = CustomTabsClient.getPackageName(this, listOf("com.android.chrome", "com.chrome.beta", "com.chrome.dev"), true)
            ?: CustomTabsClient.getPackageName(this, null)
        if (browser == null) {
            android.widget.Toast.makeText(this, R.string.oauth_browser_unavailable, android.widget.Toast.LENGTH_LONG).show()
            OAuthBrowser.cancel(sessionId); finish(); return
        }
        fun launch(session: CustomTabsSession?) {
            if (launched || isFinishing || !OAuthBrowser.isActive(sessionId)) return
            launched = true
            fallback?.let(handler::removeCallbacks)
            val tab = CustomTabsIntent.Builder(session).setShowTitle(true)
                .setShareState(CustomTabsIntent.SHARE_STATE_OFF).build()
            tab.intent.setPackage(browser)
            // Chrome binds this inert service while the tab is visible, raising the app's process
            // priority. This complements the foreground service and CPU wake lock during OAuth.
            tab.intent.putExtra("android.support.customtabs.extra.KEEP_ALIVE",
                Intent(this, OAuthBrowserKeepAliveService::class.java))
            try { tab.launchUrl(this, url) }
            catch (_: Exception) { OAuthBrowser.cancel(sessionId); finish() }
        }
        connection = object : CustomTabsServiceConnection() {
            override fun onCustomTabsServiceConnected(name: ComponentName, client: CustomTabsClient) {
                client.warmup(0L)
                launch(client.newSession(CustomTabsCallback()))
            }
            override fun onServiceDisconnected(name: ComponentName) { }
        }
        if (!CustomTabsClient.bindCustomTabsService(this, browser, connection!!)) {
            connection = null
            launch(null)
        } else {
            fallback = Runnable { launch(null) }.also { handler.postDelayed(it, 1500) }
        }
    }

    override fun onStop() {
        super.onStop()
        if (launched) stoppedForBrowser = true
    }

    override fun onResume() {
        super.onResume()
        if (stoppedForBrowser) {
            if (OAuthBrowser.isActive(sessionId)) OAuthBrowser.cancel(sessionId)
            finish()
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        if (intent.getBooleanExtra(COMPLETE, false) && intent.getStringExtra(SESSION) == sessionId) finish()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putBoolean("launched", launched)
        super.onSaveInstanceState(outState)
    }

    override fun onDestroy() {
        fallback?.let(handler::removeCallbacks)
        connection?.let { runCatching { unbindService(it) } }
        if (isFinishing && OAuthBrowser.isActive(sessionId)) OAuthBrowser.cancel(sessionId)
        super.onDestroy()
    }

    companion object {
        const val SESSION = "oauth_session"
        const val URL = "oauth_url"
        const val COMPLETE = "oauth_complete"
    }
}
