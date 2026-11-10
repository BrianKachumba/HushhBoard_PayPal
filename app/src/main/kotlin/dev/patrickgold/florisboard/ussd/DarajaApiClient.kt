package dev.patrickgold.florisboard.ussd

import android.util.Base64
import android.util.Log
import com.google.gson.Gson
import com.google.gson.JsonObject
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object DarajaApiClient {
    private val client = OkHttpClient()
    private val gson = Gson()

    // 🔑 Your HushhBoard Sandbox Credentials
    // Daraja sandbox keys come from gradle properties / env (never committed). See app/build.gradle.kts.
    private val CONSUMER_KEY = dev.patrickgold.florisboard.BuildConfig.HUSHH_DARAJA_KEY
    private val CONSUMER_SECRET = dev.patrickgold.florisboard.BuildConfig.HUSHH_DARAJA_SECRET

    // 🌍 Standard Safaricom Sandbox Variables
    private val PASSKEY = dev.patrickgold.florisboard.BuildConfig.HUSHH_DARAJA_PASSKEY
    private const val BUSINESS_SHORTCODE = "174379"
    private const val BASE_URL = "https://sandbox.safaricom.co.ke"

    private fun generateToken(): String? {
        val keys = "$CONSUMER_KEY:$CONSUMER_SECRET"
        val auth = Base64.encodeToString(keys.toByteArray(), Base64.NO_WRAP)

        val request = Request.Builder()
            .url("$BASE_URL/oauth/v1/generate?grant_type=client_credentials")
            .header("Authorization", "Basic $auth")
            .build()

        return try {
            client.newCall(request).execute().use { response ->
                if (response.isSuccessful) {
                    val json = gson.fromJson(response.body?.string(), JsonObject::class.java)
                    json.get("access_token").asString
                } else {
                    Log.e("DarajaApi", "Failed to get token: ${response.body?.string()}")
                    null
                }
            }
        } catch (e: Exception) {
            Log.e("DarajaApi", "Token error: ${e.message}")
            null
        }
    }

    fun initiateStkPush(phone: String, amount: String, accountReference: String, transactionDesc: String): Boolean {
        val token = generateToken() ?: return false

        val timestamp = SimpleDateFormat("yyyyMMddHHmmss", Locale.getDefault()).format(Date())
        val passwordStr = "$BUSINESS_SHORTCODE$PASSKEY$timestamp"
        val password = Base64.encodeToString(passwordStr.toByteArray(), Base64.NO_WRAP)

        // Ensure phone number is formatted perfectly to Safaricom's 254... standard
        val formattedPhone = when {
            phone.startsWith("0") -> "254${phone.substring(1)}"
            phone.startsWith("+") -> phone.substring(1)
            else -> phone
        }

        val jsonBody = JsonObject().apply {
            addProperty("BusinessShortCode", BUSINESS_SHORTCODE)
            addProperty("Password", password)
            addProperty("Timestamp", timestamp)
            addProperty("TransactionType", "CustomerPayBillOnline")
            addProperty("Amount", amount)
            addProperty("PartyA", formattedPhone) // The person paying
            addProperty("PartyB", BUSINESS_SHORTCODE) // The business receiving
            addProperty("PhoneNumber", formattedPhone) // The phone that receives the STK Push
            addProperty("CallBackURL", "https://mydomain.com/path") // Safaricom requires this to be filled
            addProperty("AccountReference", accountReference)
            addProperty("TransactionDesc", transactionDesc)
        }

        val requestBody = jsonBody.toString().toRequestBody("application/json".toMediaType())
        val request = Request.Builder()
            .url("$BASE_URL/mpesa/stkpush/v1/processrequest")
            .header("Authorization", "Bearer $token")
            .post(requestBody)
            .build()

        return try {
            client.newCall(request).execute().use { response ->
                val responseBody = response.body?.string()
                Log.d("DarajaApi", "STK Response: $responseBody")
                response.isSuccessful
            }
        } catch (e: Exception) {
            Log.e("DarajaApi", "STK Push error: ${e.message}")
            false
        }
    }
}
