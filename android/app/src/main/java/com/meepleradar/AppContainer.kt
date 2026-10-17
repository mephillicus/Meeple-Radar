package com.meepleradar

import android.content.Context
import com.meepleradar.data.remote.OkHttpTransport
import com.meepleradar.data.remote.SupabaseApi
import com.meepleradar.data.remote.SupabaseRestApi
import com.meepleradar.data.repo.DefaultListingRepository
import com.meepleradar.data.repo.ListingRepository
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient

class AppContainer(context: Context) {

    private val json = Json { ignoreUnknownKeys = true }

    private val api: SupabaseApi = SupabaseRestApi(
        baseUrl = BuildConfig.SUPABASE_URL,
        anonKey = BuildConfig.SUPABASE_ANON_KEY,
        transport = OkHttpTransport(OkHttpClient()),
        json = json,
    )

    val repository: ListingRepository = DefaultListingRepository(api)
}
