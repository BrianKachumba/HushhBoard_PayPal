/*
 * Copyright (C) 2026 HushhBoard
 * Licensed under the Apache License, Version 2.0.
 */

package dev.hushhboard.nlp.entities

/** Kenyan towns, Nairobi estates and landmarks people send as meeting points (lower case). */
internal object KenyaPlaces {
    val NAMES: List<String> = listOf(
        // Cities and towns
        "nairobi", "mombasa", "kisumu", "nakuru", "eldoret", "thika", "machakos", "nyeri", "meru", "embu",
        "kakamega", "kericho", "kitale", "malindi", "lamu", "garissa", "naivasha", "nanyuki", "kiambu",
        "ruiru", "juja", "kitengela", "ngong", "rongai", "kikuyu", "limuru", "athi river", "mlolongo",
        "syokimau", "kitui", "bungoma", "busia", "kisii", "migori", "homa bay", "bomet", "narok", "kajiado",
        "voi", "diani", "kilifi", "watamu", "isiolo", "marsabit", "lodwar", "wajir", "mandera", "nyahururu",
        "murang'a", "muranga", "kerugoya", "karatina", "naro moru", "molo", "njoro", "webuye", "vihiga",
        "siaya", "bondo", "maseno", "kapsabet", "iten", "kabarnet", "maralal", "chuka", "mwea", "makueni",
        "kangundo", // Nairobi neighbourhoods
        "westlands", "kilimani", "kileleshwa", "lavington", "langata", "lang'ata", "parklands",
        "eastleigh", "south b", "south c", "embakasi", "kasarani", "roysambu", "githurai",
        "zimmerman", "umoja", "donholm", "buruburu", "kayole", "utawala", "imara daima",
        "industrial area", "upper hill", "upperhill", "hurlingham", "riverside", "spring valley", "runda",
        "muthaiga", "gigiri", "ruaka", "kangemi", "kawangware", "dagoretti", "kibera", "mathare",
        "kariobangi", "dandora", "ruai", "kamulu", "joska", "tassia", "nyayo",
        "ngara", "pangani", "kilimani", "yaya", "valley arcade", "woodley",
        "ongata rongai", "kitisuru", "loresho", "mountain view", "uthiru", "kinoo", "mwiki",
        "thome", "garden estate", "ridgeways", "kiambu road", "thindigua",
        // Landmarks and malls
        "cbd", "jkia", "wilson airport", "sarit centre", "the junction", "junction mall", "two rivers",
        "garden city", "the hub", "village market", "westgate", "yaya centre", "prestige plaza",
        "galleria", "thika road mall", "trm", "uhuru park", "central park", "kicc", "times tower",
        "kencom", "odeon", "ambassadeur", "afya centre", "nation centre",
        "moi avenue", "kenyatta avenue", "tom mboya", "river road", "luthuli", "koinange street",
        "thika road", "mombasa road", "ngong road", "waiyaki way", "jogoo road", "langata road",
        "outering road", "kangundo road", "lower kabete", "argwings kodhek", "kenyatta hospital",
        "knh", "aga khan", "nairobi hospital", "mater hospital", "uon", "kenyatta university",
        "strathmore", "usiu", "jkuat", "moi university", "egerton", "maseno university",
        "nyali", "bamburi", "shanzu", "mtwapa", "likoni", "kisauni", "fort jesus", "old town",
    ).distinct().sortedByDescending { it.length }
}
