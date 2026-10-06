package org.starfall.multigateway.data.adapter.common

import android.app.Service
import android.content.Intent
import android.os.Binder
import android.os.IBinder

/** Chrome may bind to this service; it exposes no operations or credentials. */
class OAuthBrowserKeepAliveService : Service() {
    override fun onBind(intent: Intent?): IBinder = Binder()
}
