package org.feeluown.mobile

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch

class ShareReceiverActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val sharedText = sharedTextFrom(intent)
        if (sharedText.isNullOrBlank()) {
            openMainActivity()
            return
        }
        lifecycleScope.launch {
            openMainActivity(resolveAndroidSharedText(sharedText))
        }
    }

    private fun sharedTextFrom(intent: Intent?): String? {
        val action = intent?.action ?: return null
        val text = when (action) {
            Intent.ACTION_SEND -> intent.getCharSequenceExtra(Intent.EXTRA_TEXT)?.toString()
            Intent.ACTION_PROCESS_TEXT -> intent.getCharSequenceExtra(Intent.EXTRA_PROCESS_TEXT)?.toString()
            else -> null
        }?.takeIf { it.isNotBlank() }
        val subject = intent.getCharSequenceExtra(Intent.EXTRA_SUBJECT)
            ?.toString()
            ?.takeIf { it.isNotBlank() && !text.orEmpty().contains(it) }
        return listOfNotNull(subject, text).joinToString("\n").takeIf { it.isNotBlank() }
    }

    private fun openMainActivity(sharedText: String? = null) {
        val mainIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
            if (sharedText != null) {
                action = Intent.ACTION_SEND
                type = "text/plain"
                putExtra(Intent.EXTRA_TEXT, sharedText)
            } else {
                action = Intent.ACTION_MAIN
            }
        }
        startActivity(mainIntent)
        finish()
    }
}
