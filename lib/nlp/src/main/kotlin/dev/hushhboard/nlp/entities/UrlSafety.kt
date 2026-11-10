/*
 * Copyright (C) 2026 HushhBoard
 * Licensed under the Apache License, Version 2.0.
 */

package dev.hushhboard.nlp.entities

/**
 * A quick, offline look at a link before anyone opens it: what site it really goes to and the
 * tricks scam QR codes and SMS links use (look-alike bank names, shorteners hiding the target,
 * raw IP addresses, "safaricom.login-secure.xyz", punycode, no HTTPS).
 *
 * It cannot prove a link is safe; it says what is suspicious so the user decides with the real
 * destination in front of them.
 */
object UrlSafety {

    enum class Warning(val message: String) {
        NOT_HTTPS("Not a secure (https) link"),
        SHORTENER("Short link: the real destination is hidden"),
        IP_ADDRESS("Goes to a raw IP address, not a named website"),
        PUNYCODE("Uses look-alike characters in the address"),
        LOOKALIKE("Looks like a well-known brand but isn't its real website"),
        BRAND_IN_SUBDOMAIN("Brand name used inside another website's address"),
        USERINFO("Address hides the real site after an @"),
        SUSPICIOUS_TLD("Unusual web address ending often used by scams"),
        NON_STANDARD_PORT("Uses an unusual port"),
        DANGEROUS_SCHEME("Not a web link: it can run code or open a file"),
    }

    enum class Level { OK, CAUTION, DANGER }

    data class Report(
        val url: String,
        /** Host as typed, e.g. "secure.safaricom.co.ke". */
        val host: String,
        /** The site that actually owns the address, e.g. "safaricom.co.ke". */
        val site: String,
        val isHttps: Boolean,
        val warnings: List<Warning>,
        /** For LOOKALIKE / BRAND_IN_SUBDOMAIN: the brand being imitated. */
        val imitates: String? = null,
    ) {
        val level: Level
            get() = when {
                warnings.any { it in DANGER_WARNINGS } -> Level.DANGER
                warnings.isNotEmpty() -> Level.CAUTION
                else -> Level.OK
            }
    }

    private val DANGER_WARNINGS = setOf(
        Warning.LOOKALIKE, Warning.BRAND_IN_SUBDOMAIN, Warning.PUNYCODE, Warning.USERINFO,
        Warning.IP_ADDRESS, Warning.DANGEROUS_SCHEME,
    )

    private val SHORTENERS = setOf(
        "bit.ly", "bitly.com", "tinyurl.com", "t.co", "goo.gl", "ow.ly", "is.gd", "buff.ly", "cutt.ly",
        "rebrand.ly", "shorturl.at", "rb.gy", "tiny.cc", "s.id", "t.ly", "bl.ink", "lnkd.in", "qrco.de",
        "short.io", "shorte.st", "adf.ly", "v.gd", "x.co", "trib.al", "soo.gd", "clck.ru", "u.to",
        "bit.do", "tr.im", "y2u.be", "shorturl.com", "qr.net", "me-qr.com", "linkr.ee",
    )

    private val SUSPICIOUS_TLDS = setOf(
        "zip", "mov", "xyz", "top", "click", "country", "gq", "ml", "cf", "tk", "ga", "work", "support",
        "loan", "kim", "buzz", "rest", "cam", "monster", "quest", "cyou", "sbs", "icu", "bar", "lol",
    )

    /** Second-level suffixes where the registrable name is one label further left. */
    private val TWO_LEVEL_SUFFIXES = setOf(
        "co.ke", "or.ke", "ac.ke", "go.ke", "ne.ke", "sc.ke", "me.ke", "info.ke", "mobi.ke",
        "co.uk", "org.uk", "ac.uk", "gov.uk", "com.au", "co.za", "org.za", "co.tz", "or.tz", "go.tz",
        "co.ug", "or.ug", "go.ug", "com.ng", "org.ng", "gov.ng", "co.rw", "com.gh", "co.in", "com.br",
    )

    /** Brands that scammers imitate in Kenya, with every site that really belongs to them. */
    private val BRANDS: Map<String, Set<String>> = mapOf(
        "safaricom" to setOf("safaricom.co.ke", "safaricom.com", "m-pesa.com", "mpesa.com"),
        "mpesa" to setOf("safaricom.co.ke", "safaricom.com", "m-pesa.com", "mpesa.com"),
        "equity" to setOf("equitybank.co.ke", "equitygroupholdings.com", "equitybankgroup.com"),
        "equitybank" to setOf("equitybank.co.ke", "equitygroupholdings.com", "equitybankgroup.com"),
        "kcb" to setOf("kcbgroup.com", "kcbbankgroup.com"),
        "kcbgroup" to setOf("kcbgroup.com", "kcbbankgroup.com"),
        "ncba" to setOf("ncbagroup.com", "ncba.co.ke"),
        "coopbank" to setOf("co-opbank.co.ke"),
        "absa" to setOf("absabank.co.ke", "absa.africa", "absa.co.za"),
        "stanbic" to setOf("stanbicbank.co.ke", "stanbic.com"),
        "airtel" to setOf("airtel.co.ke", "airtelkenya.com", "airtel.africa", "airtel.com", "airtel.in"),
        "kra" to setOf("kra.go.ke"),
        "ecitizen" to setOf("ecitizen.go.ke"),
        "hustlerfund" to setOf("hustlerfund.go.ke"),
        "helb" to setOf("helb.co.ke"),
        "nhif" to setOf("nhif.or.ke"),
        "jumia" to setOf("jumia.co.ke", "jumia.com"),
        "google" to setOf("google.com", "google.co.ke", "goo.gl", "youtube.com", "g.co"),
        "facebook" to setOf("facebook.com", "fb.com", "fb.me", "meta.com"),
        "whatsapp" to setOf("whatsapp.com", "wa.me", "whatsapp.net"),
        "instagram" to setOf("instagram.com", "instagr.am"),
        "paypal" to setOf("paypal.com", "paypal.me"),
        "apple" to setOf("apple.com", "icloud.com"),
        "microsoft" to setOf("microsoft.com", "live.com", "outlook.com", "office.com"),
        "netflix" to setOf("netflix.com"),
        "binance" to setOf("binance.com"),
        "tiktok" to setOf("tiktok.com"),
    )

    private val IPV4 = Regex("""^\d{1,3}(?:\.\d{1,3}){3}$""")

    /** The host of [url] (lower case, no port), or the input if it cannot be parsed. */
    fun hostOf(url: String): String {
        val rest = url.substringAfter("://", url)
        val authority = rest.substringBefore('/').substringBefore('?').substringBefore('#')
        return authority.substringAfterLast('@').substringBefore(':').lowercase().trimEnd('.')
    }

    /** "secure.mail.safaricom.co.ke" -> "safaricom.co.ke". */
    fun siteOf(host: String): String {
        val labels = host.split('.').filter { it.isNotEmpty() }
        if (labels.size <= 2 || IPV4.matches(host)) return host
        val lastTwo = labels.takeLast(2).joinToString(".")
        return if (lastTwo in TWO_LEVEL_SUFFIXES) labels.takeLast(3).joinToString(".") else lastTwo
    }

    fun check(rawUrl: String): Report {
        val url = rawUrl.trim()
        val scheme = url.substringBefore("://", "").lowercase()
        val warnings = ArrayList<Warning>()
        if (url.lowercase().startsWith("javascript:") || url.lowercase().startsWith("data:") ||
            url.lowercase().startsWith("file:") || url.lowercase().startsWith("intent:")
        ) {
            return Report(url, "", "", false, listOf(Warning.DANGEROUS_SCHEME))
        }
        val authority = url.substringAfter("://", url).substringBefore('/').substringBefore('?').substringBefore('#')
        if ('@' in authority) warnings += Warning.USERINFO
        val port = authority.substringAfterLast('@').substringAfter(':', "")
        if (port.isNotEmpty() && port != "443" && port != "80") warnings += Warning.NON_STANDARD_PORT
        val host = hostOf(url)
        val site = siteOf(host)
        val isHttps = scheme == "https"
        if (!isHttps) warnings += Warning.NOT_HTTPS
        if (IPV4.matches(host) || host.startsWith("[")) warnings += Warning.IP_ADDRESS
        if (host.split('.').any { it.startsWith("xn--") } || host.any { it.code > 127 }) warnings += Warning.PUNYCODE
        if (site in SHORTENERS || host in SHORTENERS) warnings += Warning.SHORTENER
        val tld = host.substringAfterLast('.', "")
        if (tld in SUSPICIOUS_TLDS) warnings += Warning.SUSPICIOUS_TLD

        var imitates: String? = null
        if (!IPV4.matches(host)) {
            val siteLabel = site.substringBefore('.')
            val skeleton = skeleton(siteLabel)
            val isOfficialAnything = BRANDS.values.any { site in it }
            if (!isOfficialAnything) {
                for ((brand, official) in BRANDS) {
                    if (site in official) continue
                    val b = skeleton(brand)
                    val looksLike = skeleton == b ||
                        (b.length >= 5 && skeleton.length >= 5 && editDistance(skeleton, b) == 1) ||
                        (b.length >= 5 && skeleton.contains(b))
                    if (looksLike) {
                        warnings += Warning.LOOKALIKE
                        imitates = brand
                        break
                    }
                    val subdomains = host.removeSuffix(site).split('.', '-').filter { it.isNotEmpty() }
                    if (b.length >= 3 && subdomains.any { skeleton(it) == b }) {
                        warnings += Warning.BRAND_IN_SUBDOMAIN
                        imitates = brand
                        break
                    }
                }
            }
        }
        return Report(url, host, site, isHttps, warnings.distinct(), imitates)
    }

    /** Undo the usual look-alike substitutions: "saf4ric0m", "rn" for "m", "1"/"i" for "l", hyphens. */
    internal fun skeleton(label: String): String {
        val s = label.lowercase().replace("-", "").replace("_", "").replace("rn", "m").replace("vv", "w")
        val map = mapOf(
            '0' to 'o', '1' to 'l', 'i' to 'l', '3' to 'e', '4' to 'a', '5' to 's', '7' to 't', '8' to 'b',
            '@' to 'a', '$' to 's',
        )
        return s.map { map[it] ?: it }.joinToString("")
    }

    internal fun editDistance(a: String, b: String): Int {
        val dp = IntArray(b.length + 1) { it }
        for (i in 1..a.length) {
            var prev = dp[0]
            dp[0] = i
            for (j in 1..b.length) {
                val tmp = dp[j]
                dp[j] = minOf(dp[j] + 1, dp[j - 1] + 1, prev + if (a[i - 1] == b[j - 1]) 0 else 1)
                prev = tmp
            }
        }
        return dp[b.length]
    }
}
