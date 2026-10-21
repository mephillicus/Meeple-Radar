package com.meepleradar.ui.incoming

import com.meepleradar.data.model.IncomingItem
import com.meepleradar.data.model.ItemKind
import com.meepleradar.data.model.Offer
import java.time.format.DateTimeFormatter
import java.util.Locale

const val NO_PRICE_LABEL: String = "n/d"
const val UNDATED_LABEL: String = "Novità — data da confermare"

private val DATE_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("dd/MM/yyyy")

fun priceLabel(offer: Offer): String {
    val price = offer.price ?: return NO_PRICE_LABEL
    return if (price.currency == "EUR") {
        "%.2f €".format(Locale.ITALY, price.amount)
    } else {
        "%.2f %s".format(Locale.US, price.amount, price.currency)
    }
}

fun bestPriceLabel(offers: List<Offer>): String {
    val chosen = bestPricedOffer(offers)
        ?: return NO_PRICE_LABEL
    return priceLabel(chosen)
}

fun dateLabel(item: IncomingItem): String {
    val releaseDate = item.releaseDate
    return if (item.kind == ItemKind.UPCOMING && releaseDate != null) {
        "In uscita il ${releaseDate.format(DATE_FORMAT)}"
    } else {
        UNDATED_LABEL
    }
}
