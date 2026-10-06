package org.starfall.multigateway.data.adapter.common

import android.content.Context
import android.content.Intent
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext
import org.starfall.multigateway.ui.auth.OAuthBrowserActivity

/** A browser window belongs to one cancellable authorization, including device-code polling. */
internal object OAuthBrowser {
    private class Session(val id: String, val job: Job) : AbstractCoroutineContextElement(Key) {
        var opened = false
        companion object Key : CoroutineContext.Key<Session>
    }
    private val browserLock = Mutex()
    private val sessions = ConcurrentHashMap<String, Session>()

    suspend fun <T> withSession(context: Context, block: suspend () -> T): T {
        if (currentCoroutineContext()[Session] != null) return block()
        return browserLock.withLock {
            coroutineScope {
                val session = Session(UUID.randomUUID().toString(), currentCoroutineContext().job)
                sessions[session.id] = session
                try { withContext(session) { block() } }
                finally {
                    sessions.remove(session.id)
                    if (session.opened) withContext(NonCancellable + Dispatchers.Main) {
                        runCatching {
                            context.startActivity(Intent(context, OAuthBrowserActivity::class.java)
                                .putExtra(OAuthBrowserActivity.SESSION, session.id)
                                .putExtra(OAuthBrowserActivity.COMPLETE, true)
                                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP))
                        }
                    }
                }
            }
        }
    }

    suspend fun open(context: Context, url: String) = withContext(Dispatchers.Main) {
        val session = currentCoroutineContext()[Session] ?: error("OAuth browser requires an authorization session")
        context.startActivity(Intent(context, OAuthBrowserActivity::class.java)
            .putExtra(OAuthBrowserActivity.SESSION, session.id).putExtra(OAuthBrowserActivity.URL, url)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        session.opened = true
    }

    fun isActive(id: String) = sessions.containsKey(id)
    fun cancel(id: String) { sessions[id]?.job?.cancel(CancellationException("Sign-in window closed")) }
}
