package dev.patrickgold.florisboard.ussd

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.telephony.SubscriptionInfo
import android.telephony.SubscriptionManager
import android.telecom.PhoneAccountHandle
import android.telecom.TelecomManager

data class ActiveSim(
    val slotIndex: Int,
    val carrierName: String,
    val subscriptionId: Int
)

object SimManager {

    @SuppressLint("MissingPermission")
    fun getActiveSims(context: Context): List<ActiveSim> {
        val subscriptionManager = context.getSystemService(Context.TELEPHONY_SUBSCRIPTION_SERVICE) as SubscriptionManager
        val activeSubscriptionInfoList: List<SubscriptionInfo>? = subscriptionManager.activeSubscriptionInfoList

        if (activeSubscriptionInfoList.isNullOrEmpty()) return emptyList()

        return activeSubscriptionInfoList.map { info ->
            ActiveSim(
                slotIndex = info.simSlotIndex,
                carrierName = info.carrierName.toString(),
                subscriptionId = info.subscriptionId
            )
        }.sortedBy { it.slotIndex }
    }

    @SuppressLint("MissingPermission")
    fun executeUssd(context: Context, ussdCode: String, sim: ActiveSim) {
        // We MUST encode the '#' symbol as '%23' for Android to parse it correctly
        val encodedUssd = Uri.encode(ussdCode)
        val uri = Uri.parse("tel:$encodedUssd")

        val intent = Intent(Intent.ACTION_CALL, uri).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            // This extra tells Android exactly which SIM card to use for the call
            putExtra("android.telecom.extra.PHONE_ACCOUNT_HANDLE", getPhoneAccountHandle(context, sim.subscriptionId))
        }

        try {
            context.startActivity(intent)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    @SuppressLint("MissingPermission")
    private fun getPhoneAccountHandle(context: Context, subscriptionId: Int): PhoneAccountHandle? {
        val telecomManager = context.getSystemService(Context.TELECOM_SERVICE) as TelecomManager
        val phoneAccounts = telecomManager.callCapablePhoneAccounts
        return phoneAccounts.firstOrNull { it.id.contains(subscriptionId.toString()) }
    }
}
