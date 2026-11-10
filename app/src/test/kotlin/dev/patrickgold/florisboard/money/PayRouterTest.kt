package dev.patrickgold.florisboard.money

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf

class PayRouterTest : FunSpec({
    val me = MoneyAccounts(
        phones = listOf(PhoneLink("p1", "0712345678")),
        banks = listOf(BankLink("b1", "equity", "0123456789", "Me")),
        paypal = PayPalLink("me@example.com", "Me", "sealed"),
    )
    val amt = mapOf("amount" to "20")

    test("PayPal to own M-PESA is a withdrawal handoff to the M-PESA app") {
        val r = PayRouter.route(SourceKind.PAYPAL, PayTab.PHONE, amt + ("phone" to "0712345678"), BankDestination.BANK_ACCOUNT, me)
        r.shouldBeInstanceOf<PayRoute.Handoff>()
        r.flow shouldBe Flow.WITHDRAWAL
        r.app shouldBe HandoffApp.MPESA_GLOBAL_PAY
    }

    test("PayPal to another Safaricom number is a credit waiting for the API") {
        val r = PayRouter.route(SourceKind.PAYPAL, PayTab.PHONE, amt + ("phone" to "0722000111"), BankDestination.BANK_ACCOUNT, me)
        r.shouldBeInstanceOf<PayRoute.NeedsApi>()
        r.flow shouldBe Flow.CREDIT
    }

    test("PayPal to Airtel is not supported") {
        PayRouter.route(SourceKind.PAYPAL, PayTab.PHONE, amt + ("phone" to "0733000111"), BankDestination.BANK_ACCOUNT, me)
            .shouldBeInstanceOf<PayRoute.NotSupported>()
    }

    test("PayPal cannot pay tills or paybills") {
        PayRouter.route(SourceKind.PAYPAL, PayTab.TILL, amt + ("till" to "123456"), BankDestination.BANK_ACCOUNT, me).shouldBeInstanceOf<PayRoute.NotSupported>()
        PayRouter.route(SourceKind.PAYPAL, PayTab.PAYBILL, amt + ("paybill" to "247247"), BankDestination.BANK_ACCOUNT, me).shouldBeInstanceOf<PayRoute.NotSupported>()
    }

    test("PayPal to another PayPal email is a real PayPal payment") {
        val r = PayRouter.route(SourceKind.PAYPAL, PayTab.BANK, amt + ("ppEmail" to "jane@studio.com"), BankDestination.PAYPAL, me)
        r shouldBe PayRoute.PayPalSend("jane@studio.com")
    }

    test("PayPal to own PayPal is refused") {
        PayRouter.route(SourceKind.PAYPAL, PayTab.BANK, amt + ("ppEmail" to "ME@example.com"), BankDestination.PAYPAL, me)
            .shouldBeInstanceOf<PayRoute.NotSupported>()
    }

    test("PayPal to own bank is a withdrawal; to another bank needs the bank API") {
        val own = PayRouter.route(SourceKind.PAYPAL, PayTab.BANK, amt + ("bankName" to "Equity") + ("bankAccount" to "0123456789"), BankDestination.BANK_ACCOUNT, me)
        own.shouldBeInstanceOf<PayRoute.Handoff>()
        own.flow shouldBe Flow.WITHDRAWAL
        val other = PayRouter.route(SourceKind.PAYPAL, PayTab.BANK, amt + ("bankName" to "KCB") + ("bankAccount" to "9999999999"), BankDestination.BANK_ACCOUNT, me)
        other.shouldBeInstanceOf<PayRoute.NeedsApi>()
    }

    test("M-PESA to a bank account uses the bank's paybill") {
        val r = PayRouter.route(SourceKind.MPESA, PayTab.BANK, amt + ("bankName" to "kcb") + ("bankAccount" to "1234567"), BankDestination.BANK_ACCOUNT, me)
        r shouldBe PayRoute.Local(PayTab.PAYBILL, mapOf("amount" to "20", "paybill" to "522522", "account" to "1234567"))
    }

    test("M-PESA to own PayPal is a top-up handoff") {
        val r = PayRouter.route(SourceKind.MPESA, PayTab.BANK, amt, BankDestination.PAYPAL, me)
        r.shouldBeInstanceOf<PayRoute.Handoff>()
        r.flow shouldBe Flow.CREDIT
    }

    test("M-PESA phone payments keep the existing flow") {
        PayRouter.route(SourceKind.MPESA, PayTab.PHONE, amt + ("phone" to "0722000111"), BankDestination.BANK_ACCOUNT, me)
            .shouldBeInstanceOf<PayRoute.Local>()
    }

    test("flow labels") {
        PayRouter.flowFor(SourceKind.MPESA, PayTab.PHONE, mapOf("phone" to "0712345678"), BankDestination.BANK_ACCOUNT, me) shouldBe Flow.WITHDRAWAL
        PayRouter.flowFor(SourceKind.MPESA, PayTab.PHONE, mapOf("phone" to "0722000111"), BankDestination.BANK_ACCOUNT, me) shouldBe Flow.CREDIT
        PayRouter.flowFor(SourceKind.MPESA, PayTab.TILL, mapOf("till" to "123456"), BankDestination.BANK_ACCOUNT, me) shouldBe Flow.PAYMENT
    }
})
