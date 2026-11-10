package dev.patrickgold.florisboard.ussd

import androidx.compose.ui.graphics.vector.ImageVector

data class HushhCategory(
    val id: String,
    val displayName: String,
    val emoji: String,
    val icon: ImageVector,
    val subCategories: List<String>
)

object CategoryRegistry {
    // 🚀 Real spending categories. Never matched implicitly — only via explicit user selection.
    val masterList = listOf(
        HushhCategory("food", "Food & Dining", "🍔", HushhIcons.Restaurant, listOf("Restaurants", "Kiosk", "Snack", "Fast Food", "Breakfast", "Lunch", "Supper", "Cooking supplies", "Groceries", "Fruits", "Other")),
        HushhCategory("transport", "Transport", "🚕", HushhIcons.DirectionsCar, listOf("Taxi", "Matatu", "Boda", "Bus", "Fuel", "Parking", "Flight", "Maintenance", "Other")),
        HushhCategory("bills and utilities", "Bills & Utilities", "💡", HushhIcons.ReceiptLong, listOf("Electricity", "Water", "Internet", "Rent", "Garbage", "Home repairs")),
        HushhCategory("shopping", "Shopping", "🛍️", HushhIcons.ShoppingBag, listOf("Clothing and shoes", "Beauty", "Home goods", "Toiletries", "Electronics", "SuperMarket", "Children")),
        HushhCategory("transfers", "Family & Friends", "💸", HushhIcons.Payments, listOf("Parents", "Friends", "Siblings", "Children", "Partner", "Charity & donations", "Bank Transfer")),
        HushhCategory("entertainment", "Entertainment", "🎬", HushhIcons.Movie, listOf("Sportfy", "Clubbing/Sheree", "Netflix", "Hobbies", "TV Subscriptions")),
        HushhCategory("health", "Health", "🏥", HushhIcons.HealthAndSafety, listOf("Pharmacy", "GYM", "Hospital Bill", "Check ups")),
        HushhCategory("finance", "Finances", "🛒", HushhIcons.ShoppingCart, listOf("Loan repayment", "Taxes", "Savings", "Insurance", "Investment", "Bakery")),
        HushhCategory("education", "Education", "📚", HushhIcons.School, listOf("Tuition Fees", "Hostel", "School Supplies", "Trip and Recreations")),
        HushhCategory("business", "My Business", "✂️", HushhIcons.Face, listOf("Business Operation", "Inventory", "Investment", "Marketing")),
        HushhCategory("airtime", "Phone Use", "📞", HushhIcons.Category, listOf("Mobile data", "SMS", "Minutes"))
    )

    // 🚀 A real, distinct bucket — deliberately NOT in masterList. It exists so unlabeled
    // spend surfaces as its own visible category (nudging the user to label it) instead of
    // silently merging into whatever category happens to be last in the list.
    val uncategorized = HushhCategory(
        id = "uncategorized",
        displayName = "Uncategorized",
        emoji = "❓",
        icon = HushhIcons.Category,
        subCategories = emptyList()
    )

    private val byDisplayName = masterList.associateBy { it.displayName.lowercase() }

    /**
     * Strips a leading emoji (if present) and resolves against the registry.
     * Blank input, "Uncategorized", or anything that doesn't match a real category
     * returns the dedicated [uncategorized] bucket — it is NEVER silently folded into
     * masterList.last() or any other real category.
     */
    fun getCategoryByName(name: String): HushhCategory {
        val cleaned = name
            .trim()
            .replace(Regex("^[\\p{So}\\p{Cn}\\p{Sk}\\uFE0F]+\\s*"), "")
            .trim()

        if (cleaned.isBlank() || cleaned.equals("Uncategorized", ignoreCase = true)) {
            return uncategorized
        }

        return byDisplayName[cleaned.lowercase()] ?: uncategorized
    }

    fun getCategoryById(id: String): HushhCategory {
        if (id == uncategorized.id) return uncategorized
        return masterList.find { it.id == id } ?: uncategorized
    }
}
