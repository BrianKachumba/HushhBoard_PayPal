package dev.patrickgold.florisboard.ussd

import android.content.Context
import android.util.Log
import androidx.room.*
import kotlinx.coroutines.flow.Flow

// 1. THE DATABASE ENTITY
@Entity(tableName = "transactions")
data class TransactionEntity(
    @PrimaryKey val transactionId: String,
    val type: String, // SEND_MONEY, TILL, PAYBILL
    val recipient: String,
    val amount: String,
    val fee: String,
    val balance: String,
    val dailyLimitRemaining: String,
    val timestamp: Long,
    val dateString: String,
    val timeString: String,
    val rawMessage: String,
    var category: String = "Uncategorized",
    var description: String = "",
    var isLabeled: Boolean = false,
    var isDiscarded: Boolean = false
)

// 2. THE DAO
@Dao
interface TransactionDao {
    @Query("SELECT * FROM transactions ORDER BY timestamp DESC")
    fun getAllTransactions(): Flow<List<TransactionEntity>>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertTransaction(transaction: TransactionEntity)

    @Update
    suspend fun updateTransaction(transaction: TransactionEntity)

    @Delete
    suspend fun deleteTransaction(transaction: TransactionEntity)
}

// 3. THE ROOM DATABASE
@Database(entities = [TransactionEntity::class], version = 1, exportSchema = false)
abstract class AppDatabase : RoomDatabase() {
    abstract fun transactionDao(): TransactionDao

    companion object {
        @Volatile private var INSTANCE: AppDatabase? = null
        fun getDatabase(context: Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java, "hushh_finance_db"
                ).build()
                INSTANCE = instance
                instance
            }
        }
    }
}

// 4. THE ROBUST M-PESA PARSER
object MpesaParser {
    fun parseSms(message: String, timestamp: Long): TransactionEntity? {
        // Reject non-expense messages (Fuliza loans, incoming money, failed transactions)
        val ignoreList = listOf("Fuliza M-PESA amount", "You have received", "Your account balance", "Failed", "used to fully pay", "used to partially pay")
        if (ignoreList.any { message.contains(it, ignoreCase = true) }) return null

        // Ensure it starts with a 10-character alphanumeric ID
        if (!message.matches(Regex("^[A-Z0-9]{10} Confirmed.*"))) return null

        try {
            val id = message.substring(0, 10)

            var type = TransactionType.UNKNOWN.name
            var recipient = "Unknown"
            var amount = "0.00"
            var dateStr = ""
            var timeStr = ""

            // Strict Regex Matchers
            val sendRegex = Regex("Ksh([\\d,.]+)\\s+sent to\\s+(.*?)\\s+on\\s+(\\d{1,2}/\\d{1,2}/\\d{2})\\s+at\\s+(\\d{1,2}:\\d{2}\\s+[APM]{2})")
            val paybillRegex = Regex("Ksh([\\d,.]+)\\s+sent to\\s+(.*?)\\s+for account\\s+(.*?)\\s+on\\s+(\\d{1,2}/\\d{1,2}/\\d{2})\\s+at\\s+(\\d{1,2}:\\d{2}\\s+[APM]{2})")
            val tillRegex = Regex("Ksh([\\d,.]+)\\s+paid to\\s+(.*?)\\.?\\s+on\\s+(\\d{1,2}/\\d{1,2}/\\d{2})\\s+at\\s+(\\d{1,2}:\\d{2}\\s+[APM]{2})")

            when {
                paybillRegex.containsMatchIn(message) -> {
                    val match = paybillRegex.find(message)!!
                    type = TransactionType.PAYBILL.name
                    amount = match.groupValues[1]
                    recipient = "${match.groupValues[2]} (Acc: ${match.groupValues[3]})"
                    dateStr = match.groupValues[4]
                    timeStr = match.groupValues[5]
                }
                sendRegex.containsMatchIn(message) -> {
                    val match = sendRegex.find(message)!!
                    type = TransactionType.SEND_MONEY.name
                    amount = match.groupValues[1]
                    // Clean phone number out of recipient name if present
                    recipient = match.groupValues[2].replace(Regex("\\s07\\d{8}|\\s01\\d{8}"), "")
                    dateStr = match.groupValues[3]
                    timeStr = match.groupValues[4]
                }
                tillRegex.containsMatchIn(message) -> {
                    val match = tillRegex.find(message)!!
                    type = TransactionType.TILL.name
                    amount = match.groupValues[1]
                    recipient = match.groupValues[2]
                    dateStr = match.groupValues[3]
                    timeStr = match.groupValues[4]
                }
                else -> return null // Ignore if it doesn't match spending rules
            }

            val fee = Regex("Transaction cost,\\s*Ksh([\\d,.]+)").find(message)?.groupValues?.get(1) ?: "0.00"
            val balance = Regex("New M-PESA balance is\\s*Ksh([\\d,.]+)").find(message)?.groupValues?.get(1) ?: "0.00"
            val limit = Regex("Amount you can transact within the day is\\s*([\\d,.]+)").find(message)?.groupValues?.get(1) ?: "0.00"

            return TransactionEntity(
                transactionId = id, type = type, recipient = recipient.trim(),
                amount = amount, fee = fee, balance = balance, dailyLimitRemaining = limit,
                timestamp = timestamp, dateString = dateStr, timeString = timeStr,
                rawMessage = message
            )
        } catch (e: Exception) {
            Log.e("MpesaParser", "Failed to parse: ${e.message}")
            return null
        }
    }
}
