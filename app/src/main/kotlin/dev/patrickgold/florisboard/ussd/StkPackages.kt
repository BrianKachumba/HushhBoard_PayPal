// 🚀 HUSHHBOARD
package dev.patrickgold.florisboard.ussd

object StkPackages {
    val KNOWN: Set<String> = setOf(
        "com.android.stk",
        "com.android.stk1",
        "com.android.stk2",
        "org.codeaurora.stk",
    )

    fun matches(packageName: String?): Boolean {
        return packageName != null && packageName in KNOWN
    }
}
