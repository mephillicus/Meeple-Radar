package com.meepleradar.data.model

import java.time.LocalDate
import java.util.Locale

enum class AvailabilityStatus {
    AVAILABLE,
    UNAVAILABLE,
    PREORDER,
    BACKORDER,
    REPRINT,
    DISCONTINUED,
    UNKNOWN,
}

fun availabilityStatus(
    value: String?,
    releaseDate: LocalDate? = null,
    today: LocalDate = LocalDate.now(),
    reprintDate: LocalDate? = null,
): AvailabilityStatus {
    if (releaseDate != null && !releaseDate.isBefore(today)) {
        return AvailabilityStatus.PREORDER
    }
    val token = value
        ?.substringAfterLast('/')
        ?.lowercase(Locale.ROOT)
        ?.filter(Char::isLetterOrDigit)

    val parsed = when (token) {
        null, "" -> AvailabilityStatus.UNKNOWN

        "true", "1", "yes", "available", "instock", "limitedavailability",
        "onlineonly", "instoreonly",
        -> AvailabilityStatus.AVAILABLE

        "false", "0", "no", "outofstock", "soldout", "unavailable",
        "notavailable", "nondisponibile", "esaurito", "esaurita", "esauriti",
        "esaurite", "noninmagazzino",
        -> AvailabilityStatus.UNAVAILABLE

        "preorder", "presale", "preordine", "preordina" -> AvailabilityStatus.PREORDER
        "backorder", "backordered", "ordinabile", "suordinazione" -> AvailabilityStatus.BACKORDER
        "reprint", "ristampa", "inristampa", "riassortimento", "inriassortimento" -> AvailabilityStatus.REPRINT
        "discontinued", "fuoriproduzione" -> AvailabilityStatus.DISCONTINUED
        else -> AvailabilityStatus.UNKNOWN
    }

    if (
        reprintDate != null &&
        !reprintDate.isBefore(today) &&
        parsed in setOf(
            AvailabilityStatus.UNAVAILABLE,
            AvailabilityStatus.BACKORDER,
            AvailabilityStatus.UNKNOWN,
        )
    ) {
        return AvailabilityStatus.REPRINT
    }

    return parsed
}
