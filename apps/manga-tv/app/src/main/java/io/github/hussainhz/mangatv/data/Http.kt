package io.github.hussainhz.mangatv.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Credentials
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.IOException
import java.util.concurrent.TimeUnit

object Http {
    /**
     * Shared client for catalogs and page images. Adds HTTP Basic auth for any request that
     * goes to a saved server (same scheme, host and port), so Coil image loads are authorised too.
     */
    val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .addInterceptor { chain ->
            val request = chain.request()
            val server = Store.servers.firstOrNull { s ->
                val base = s.url.toHttpUrlOrNull() ?: return@firstOrNull false
                s.username.isNotEmpty() && base.scheme == request.url.scheme &&
                    base.host == request.url.host && base.port == request.url.port
            }
            val authed = if (server != null && request.header("Authorization") == null) {
                request.newBuilder()
                    .header("Authorization", Credentials.basic(server.username, server.password))
                    .build()
            } else request
            chain.proceed(authed.newBuilder().header("User-Agent", "MangaTV/0.1 (Android TV)").build())
        }
        .build()

    suspend fun <T> get(url: String, block: (Response) -> T): T = withContext(Dispatchers.IO) {
        client.newCall(Request.Builder().url(url).build()).execute().use { response ->
            if (!response.isSuccessful) throw IOException("HTTP ${response.code}")
            block(response)
        }
    }

    suspend fun getString(url: String): String = get(url) { it.body!!.string() }
}
