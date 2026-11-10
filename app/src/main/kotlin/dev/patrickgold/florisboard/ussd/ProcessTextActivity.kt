package dev.patrickgold.florisboard.ussd

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import dev.patrickgold.florisboard.scanner.MobileMoneyParser

class ProcessTextActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // 1. Get the highlighted text from the OS
        val highlightedText = intent.getCharSequenceExtra(Intent.EXTRA_PROCESS_TEXT)?.toString() ?: ""

        if (highlightedText.isNotBlank()) {
            // 2. Feed it into your existing Vision/Camera parser
            val parsedResult = MobileMoneyParser.parse(highlightedText)

            if (parsedResult.type != TransactionType.UNKNOWN) {
                // 3. We found payment details! Launch the USSD Confirmation UI
                val ussdIntent = Intent(this, UssdConfirmationActivity::class.java).apply {
                    putExtra("RAW_OCR_TEXT", highlightedText)
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                }
                startActivity(ussdIntent)
            } else {
                Toast.makeText(this, "No valid payment details found in selection.", Toast.LENGTH_SHORT).show()
            }
        } else {
            Toast.makeText(this, "No text selected.", Toast.LENGTH_SHORT).show()
        }

        // Close this invisible activity immediately
        finish()
    }
}
