package com.example.aiassistant

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

class RemoteSkipActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        when (intent?.action) {
            RemoteSkipController.ACTION_CONFIRM_REMOTE_SKIP -> {
                RemoteSkipController.confirmFromWatch(context)
            }
            RemoteSkipController.ACTION_CONFIRM_REMOTE_SKIP_TEST -> {
                RemoteSkipController.onWatchTestAction(context)
            }
            RemoteSkipController.ACTION_DISMISS_REMOTE_SKIP_TEST -> {
                RemoteSkipController.dismissWatchTest(context)
            }
            RemoteSkipController.ACTION_DISMISS_REMOTE_SKIP -> {
                RemoteSkipController.clearPending(context, "dismissed")
            }
        }
    }
}
