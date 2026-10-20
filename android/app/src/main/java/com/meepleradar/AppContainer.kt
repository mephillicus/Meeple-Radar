package com.meepleradar

import android.content.Context
import com.meepleradar.data.local.SharedPreferencesFavoriteStore
import com.meepleradar.data.local.SharedPreferencesLocalCacheStore
import com.meepleradar.data.remote.BoardGameGeekImageLookup
import com.meepleradar.data.remote.BoardGameImageLookup
import com.meepleradar.data.remote.GeekGroupApi
import com.meepleradar.data.remote.GeekGroupRestApi
import com.meepleradar.data.remote.OkHttpTransport
import com.meepleradar.data.remote.SupabaseApi
import com.meepleradar.data.remote.SupabaseRestApi
import com.meepleradar.data.repo.DefaultListingRepository
import com.meepleradar.data.repo.ListingRepository
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient

class AppContainer(context: Context) {

    private val json = Json { ignoreUnknownKeys = true }
    private val transport = OkHttpTransport(OkHttpClient())

    val geekGroupApi: GeekGroupApi = GeekGroupRestApi(transport, json)

    val boardGameImageLookup: BoardGameImageLookup = BoardGameGeekImageLookup(
        transport = transport,
        geekGroupApi = geekGroupApi,
    )

    private val api: SupabaseApi = SupabaseRestApi(
        baseUrl = BuildConfig.SUPABASE_URL,
        anonKey = BuildConfig.SUPABASE_ANON_KEY,
        transport = transport,
        json = json,
    )

    val repository: ListingRepository = DefaultListingRepository(
        api = api,
        favoriteStore = SharedPreferencesFavoriteStore(context.applicationContext),
        localCacheStore = SharedPreferencesLocalCacheStore(context.applicationContext, json),
    )
}
