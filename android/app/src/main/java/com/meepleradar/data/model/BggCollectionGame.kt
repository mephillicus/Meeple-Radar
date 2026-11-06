package com.meepleradar.data.model

import kotlinx.serialization.Serializable

@Serializable
data class BggCollectionGame(
    val bggId: Int,
    val name: String,
    val kind: String,
    val yearPublished: Int?,
    val imageUrl: String?,
    val rating: Double?,
    val expandsBggId: Int? = null,
)

@Serializable
data class BggExpansion(
    val bggId: Int,
    val name: String,
    val expandsBggId: Int,
    val url: String,
)
