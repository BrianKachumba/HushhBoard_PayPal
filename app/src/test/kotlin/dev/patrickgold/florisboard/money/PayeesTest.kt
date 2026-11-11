package dev.patrickgold.florisboard.money

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

class PayeesTest : FunSpec({
    val a = MoneyAccounts(
        phones = listOf(PhoneLink("p1", "0712345678"), PhoneLink("p2", "0722000111"), PhoneLink("p3", "0733000111")),
        banks = listOf(BankLink("b1", "kcb", "1111111111", ""), BankLink("b2", "kcb", "2222222222", ""), BankLink("b3", "equity", "3333333333", "")),
        paypal = PayPalLink("me@example.com", "Me", "sealed"),
    )

    test("one bar per provider: Safaricom, Airtel, KCB, Equity, PayPal") {
        Payees.groups(a).map { it.name } shouldBe listOf("M-PESA", "Airtel Money", "KCB Bank", "Equity Bank", "PayPal")
        Payees.groups(a).first { it.key == "bank:kcb" }.options.size shouldBe 2
    }

    test("primary defaults to the first added and follows the user's choice") {
        Payees.groups(a).first { it.key == "net:SAFARICOM" }.primaryId shouldBe "p1"
        val b = a.copy(primaries = mapOf("net:SAFARICOM" to "p2"))
        val g = Payees.groups(b).first { it.key == "net:SAFARICOM" }
        g.primaryId shouldBe "p2"
        g.options.first().id shouldBe "p2"
    }

    test("a removed primary falls back to the next account") {
        a.copy(primaries = mapOf("bank:kcb" to "gone")).primaryId("bank:kcb") shouldBe "b1"
    }

    test("PayPal is only listed where it works") {
        Payees.payPalAllowed(PayTab.TILL, "") shouldBe false
        Payees.payPalAllowed(PayTab.PAYBILL, "") shouldBe false
        Payees.payPalAllowed(PayTab.BANK, "") shouldBe true
        Payees.payPalAllowed(PayTab.PHONE, "") shouldBe true
        Payees.payPalAllowed(PayTab.PHONE, "0712345678") shouldBe true
        Payees.payPalAllowed(PayTab.PHONE, "0733000111") shouldBe false
    }
})
