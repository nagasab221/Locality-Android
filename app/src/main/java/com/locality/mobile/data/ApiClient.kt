package com.locality.mobile.data

import com.locality.mobile.data.api.LocalityApi
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import retrofit2.Retrofit
import retrofit2.converter.kotlinx.serialization.asConverterFactory
import java.util.concurrent.TimeUnit

val AppJson = Json {
    ignoreUnknownKeys = true
    coerceInputValues = true
    explicitNulls = false
    encodeDefaults = true
}

/**
 * One OkHttp client for everything (API, Coil images, ExoPlayer streaming,
 * downloads) so the LAN token header rides along on every request. Retrofit is
 * rebuilt whenever the stored server address changes.
 */
class ApiClient(scope: CoroutineScope, configFlow: Flow<ServerConfig?>) {
    @Volatile
    var config: ServerConfig? = null
        private set

    @Volatile
    private var api: LocalityApi? = null

    val okHttp: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(4, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .addInterceptor { chain ->
            val token = config?.token
            // A party guest streams from someone else's server and carries its
            // own ?token=; don't overwrite that with ours.
            val hasOwnToken = chain.request().url.queryParameter("token") != null
            val req = if (token.isNullOrEmpty() || hasOwnToken) chain.request()
            else chain.request().newBuilder().header("X-Locality-Token", token).build()
            chain.proceed(req)
        }
        .build()

    /**
     * One-off GET against an ARBITRARY Locality server (a party host's machine),
     * which is neither the configured server nor covered by the interceptor.
     */
    suspend fun <T> getJson(
        url: String,
        token: String,
        serializer: kotlinx.serialization.DeserializationStrategy<T>,
    ): T = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
        val req = okhttp3.Request.Builder()
            .url(url)
            .apply { if (token.isNotEmpty()) addHeader("X-Locality-Token", token) }
            .build()
        okHttp.newCall(req).execute().use { resp ->
            val raw = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) throw Exception("Host server responded ${resp.code}")
            AppJson.decodeFromString(serializer, raw)
        }
    }

    init {
        scope.launch {
            configFlow.collect { cfg ->
                config = cfg
                api = cfg?.let { buildApi(it) }
            }
        }
    }

    fun api(): LocalityApi? = api

    fun imageUrl(rel: String?): String? = config?.imageUrl(rel)

    private fun buildApi(cfg: ServerConfig): LocalityApi = Retrofit.Builder()
        .baseUrl(cfg.baseUrl)
        .client(okHttp)
        .addConverterFactory(AppJson.asConverterFactory("application/json".toMediaType()))
        .build()
        .create(LocalityApi::class.java)
}
