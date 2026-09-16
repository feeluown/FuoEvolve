package org.feeluown.mobile

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioManager

/** Observes system headphone disconnects for the lifetime of the playback service. */
internal class AndroidHeadphoneDisconnectMonitor(
    private val context: Context,
    private val onHeadphonesDisconnected: () -> Unit,
) : AutoCloseable {
    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action == AudioManager.ACTION_AUDIO_BECOMING_NOISY) {
                onHeadphonesDisconnected()
            }
        }
    }

    init {
        context.registerReceiver(receiver, IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY))
    }

    override fun close() {
        context.unregisterReceiver(receiver)
    }
}
