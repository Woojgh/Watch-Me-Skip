package com.example.aiassistant

import android.app.Activity
import android.content.Intent
import android.os.Bundle

class RemoteSkipActionActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        handleAction(intent?.action)
        finish()
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleAction(intent?.action)
        finish()
    }

    private fun handleAction(action: String?) {
        when (action) {
            RemoteSkipController.ACTION_CONFIRM_REMOTE_SKIP -> {
                RemoteSkipController.confirmFromWatch(this)
            }
            RemoteSkipController.ACTION_CONFIRM_REMOTE_SKIP_TEST -> {
                RemoteSkipController.onWatchTestAction(this)
            }
            RemoteSkipController.ACTION_DISMISS_REMOTE_SKIP_TEST -> {
                RemoteSkipController.dismissWatchTest(this)
            }
            RemoteSkipController.ACTION_DISMISS_REMOTE_SKIP -> {
                RemoteSkipController.clearPending(this, "dismissed")
            }
        }
    }
}
