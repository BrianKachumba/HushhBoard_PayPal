/*
 * Copyright (C) 2026 HushhBoard
 * Licensed under the Apache License, Version 2.0.
 */

package dev.hushhboard.nlp.entities

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldNotContain
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe

private fun kinds(text: String) = EntityExtractor.extract(text).map { it.kind to it.value }

class OtpTest : FunSpec({
    test("codes next to cue words") {
        Otp.fromMessage("Your verification code is 482913. Do not share it.") shouldBe "482913"
        Otp.fromMessage("G-482913 is your Google verification code.") shouldBe "482913"
        Otp.fromMessage("Use 7731 to sign in to your account") shouldBe "7731"
        Otp.fromMessage("Nambari yako ya uthibitisho ni 5521") shouldBe "5521"
        Otp.fromMessage("Your OTP: 123-456") shouldBe "123456"
        Otp.fromMessage("<#> 902114 is your WhatsApp code. Don't share this code") shouldBe "902114"
    }

    test("numbers that are not codes") {
        Otp.fromMessage("Your bill of KSh 4500 is due on 12/03/2026.") shouldBe null
        Otp.fromMessage("Call us on 0712345678 for help") shouldBe null
        Otp.fromMessage("Meet me at 1630 near the stage") shouldBe null
        Otp.fromMessage(
            "SFK7ABC12D Confirmed. Ksh500.00 sent to JOHN DOE 0712345678 on 1/3/26 at 2:15 PM. " +
                "New M-PESA balance is Ksh1,200.00. Transaction cost, Ksh7.00.",
        ) shouldBe null
        Otp.fromMessage("Paybill 247247 account code 5566") shouldBe "5566" // "code" is explicit
        Otp.fromMessage("Pay to till 554433 now") shouldBe null
    }

    test("a copied code on its own") {
        Otp.isBareCode("482913") shouldBe true
        Otp.isBareCode(" 4829 ") shouldBe true
        Otp.isBareCode("hello 4829") shouldBe false
        kinds("482913") shouldBe listOf(EntityKind.OTP to "482913")
    }
})

class EntityExtractorTest : FunSpec({
    test("Kenyan phone numbers in any format, normalised") {
        kinds("Call 0712 345 678") shouldContain (EntityKind.PHONE to "0712345678")
        kinds("WhatsApp +254 712 345678 asap") shouldContain (EntityKind.PHONE to "0712345678")
        kinds("new line 0110123456") shouldContain (EntityKind.PHONE to "0110123456")
        EntityExtractor.carrierOf("0712345678") shouldBe "Safaricom"
        EntityExtractor.carrierOf("0733123456") shouldBe "Airtel"
    }

    test("paybill with account, till, amount") {
        val e = EntityExtractor.extract("Pay via M-PESA Paybill 247247, Account No. 0712345678. Amount KSh 1,500")
        val paybill = e.first { it.kind == EntityKind.PAYBILL }
        paybill.value shouldBe "247247"
        paybill.extra["account"] shouldBe "0712345678"
        e.map { it.kind } shouldContain EntityKind.AMOUNT
        e.first { it.kind == EntityKind.AMOUNT }.value shouldBe "1500"
        kinds("Lipa na M-PESA Buy Goods Till Number 554433") shouldContain (EntityKind.TILL to "554433")
    }

    test("M-Pesa SMS: code, person, amounts") {
        val sms = "SFK7ABC12D Confirmed. Ksh500.00 sent to JOHN KAMAU 0712345678 on 1/3/26 at 2:15 PM."
        val k = kinds(sms)
        k shouldContain (EntityKind.MPESA_CODE to "SFK7ABC12D")
        k shouldContain (EntityKind.PERSON to "John Kamau")
        k shouldContain (EntityKind.PHONE to "0712345678")
        k shouldContain (EntityKind.AMOUNT to "500")
        kinds("SGH1XYZ9QA Confirmed. Ksh250.00 paid to NAIVAS WESTLANDS. on 2/3/26") shouldContain
            (EntityKind.BUSINESS to "Naivas Westlands")
    }

    test("emails and links, trailing punctuation trimmed") {
        kinds("Write to jane.doe@example.co.ke.") shouldContain (EntityKind.EMAIL to "jane.doe@example.co.ke")
        kinds("Details at https://safaricom.co.ke/offers).") shouldContain (EntityKind.URL to "https://safaricom.co.ke/offers")
        kinds("visit www.kcbgroup.com today") shouldContain (EntityKind.URL to "https://www.kcbgroup.com")
        kinds("see hushhboard.co.ke/help") shouldContain (EntityKind.URL to "https://hushhboard.co.ke/help")
        // An email's domain is not a separate link.
        kinds("jane@example.com").map { it.first } shouldBe listOf(EntityKind.EMAIL)
    }

    test("names and places from cues") {
        kinds("Name: Wanjiku Mwangi\nPhone: 0722000111") shouldContain (EntityKind.PERSON to "Wanjiku Mwangi")
        kinds("Meet at Sarit Centre at 5") shouldContain (EntityKind.PLACE to "Sarit Centre")
        kinds("Location: Two Rivers Mall, Gate B") shouldContain (EntityKind.PLACE to "Two Rivers Mall, Gate B")
        kinds("I'm in Westlands now") shouldContain (EntityKind.PLACE to "Westlands")
        kinds("pin -1.2921, 36.8219") shouldContain (EntityKind.PLACE to "-1.2921,36.8219")
        kinds("KRA PIN A012345678B").map { it.first } shouldContain EntityKind.KRA_PIN
    }

    test("ranking offers codes and payments first") {
        val ranked = EntityExtractor.rank(EntityExtractor.extract("Call 0712345678. Your code is 4471"))
        ranked.first().kind shouldBe EntityKind.OTP
    }

    test("plain chat text has nothing") {
        EntityExtractor.extract("haha sawa tutaonana kesho") shouldBe emptyList()
    }
})

class UrlSafetyTest : FunSpec({
    test("official sites are fine") {
        UrlSafety.check("https://www.safaricom.co.ke/personal/m-pesa").level shouldBe UrlSafety.Level.OK
        UrlSafety.check("https://kcbgroup.com").site shouldBe "kcbgroup.com"
        UrlSafety.check("https://wa.me/254712345678").level shouldBe UrlSafety.Level.OK
    }

    test("look-alikes and tricks are flagged") {
        val fake = UrlSafety.check("https://saf4ric0m-bonus.xyz/claim")
        fake.warnings shouldContain UrlSafety.Warning.LOOKALIKE
        fake.imitates shouldBe "safaricom"
        fake.level shouldBe UrlSafety.Level.DANGER
        UrlSafety.check("https://safaricom.login-secure.com").warnings shouldContain UrlSafety.Warning.BRAND_IN_SUBDOMAIN
        UrlSafety.check("http://192.168.0.10/pay").warnings shouldContain UrlSafety.Warning.IP_ADDRESS
        UrlSafety.check("https://bit.ly/3abcd").warnings shouldContain UrlSafety.Warning.SHORTENER
        UrlSafety.check("http://example.com").warnings shouldContain UrlSafety.Warning.NOT_HTTPS
        UrlSafety.check("https://xn--safarcom-9za.com").warnings shouldContain UrlSafety.Warning.PUNYCODE
        UrlSafety.check("https://www.google.com@evil.example/x").warnings shouldContain UrlSafety.Warning.USERINFO
        UrlSafety.check("javascript:alert(1)").level shouldBe UrlSafety.Level.DANGER
        UrlSafety.check("https://equlty-bank.com").warnings shouldContain UrlSafety.Warning.LOOKALIKE
    }

    test("site of a host") {
        UrlSafety.siteOf("a.b.equitybank.co.ke") shouldBe "equitybank.co.ke"
        UrlSafety.siteOf("mail.google.com") shouldBe "google.com"
    }
})

class ScanPayloadTest : FunSpec({
    test("wifi") {
        val w = ScanPayload.parse("WIFI:T:WPA;S:Cafe\\;Net;P:pa\\:ss;H:false;;") as ScanPayload.Wifi
        w.ssid shouldBe "Cafe;Net"
        w.password shouldBe "pa:ss"
        w.security shouldBe "WPA"
        (ScanPayload.parse("WIFI:S:Open;;") as ScanPayload.Wifi).security shouldBe "nopass"
    }

    test("links, phones, sms, email, geo") {
        (ScanPayload.parse("https://kcbgroup.com/x") as ScanPayload.Link).report.site shouldBe "kcbgroup.com"
        (ScanPayload.parse("tel:+254712345678") as ScanPayload.Phone).number shouldBe "+254712345678"
        val sms = ScanPayload.parse("SMSTO:0712345678:Hello there") as ScanPayload.Sms
        sms.number shouldBe "0712345678"
        sms.body shouldBe "Hello there"
        val mail = ScanPayload.parse("mailto:a@b.com?subject=Hi%20you&body=x") as ScanPayload.Email
        mail.subject shouldBe "Hi you"
        val geo = ScanPayload.parse("geo:-1.2921,36.8219?q=Nairobi") as ScanPayload.Geo
        geo.lat shouldBe -1.2921
        geo.query shouldBe "Nairobi"
    }

    test("contacts") {
        val v = ScanPayload.parse("BEGIN:VCARD\nVERSION:3.0\nFN:Jane Wanjiru\nTEL;TYPE=CELL:0712345678\nEMAIL:jane@x.com\nORG:Acme\nEND:VCARD") as ScanPayload.Contact
        v.name shouldBe "Jane Wanjiru"
        v.phones shouldBe listOf("0712345678")
        v.organization shouldBe "Acme"
        val m = ScanPayload.parse("MECARD:N:Otieno,Brian;TEL:0722000111;EMAIL:b@x.com;;") as ScanPayload.Contact
        m.name shouldBe "Brian Otieno"
    }

    test("EMVCo checksum and a KE-QR style payment") {
        ScanPayload.crc16("123456789") shouldBe "29B1"
        fun field(tag: String, v: String) = tag + "%02d".format(v.length) + v
        val body = field("00", "01") + field("01", "12") +
            field("29", field("00", "ke.go.qr") + field("01", "554433")) +
            field("53", "404") + field("54", "250.00") + field("58", "KE") + field("59", "MAMA MBOGA") + field("60", "Nairobi") + "6304"
        val qr = body + ScanPayload.crc16(body)
        val p = ScanPayload.parse(qr) as ScanPayload.Payment
        p.verified shouldBe true
        p.payment.type shouldBe PaymentParser.Type.TILL
        p.payment.till shouldBe "554433"
        p.payment.amount shouldBe "250"
        p.merchant shouldBe "MAMA MBOGA"
        val tampered = qr.dropLast(1) + (if (qr.last() == '0') '1' else '0')
        (ScanPayload.parse(tampered) as ScanPayload.Payment).verified shouldBe false
    }

    test("plain text and text that is a payment") {
        ScanPayload.parse("Hello world, see you") shouldBe ScanPayload.Text("Hello world, see you")
        (ScanPayload.parse("LIPA NA M-PESA BUY GOODS TILL NUMBER 554433") as ScanPayload.Payment).payment.till shouldBe "554433"
    }
})

class PaymentParserTest : FunSpec({
    test("signs and instructions") {
        PaymentParser.parse("LIPA NA M-PESA\nBUY GOODS\n554433").let { it.type shouldBe PaymentParser.Type.TILL; it.till shouldBe "554433" }
        PaymentParser.parse("PAYBILL NO. 247247 ACCOUNT NO. AB1234").let {
            it.type shouldBe PaymentParser.Type.PAYBILL
            it.paybill shouldBe "247247"
            it.account shouldBe "AB1234"
        }
        PaymentParser.parse("send ksh 500 to 0712 345 678").let {
            it.type shouldBe PaymentParser.Type.SEND_MONEY
            it.phone shouldBe "0712345678"
            it.amount shouldBe "500"
        }
        PaymentParser.parse("random words 123").type shouldBe PaymentParser.Type.UNKNOWN
    }

    test("consensus needs the same reading in 3 of the last 5 frames") {
        val c = PaymentParser.Consensus()
        val good = PaymentParser.parse("Till 554433 amount 200")
        val misread = PaymentParser.parse("Till 554438 amount 200")
        c.offer(good) shouldBe null
        c.offer(misread) shouldBe null
        c.offer(good) shouldBe null
        c.offer(good) shouldNotBe null
        c.reset()
        c.progress() shouldBe 0f
    }
})
