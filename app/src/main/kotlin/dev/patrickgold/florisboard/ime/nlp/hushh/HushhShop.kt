package dev.patrickgold.florisboard.ime.nlp.hushh

import android.content.Context
import android.content.Intent
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ShoppingBag
import androidx.compose.ui.graphics.vector.ImageVector
import dev.patrickgold.florisboard.ime.nlp.SuggestionCandidate
import dev.patrickgold.florisboard.ime.nlp.SuggestionProvider
import dev.patrickgold.florisboard.money.MoneyAi
import dev.patrickgold.florisboard.ussd.UssdConfirmationActivity

/**
 * "get me the Sony WH-1000XM5" typed in a chat -> a Smartbar chip "Shop · Sony WH-1000XM5".
 * Tapping it opens HushhBoard Pay with Channel3's live prices, ready to pay or request with PayPal.
 */
data class HushhShopCandidate(val query: String) : SuggestionCandidate {
    override val text: CharSequence = query
    override val secondaryText: CharSequence = "Prices · PayPal"
    override val confidence: Double = 1.0
    override val isEligibleForAutoCommit: Boolean = false
    override val isEligibleForUserRemoval: Boolean = true
    override val icon: ImageVector = Icons.Rounded.ShoppingBag
    override val sourceProvider: SuggestionProvider? = null

    companion object {
        private val dismissed = mutableSetOf<String>()

        /** Looks only at the sentence being typed, so old messages in the field don't trigger it. */
        fun from(textBeforeCursor: CharSequence): HushhShopCandidate? {
            val tail = textBeforeCursor.takeLast(140).toString()
            val sentence = tail.split(Regex("[.!?\\n]\\s")).last()
            val q = MoneyAi.shoppingQuery(sentence) ?: return null
            if (q.length < 4 || q.lowercase() in dismissed) return null
            return HushhShopCandidate(q)
        }

        fun dismiss(query: String) { dismissed += query.lowercase() }

        fun open(context: Context, query: String) {
            dismiss(query)
            context.startActivity(
                Intent(context, UssdConfirmationActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    .putExtra(UssdConfirmationActivity.EXTRA_SHOP_QUERY, query)
            )
        }
    }
}
