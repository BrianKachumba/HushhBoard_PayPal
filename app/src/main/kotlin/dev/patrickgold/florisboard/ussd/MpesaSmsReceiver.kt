package dev.patrickgold.florisboard.ussd

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.provider.Telephony
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Runs in the ":hushh" process (see AndroidManifest.xml), so incoming SMS never wake up
 * Room/SQLite inside the keyboard's process.
 */
class MpesaSmsReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Telephony.Sms.Intents.SMS_RECEIVED_ACTION) return

        // Cheap filtering on the main thread; parsing + DB work happens off it.
        val mpesaMessages = Telephony.Sms.Intents.getMessagesFromIntent(intent)
            .filter { it.displayOriginatingAddress?.equals("MPESA", ignoreCase = true) == true }
            .mapNotNull { sms -> sms.displayMessageBody?.let { body -> body to sms.timestampMillis } }
        if (mpesaMessages.isEmpty()) return

        // goAsync() keeps this process alive until the insert has finished. Without it Android
        // may kill the (otherwise idle) ":hushh" process right after onReceive() returns.
        val pending = goAsync()
        val appContext = context.applicationContext
        ReceiverScope.launch {
            try {
                val dao = AppDatabase.getDatabase(appContext).transactionDao()
                for ((body, timestamp) in mpesaMessages) {
                    val parsedTx = MpesaParser.parseSms(body, timestamp) ?: continue
                    dao.insertTransaction(parsedTx)
                }
            } finally {
                pending.finish()
            }
        }
    }

    private companion object {
        val ReceiverScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    }
}
