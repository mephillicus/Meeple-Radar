package com.meepleradar.data.model

import java.time.LocalDate
import java.util.Locale

enum class AvailabilityStatus {
    AVAILABLE,
    UNAVAILABLE,
    PREORDER,
    BACKORDER,
    DISCONTINUED,
    UNKNOWN,
}

fun availabilityStatus(value: String?, releaseDate: LocalDate? = null, today: LocalDate = LocalDate.now()): AvailabilityStatus {
    if (releaseDate != null && !releaseDate.isBefore(today)) {
        return AvailabilityStatus.PREORDER
    }
    val token = value
        ?.substringAfterLast('/')
        ?.lowercase(Locale.ROOT)
        ?.filter(Char::isLetterOrDigit)
        ?: return AvailabilityStatus.UNKNOWN

    return when (token) {
        "true", "1", "yes", "available", "instock", "limitedavailability",
        "onlineonly", "instoreonly",
        -> AvailabilityStatus.AVAILABLE

        "false", "0", "no", "outofstock", "soldout", "unavailable",
        "notavailable", "nondisponibile", "esaurito", "esaurita", "esauriti",
        "esaurite", "noninmagazzino",
        -> AvailabilityStatus.UNAVAILABLE

        "preorder", "presale", "preordine", "preordina" -> AvailabilityStatus.PREORDER
        "backorder", "backordered", "ordinabile", "suordinazione" -> AvailabilityStatus.BACKORDER
        "discontinued", "fuoriproduzione" -> AvailabilityStatus.DISCONTINUED
        else -> AvailabilityStatus.UNKNOWN
    }
}
