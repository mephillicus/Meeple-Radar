package com.meepleradar

import android.content.Context
import com.meepleradar.data.local.NotificationPreferencesStore
import com.meepleradar.data.local.RoomLocalCacheStore
import com.meepleradar.data.local.SharedPreferencesBggCollectionStore
import com.meepleradar.data.local.SharedPreferencesBggSelectionStore
import com.meepleradar.data.local.SharedPreferencesFavoriteStore
import com.meepleradar.data.local.SharedPreferencesLocalCacheStore
import com.meepleradar.data.local.SharedPreferencesNotificationPreferencesStore
import com.meepleradar.data.local.SharedPreferencesSeenStore
import com.meepleradar.data.local.db.MeepleRadarDatabase
import com.meepleradar.data.remote.BggCollectionApi
import com.meepleradar.data.remote.BggXmlCollectionApi
import com.meepleradar.data.remote.BoardGameGeekImageLookup
import com.meepleradar.data.remote.BoardGameImageLookup
import com.meepleradar.data.remote.GeekGroupApi
import com.meepleradar.data.remote.GeekGroupRestApi
import com.meepleradar.data.remote.OkHttpTransport
import com.meepleradar.data.remote.SupabaseApi
import com.meepleradar.data.remote.SupabaseRestApi
import com.meepleradar.data.repo.DefaultListingRepository
import com.meepleradar.data.repo.ListingRepository
import com.meepleradar.notifications.SyncWorkScheduler
import java.io.File
import java.util.concurrent.TimeUnit
import kotlinx.serialization.json.Json
import okhttp3.Cache
import okhttp3.OkHttpClient

class AppContainer(context: Context) {

    private val appContext = context.applicationContext
    private val json = Json { ignoreUnknownKeys = true }
    private val okHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .callTimeout(45, TimeUnit.SECONDS)
        .cache(Cache(File(appContext.cacheDir, HTTP_CACHE_DIR), HTTP_CACHE_SIZE_BYTES))
        .build()
    private val transport = OkHttpTransport(okHttpClient)

    val geekGroupApi: GeekGroupApi = GeekGroupRestApi(transport, json)

    val boardGameImageLookup: BoardGameImageLookup = BoardGameGeekImageLookup(
        transport = transport,
        geekGroupApi = geekGroupApi,
    )
    val bggSelectionStore = SharedPreferencesBggSelectionStore(appContext)
    val bggCollectionStore = SharedPreferencesBggCollectionStore(appContext, json)
    val bggCollectionApi: BggCollectionApi = BggXmlCollectionApi(transport)
    val notificationPreferencesStore: NotificationPreferencesStore =
        SharedPreferencesNotificationPreferencesStore(appContext)
    val syncWorkScheduler: SyncWorkScheduler = SyncWorkScheduler(appContext)

    private val database: MeepleRadarDatabase = MeepleRadarDatabase.create(appContext)

    private val api: SupabaseApi = SupabaseRestApi(
        baseUrl = BuildConfig.SUPABASE_URL,
        anonKey = BuildConfig.SUPABASE_ANON_KEY,
        transport = transport,
        json = json,
    )

    val repository: ListingRepository = DefaultListingRepository(
        api = api,
        favoriteStore = SharedPreferencesFavoriteStore(appContext),
        localCacheStore = RoomLocalCacheStore(
            context = appContext,
            database = database,
            legacyStore = SharedPreferencesLocalCacheStore(appContext, json),
        ),
        bggCollectionStore = bggCollectionStore,
        seenStore = SharedPreferencesSeenStore(appContext),
    )

    private companion object {
        const val HTTP_CACHE_DIR = "http_cache"
        const val HTTP_CACHE_SIZE_BYTES = 20L * 1024L * 1024L
    }
}
