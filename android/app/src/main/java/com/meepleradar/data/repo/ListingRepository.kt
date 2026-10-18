package com.meepleradar.data.repo

import com.meepleradar.data.model.Action
import com.meepleradar.data.model.IncomingItem
import com.meepleradar.data.model.Source
import com.meepleradar.data.remote.SupabaseApi
import com.meepleradar.data.remote.UserActionRow
import java.time.LocalDate

interface ListingRepository {
    suspend fun incoming(
        today: LocalDate,
        sourceIds: Set<String>? = null,
        includeUndated: Boolean = true,
    ): List<IncomingItem>

    suspend fun favorites(
        today: LocalDate,
        sourceIds: Set<String>? = null,
        includeUndated: Boolean = true,
    ): List<IncomingItem>

    suspend fun sources(): List<Source>

    suspend fun setAction(key: String, action: Action)
}

class DefaultListingRepository(private val api: SupabaseApi) : ListingRepository {

    override suspend fun incoming(
        today: LocalDate,
        sourceIds: Set<String>?,
        includeUndated: Boolean,
    ): List<IncomingItem> {
        val ignored = api.userActions()
            .mapNotNull { row -> row.keyOrNull()?.takeIf { row.action == Action.IGNORED } }
            .toSet()
        return buildItems(ignored, today, sourceIds, includeUndated)
    }

    override suspend fun favorites(
        today: LocalDate,
        sourceIds: Set<String>?,
        includeUndated: Boolean,
    ): List<IncomingItem> {
        val actions = api.userActions()
        val ignored = actions
            .mapNotNull { row -> row.keyOrNull()?.takeIf { row.action != Action.FAVORITE } }
            .toSet()
        val favoriteKeys = actions
            .mapNotNull { row -> row.keyOrNull()?.takeIf { row.action == Action.FAVORITE } }
            .toSet()
        return buildItems(ignored, today, sourceIds, includeUndated).filter { it.key in favoriteKeys }
    }

    override suspend fun sources(): List<Source> = api.sources()

    override suspend fun setAction(key: String, action: Action) {
        val (kind, id) = parseKey(key)
        val row = when (kind) {
            "game" -> UserActionRow(gameId = id, listingId = null, action = action)
            "listing" -> UserActionRow(gameId = null, listingId = id, action = action)
            else -> throw IllegalArgumentException("chiave non valida: $key")
        }
        api.insertAction(row)
    }

    private suspend fun buildItems(
        ignoredKeys: Set<String>,
        today: LocalDate,
        sourceIds: Set<String>?,
        includeUndated: Boolean,
    ): List<IncomingItem> =
        buildIncomingItems(
            listings = api.listings(),
            games = api.games().associateBy { it.id },
            sources = api.sources().associateBy { it.id },
            ignoredKeys = ignoredKeys,
            today = today,
            sourceIds = sourceIds,
            includeUndated = includeUndated,
        )

    private fun parseKey(key: String): Pair<String, Long> {
        val parts = key.split(':', limit = 2)
        require(parts.size == 2 && parts[0].isNotEmpty()) { "chiave non valida: $key" }
        val id = parts[1].toLongOrNull() ?: throw IllegalArgumentException("id non valido in chiave: $key")
        return parts[0] to id
    }

    private fun UserActionRow.keyOrNull(): String? = when {
        gameId != null -> "game:$gameId"
        listingId != null -> "listing:$listingId"
        else -> null
    }
}
