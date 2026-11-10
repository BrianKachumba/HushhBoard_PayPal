package dev.patrickgold.florisboard.money

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri

/** What the user is paying FROM (a row in the pay screen's "Payee" list). */
enum class SourceKind { MPESA, AIRTEL, BANK, PAYPAL }

/** What the pay screen tab is. Mirrors ussd.TransactionType without depending on it. */
enum class PayTab { PHONE, TILL, PAYBILL, BANK }

/** In the Bank tab the receiver is either a bank account or a PayPal account. */
enum class BankDestination { BANK_ACCOUNT, PAYPAL }

/** How a transfer is labelled to the user. */
enum class Flow(val label: String) { WITHDRAWAL("Withdrawal"), CREDIT("Credit"), PAYMENT("Payment") }

/** Apps we hand the user over to when a partner API isn't available to us yet. */
enum class HandoffApp(val label: String, val packageName: String, val webFallback: String) {
    MPESA_GLOBAL_PAY("Open M-PESA app", "com.safaricom.mpesa.lifestyle", "https://www.safaricom.co.ke/main-mpesa/m-pesa-services/m-pesa-global"),
    PAYPAL_APP("Open PayPal", "com.paypal.android.p2pmobile", "https://www.paypal.com/myaccount/money"),
}

sealed class PayRoute {
    /** The keyboard's existing M-PESA / Airtel flow (USSD or STK) handles it, optionally with rewritten fields. */
    data class Local(val tab: PayTab, val fields: Map<String, String>) : PayRoute()

    /** A real PayPal payment from the user's saved PayPal (no re-login). */
    data class PayPalSend(val toEmail: String) : PayRoute()

    /** Done today by another official app - one tap away. */
    data class Handoff(val flow: Flow, val title: String, val steps: List<String>, val app: HandoffApp) : PayRoute()

    /** We built it; it only needs the partner's API to go live. */
    data class NeedsApi(val flow: Flow, val partner: String, val title: String, val message: String) : PayRoute()

    data class NotSupported(val message: String) : PayRoute()
}

/**
 * The routing brain of the pay screen. Pure logic: given who pays, which tab, and the receiver,
 * decide whether we can do it now, hand off to an official app, or show it as "ready, needs API".
 */
object PayRouter {
    fun route(
        source: SourceKind,
        tab: PayTab,
        fields: Map<String, String>,
        destination: BankDestination,
        accounts: MoneyAccounts,
    ): PayRoute = when (source) {
        SourceKind.PAYPAL -> fromPayPal(tab, fields, destination, accounts)
        SourceKind.BANK -> fromBank(tab, fields, destination, accounts)
        SourceKind.MPESA, SourceKind.AIRTEL -> fromMobileMoney(source, tab, fields, destination, accounts)
    }

    private fun fromPayPal(tab: PayTab, f: Map<String, String>, dest: BankDestination, a: MoneyAccounts): PayRoute = when (tab) {
        PayTab.PHONE -> {
            val phone = f["phone"].orEmpty()
            when (Network.of(phone)) {
                Network.SAFARICOM ->
                    if (a.isOwnPhone(phone)) PayRoute.Handoff(
                        Flow.WITHDRAWAL, "Withdraw PayPal to your M-PESA",
                        listOf(
                            "Open the M-PESA app and go to Global Payments > PayPal",
                            "Choose Withdraw and enter the amount in USD",
                            "Confirm with your M-PESA PIN",
                        ),
                        HandoffApp.MPESA_GLOBAL_PAY,
                    ) else PayRoute.NeedsApi(
                        Flow.CREDIT, "PayPal × Safaricom",
                        "Credit $phone from PayPal",
                        "M-PESA supports PayPal payouts, but only to your own linked number. Sending from your PayPal straight " +
                            "to another M-PESA number needs the PayPal and Safaricom payout API. HushhBoard is ready for it.",
                    )
                Network.AIRTEL, Network.TELKOM, Network.OTHER ->
                    PayRoute.NotSupported("PayPal can only pay out to M-PESA in Kenya. ${Network.of(phone).wallet} isn't supported.")
            }
        }
        PayTab.TILL -> PayRoute.NotSupported("PayPal can't pay a till number. Choose M-PESA to pay this till.")
        PayTab.PAYBILL -> PayRoute.NotSupported("PayPal can't pay a paybill. Choose M-PESA to pay this paybill.")
        PayTab.BANK -> when (dest) {
            BankDestination.PAYPAL -> {
                val email = f["ppEmail"].orEmpty().trim()
                if (a.paypal != null && email.equals(a.paypal.email, ignoreCase = true))
                    PayRoute.NotSupported("That's your own PayPal. Pick a different PayPal account to pay.")
                else PayRoute.PayPalSend(email)
            }
            BankDestination.BANK_ACCOUNT -> {
                val bank = Banks.match(f["bankName"].orEmpty())
                val own = a.ownBank(bank?.code, f["bankAccount"].orEmpty())
                if (own != null) PayRoute.Handoff(
                    Flow.WITHDRAWAL, "Withdraw PayPal to ${own.bank?.name ?: "your bank"}",
                    listOf(
                        "Open PayPal and tap Withdraw (Transfer money)",
                        "Pick your linked ${own.bank?.name ?: "bank"} account ending ${own.accountNumber.takeLast(4)}",
                        "Equity customers can also use Equity's PayPal service for instant withdrawals",
                    ),
                    HandoffApp.PAYPAL_APP,
                ) else PayRoute.NeedsApi(
                    Flow.CREDIT, "PayPal × ${bank?.name ?: "Kenyan banks"}",
                    "Credit ${bank?.name ?: "this bank"} account from PayPal",
                    "Paying someone else's bank account from PayPal needs a bank payout API (for example Equity Jenga or KCB Buni). " +
                        "The flow is built and waiting for partner access.",
                )
            }
        }
    }

    private fun fromBank(tab: PayTab, f: Map<String, String>, dest: BankDestination, a: MoneyAccounts): PayRoute {
        if (tab == PayTab.BANK && dest == BankDestination.PAYPAL) {
            val email = f["ppEmail"].orEmpty().trim()
            val toOwn = email.isBlank() || (a.paypal != null && email.equals(a.paypal.email, ignoreCase = true))
            return if (toOwn) PayRoute.Handoff(
                Flow.CREDIT, "Top up your PayPal",
                listOf(
                    "Move the money from your bank to M-PESA in your bank app",
                    "In the M-PESA app go to Global Payments > PayPal > Top up",
                    "Direct bank-to-PayPal top-up will come with the bank's API",
                ),
                HandoffApp.MPESA_GLOBAL_PAY,
            ) else PayRoute.NeedsApi(
                Flow.CREDIT, "Banks × PayPal", "Pay a PayPal account from your bank",
                "Kenyan banks don't yet let apps send from your account to PayPal. HushhBoard is ready when they open the API.",
            )
        }
        return PayRoute.NeedsApi(
            Flow.PAYMENT, "Open banking", "Pay straight from your bank account",
            "Kenyan banks don't yet let apps start payments from your account. For now, pay with M-PESA and pick your bank as the receiver.",
        )
    }

    private fun fromMobileMoney(source: SourceKind, tab: PayTab, f: Map<String, String>, dest: BankDestination, a: MoneyAccounts): PayRoute {
        if (tab != PayTab.BANK) return PayRoute.Local(tab, f)
        if (dest == BankDestination.PAYPAL) {
            if (source == SourceKind.AIRTEL) return PayRoute.NotSupported("PayPal top-ups in Kenya work through M-PESA only.")
            val email = f["ppEmail"].orEmpty().trim()
            val toOwn = email.isBlank() || (a.paypal != null && email.equals(a.paypal.email, ignoreCase = true))
            return if (toOwn) PayRoute.Handoff(
                Flow.CREDIT, "Top up your PayPal from M-PESA",
                listOf("Open the M-PESA app", "Go to Global Payments > PayPal > Top up", "Enter the amount and your M-PESA PIN"),
                HandoffApp.MPESA_GLOBAL_PAY,
            ) else PayRoute.NeedsApi(
                Flow.CREDIT, "PayPal × Safaricom", "Pay someone's PayPal from M-PESA",
                "M-PESA can top up only your own PayPal. Paying another person's PayPal from M-PESA needs the PayPal and Safaricom API.",
            )
        }
        // M-PESA -> bank account works today: pay the bank's paybill with the account number.
        val bank = Banks.match(f["bankName"].orEmpty())
            ?: return PayRoute.NotSupported("Pick a bank from the list so we can use its M-PESA paybill.")
        return PayRoute.Local(PayTab.PAYBILL, mapOf("amount" to f["amount"].orEmpty(), "paybill" to bank.paybill, "account" to f["bankAccount"].orEmpty()))
    }

    /** Label shown under the Pay button so the user always knows what kind of move this is. */
    fun flowFor(source: SourceKind, tab: PayTab, f: Map<String, String>, dest: BankDestination, a: MoneyAccounts): Flow = when {
        tab == PayTab.PHONE && a.isOwnPhone(f["phone"].orEmpty()) -> Flow.WITHDRAWAL
        tab == PayTab.BANK && dest == BankDestination.BANK_ACCOUNT &&
            a.ownBank(Banks.match(f["bankName"].orEmpty())?.code, f["bankAccount"].orEmpty()) != null -> Flow.WITHDRAWAL
        tab == PayTab.PHONE || tab == PayTab.BANK -> Flow.CREDIT
        else -> Flow.PAYMENT
    }
}

object Handoffs {
    fun open(ctx: Context, app: HandoffApp) {
        val launch = ctx.packageManager.getLaunchIntentForPackage(app.packageName)
        val intent = launch ?: Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=${app.packageName}"))
        try {
            ctx.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (_: ActivityNotFoundException) {
            ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(app.webFallback)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
    }

    fun isInstalled(ctx: Context, app: HandoffApp) = ctx.packageManager.getLaunchIntentForPackage(app.packageName) != null
}
