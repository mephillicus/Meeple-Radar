package com.meepleradar.ui.components

import com.meepleradar.data.model.AvailabilityStatus
import com.meepleradar.data.model.IncomingItem
import com.meepleradar.data.model.availabilityStatus
import com.meepleradar.data.repo.bestEurPrice
import java.time.LocalDate
import java.util.Locale

enum class ListingSortOption(val label: String) {
    DEFAULT("Predefinito"),
    TITLE_ASC("A-Z"),
    PRICE_ASC("Prezzo ↑"),
    RELEASE_DATE_ASC("Data ↑"),
    RELEASE_DATE_DESC("Data ↓"),
    BGG_RATING_DESC("Voto BGG ↓"),
}

val ListingSortOption.isAlphabetical: Boolean
    get() = this == ListingSortOption.DEFAULT || this == ListingSortOption.TITLE_ASC

data class ListingFilterState(
    val selectedSources: Set<String>? = null,
    val hideUndated: Boolean = false,
    val hidePriceless: Boolean = false,
    val hideSeen: Boolean = false,
    val selectedAvailability: Set<AvailabilityStatus> = AvailabilityStatus.entries.toSet(),
    val searchQuery: String = "",
    val showOnlyNewlyAdded: Boolean = false,
    val sortOption: ListingSortOption = ListingSortOption.DEFAULT,
)

private val ALL_AVAILABILITY_STATUSES: Set<AvailabilityStatus> = AvailabilityStatus.entries.toSet()

fun filterAndSortListings(
    items: List<IncomingItem>,
    state: ListingFilterState,
    today: LocalDate = LocalDate.now(),
    matchItemReleaseDateAsPreorder: Boolean = true,
): List<IncomingItem> {
    val query = state.searchQuery.trim()
    val queryWords = if (query.length >= 3) {
        query.lowercase(Locale.ROOT).split(Regex("\\s+")).filter { it.isNotBlank() }
    } else {
        emptyList()
    }

    val allAvailabilitySelected = state.selectedAvailability == ALL_AVAILABILITY_STATUSES

    val filtered = items.filter { item ->
        val sourceMatch = state.selectedSources == null || item.offers.any { it.sourceId in state.selectedSources }
        val dateMatch = !state.hideUndated || item.releaseDate != null
        val priceMatch = !state.hidePriceless || item.offers.any { it.price != null }
        val seenMatch = !state.hideSeen || !item.isSeen
        val availabilityMatch = if (allAvailabilitySelected) {
            true
        } else {
            val isItemPreorder = matchItemReleaseDateAsPreorder &&
                item.releaseDate != null &&
                !item.releaseDate.isBefore(today)
            (AvailabilityStatus.PREORDER in state.selectedAvailability && isItemPreorder) ||
                item.offers.any { availabilityStatus(it.availability, it.releaseDate, today) in state.selectedAvailability }
        }
        val searchMatch = if (queryWords.isEmpty()) {
            true
        } else {
            val titleLower = item.title.lowercase(Locale.ROOT)
            queryWords.all { word -> titleLower.contains(word) }
        }
        val newlyAddedMatch = !state.showOnlyNewlyAdded || item.isNewlyAdded

        sourceMatch && dateMatch && priceMatch && seenMatch && availabilityMatch && searchMatch && newlyAddedMatch
    }

    return sortListings(filtered, state.sortOption)
}

fun sortListings(
    items: List<IncomingItem>,
    sortOption: ListingSortOption,
): List<IncomingItem> = when (sortOption) {
    ListingSortOption.DEFAULT -> items
    ListingSortOption.TITLE_ASC -> items.sortedBy { it.title.lowercase(Locale.ROOT) }
    ListingSortOption.PRICE_ASC -> items.sortedWith(
        compareBy<IncomingItem>(
            { bestEurPrice(it.offers)?.amount == null },
            { bestEurPrice(it.offers)?.amount ?: Double.MAX_VALUE },
            { it.title.lowercase(Locale.ROOT) },
        ),
    )
    ListingSortOption.RELEASE_DATE_ASC -> items.sortedWith(
        compareBy<IncomingItem>(
            { it.releaseDate == null },
            { it.releaseDate ?: LocalDate.MAX },
            { it.title.lowercase(Locale.ROOT) },
        ),
    )
    ListingSortOption.RELEASE_DATE_DESC -> items.sortedWith(
        compareBy<IncomingItem>({ it.releaseDate == null })
            .thenByDescending { it.releaseDate ?: LocalDate.MIN }
            .thenBy { it.title.lowercase(Locale.ROOT) },
    )
    ListingSortOption.BGG_RATING_DESC -> items.sortedWith(
        compareBy<IncomingItem>({ (it.game?.bggRating ?: 0.0) <= 0.0 })
            .thenByDescending { it.game?.bggRating?.takeIf { rating -> rating > 0.0 } ?: Double.NEGATIVE_INFINITY }
            .thenBy { it.title.lowercase(Locale.ROOT) },
    )
}
