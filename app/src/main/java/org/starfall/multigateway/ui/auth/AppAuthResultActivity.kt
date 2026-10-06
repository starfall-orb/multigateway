package org.starfall.multigateway.ui.auth

import android.app.Activity
import android.os.Bundle
import org.starfall.multigateway.data.adapter.common.StandardAppAuthAuthorization

/** AppAuth supplies either its validated response or cancellation error through the PendingIntent. */
class AppAuthResultActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        StandardAppAuthAuthorization.complete(intent)
        finish()
    }
}
