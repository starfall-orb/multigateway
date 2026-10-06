package org.starfall.multigateway

import android.app.Application
import android.content.Intent
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import org.starfall.multigateway.data.adapter.common.OAuthBrowser
import org.starfall.multigateway.ui.auth.OAuthBrowserActivity

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
@LooperMode(LooperMode.Mode.PAUSED)
class OAuthBrowserSessionTest {
    @Test fun completingNestedAuthorizationClosesItsWindowAndReturnsToAppTask() {
        exerciseSession(cancel = false)
    }

    @Test fun closingSignInCancelsAuthorizationAndClosesItsWindow() {
        exerciseSession(cancel = true)
    }

    private fun exerciseSession(cancel: Boolean) {
        val app = ApplicationProvider.getApplicationContext<Application>()
        val application = shadowOf(app)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val opened = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val request = scope.async {
            OAuthBrowser.withSession(app) {
                // Repository and adapter keep-alive scopes must own the same browser session.
                OAuthBrowser.withSession(app) {
                    OAuthBrowser.open(app, "https://example.test/sign-in")
                    opened.complete(Unit)
                    release.await()
                }
            }
        }
        try {
            awaitMain { opened.isCompleted }
            val open = application.nextStartedActivity
            assertEquals(OAuthBrowserActivity::class.java.name, open.component!!.className)
            assertEquals("https://example.test/sign-in", open.getStringExtra(OAuthBrowserActivity.URL))
            val id = open.getStringExtra(OAuthBrowserActivity.SESSION)!!
            assertTrue(OAuthBrowser.isActive(id))
            if (cancel) OAuthBrowser.cancel(id) else release.complete(Unit)
            awaitMain { request.isCompleted }
            assertEquals(cancel, request.isCancelled)
            assertFalse(OAuthBrowser.isActive(id))
            val close = application.nextStartedActivity
            assertTrue(close.getBooleanExtra(OAuthBrowserActivity.COMPLETE, false))
            assertEquals(id, close.getStringExtra(OAuthBrowserActivity.SESSION))
            assertTrue(close.flags and Intent.FLAG_ACTIVITY_CLEAR_TOP != 0)
            assertTrue(close.flags and Intent.FLAG_ACTIVITY_SINGLE_TOP != 0)
            assertNull(application.nextStartedActivity)
        } finally { scope.cancel(); shadowOf(Looper.getMainLooper()).idle() }
    }

    private fun awaitMain(condition: () -> Boolean) {
        val deadline = System.nanoTime() + 5_000_000_000L
        while (!condition() && System.nanoTime() < deadline) {
            shadowOf(Looper.getMainLooper()).idle()
            Thread.sleep(10)
        }
        assertTrue("Authorization did not complete", condition())
    }
}
