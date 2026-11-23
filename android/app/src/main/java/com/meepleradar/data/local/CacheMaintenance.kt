package com.meepleradar.data.local

import android.content.Context
import coil3.SingletonImageLoader
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient

/**
 * Contratto per misurare e pulire le cache media/HTTP locali (Coil + OkHttp).
 */
interface CacheMaintenance {
    suspend fun cacheSizeBytes(): Long = 0L

    suspend fun clearImageAndHttpCache(): Long = 0L
}

class AndroidCacheMaintenance(
    private val appContext: Context,
    private val okHttpClient: OkHttpClient,
    private val httpCacheDir: File,
) : CacheMaintenance {

    override suspend fun cacheSizeBytes(): Long = withContext(Dispatchers.IO) {
        val httpBytes = directorySizeBytes(httpCacheDir)
        val imageLoader = runCatching { SingletonImageLoader.get(appContext) }.getOrNull()
        val coilDiskBytes = imageLoader?.diskCache?.size
            ?: directorySizeBytes(File(appContext.cacheDir, "image_cache"))
        httpBytes + coilDiskBytes
    }

    override suspend fun clearImageAndHttpCache(): Long = withContext(Dispatchers.IO) {
        runCatching { okHttpClient.cache?.evictAll() }
        runCatching {
            val imageLoader = SingletonImageLoader.get(appContext)
            imageLoader.memoryCache?.clear()
            imageLoader.diskCache?.clear()
        }
        cacheSizeBytes()
    }

    private fun directorySizeBytes(dir: File): Long {
        if (!dir.exists()) return 0L
        if (dir.isFile) return dir.length()
        return dir.walkTopDown()
            .filter { it.isFile }
            .sumOf { it.length() }
    }
}
