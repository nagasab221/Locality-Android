package com.locality.mobile.data

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.locality.mobile.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

@Serializable
data class CloudAddress(val host: String = "", val kind: String = "lan")

@Serializable
data class CloudServer(
    @SerialName("server_id") val serverId: String = "",
    val name: String = "",
    val port: Int = 3777,
    val addresses: List<CloudAddress> = emptyList(),
    val token: String = "",
    @SerialName("last_seen") val lastSeen: String? = null,
)

/** Who's signed in. Read from disk, so it survives with no network at all. */
data class CloudSession(val userId: String, val email: String)

@Serializable
private data class AuthResponse(
    @SerialName("access_token") val accessToken: String = "",
    @SerialName("refresh_token") val refreshToken: String = "",
    @SerialName("expires_in") val expiresIn: Long = 3600,
    val user: AuthUser? = null,
)

@Serializable
private data class AuthUser(val id: String = "", val email: String = "")

@Serializable
private data class PasswordGrant(val email: String, val password: String)

@Serializable
private data class RefreshGrant(@SerialName("refresh_token") val refreshToken: String)

private val Context.cloudStore by preferencesDataStore(name = "cloud")

/**
 * The Locality account: Supabase Auth over raw REST (no SDK, to keep the APK lean).
 *
 * Two jobs:
 *  - FIND THE DESKTOP. The PC publishes its addresses + access token to the
 *    account, so the phone can pair by signing in instead of typing an IP.
 *  - BE THE IDENTITY for watch parties and cloud progress, which talk to the
 *    same Supabase project the desktop uses (see electron/server/services/
 *    cloudSync.ts and party.ts — the wire format here must match those).
 *
 * OFFLINE-FIRST: the session lives in DataStore, so signing in once means the
 * app is signed in forever after, with or without a server or internet. Tokens
 * are only refreshed when something actually needs to reach Supabase.
 */
class CloudAuth(private val context: Context) {
    private val emailKey = stringPreferencesKey("email")
    private val accessKey = stringPreferencesKey("access")
    private val refreshKey = stringPreferencesKey("refresh")
    private val userIdKey = stringPreferencesKey("userId")
    private val expiresKey = stringPreferencesKey("expiresAt")

    private val http = OkHttpClient.Builder()
        .connectTimeout(6, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .build()

    private val url = BuildConfig.SUPABASE_URL.trim().trimEnd('/')
    private val anonKey = BuildConfig.SUPABASE_ANON_KEY.trim()

    /** False when the APK was built without Supabase properties — UI hides sign-in. */
    val configured: Boolean get() = url.isNotEmpty() && anonKey.isNotEmpty()

    /** Signed-in account, or null. Emits on sign in/out without a network round trip. */
    val session: Flow<CloudSession?> = context.cloudStore.data.map { p ->
        val id = p[userIdKey].orEmpty()
        val refresh = p[refreshKey].orEmpty()
        if (id.isEmpty() || refresh.isEmpty()) null else CloudSession(id, p[emailKey].orEmpty())
    }

    /** Cached copy so callers that can't suspend (players, poll loops) can still branch. */
    @Volatile
    var current: CloudSession? = null
        private set

    suspend fun load(): CloudSession? = session.first().also { current = it }

    suspend fun savedEmail(): String? = context.cloudStore.data.first()[emailKey]
    suspend fun isSignedIn(): Boolean = context.cloudStore.data.first()[refreshKey]?.isNotEmpty() == true
    suspend fun userId(): String? = context.cloudStore.data.first()[userIdKey]?.takeIf { it.isNotEmpty() }

    suspend fun signOut() {
        context.cloudStore.edit { it.clear() }
        current = null
    }

    private fun jsonBody(body: String) = body.toRequestBody("application/json".toMediaType())

    /** Supabase returns JSON errors — surface its message, not a bare status code. */
    private fun errorOf(raw: String?, fallback: String): String {
        if (raw.isNullOrBlank()) return fallback
        return Regex("\"(?:error_description|msg|message|error)\"\\s*:\\s*\"([^\"]+)\"")
            .find(raw)?.groupValues?.get(1) ?: fallback
    }

    private suspend fun store(data: AuthResponse, fallbackEmail: String) {
        context.cloudStore.edit {
            it[accessKey] = data.accessToken
            it[refreshKey] = data.refreshToken
            it[emailKey] = data.user?.email ?: fallbackEmail
            if (!data.user?.id.isNullOrEmpty()) it[userIdKey] = data.user!!.id
            it[expiresKey] = (System.currentTimeMillis() + data.expiresIn * 1000).toString()
        }
        current = CloudSession(
            data.user?.id ?: context.cloudStore.data.first()[userIdKey].orEmpty(),
            data.user?.email ?: fallbackEmail,
        )
    }

    /** Create an account. The project has email confirmation OFF, so this signs in too. */
    suspend fun signUp(email: String, password: String): Result<Unit> = withContext(Dispatchers.IO) {
        if (!configured) return@withContext Result.failure(Exception("This build has no Supabase settings"))
        try {
            val req = Request.Builder()
                .url("$url/auth/v1/signup")
                .addHeader("apikey", anonKey)
                .post(jsonBody(AppJson.encodeToString(PasswordGrant.serializer(), PasswordGrant(email, password))))
                .build()
            http.newCall(req).execute().use { resp ->
                val raw = resp.body?.string()
                if (!resp.isSuccessful) {
                    return@withContext Result.failure(Exception(errorOf(raw, "Sign up failed (${resp.code})")))
                }
                val data = AppJson.decodeFromString(AuthResponse.serializer(), raw ?: "{}")
                if (data.accessToken.isEmpty()) {
                    // Confirmation is switched on for the project: no session yet.
                    return@withContext Result.failure(
                        Exception("Account created — check your email to confirm it, then sign in.")
                    )
                }
                store(data, email)
                Result.success(Unit)
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun signIn(email: String, password: String): Result<Unit> = withContext(Dispatchers.IO) {
        if (!configured) return@withContext Result.failure(Exception("This build has no Supabase settings"))
        try {
            val req = Request.Builder()
                .url("$url/auth/v1/token?grant_type=password")
                .addHeader("apikey", anonKey)
                .post(jsonBody(AppJson.encodeToString(PasswordGrant.serializer(), PasswordGrant(email, password))))
                .build()
            http.newCall(req).execute().use { resp ->
                val raw = resp.body?.string()
                if (!resp.isSuccessful) {
                    return@withContext Result.failure(Exception(errorOf(raw, "Sign in failed (${resp.code})")))
                }
                store(AppJson.decodeFromString(AuthResponse.serializer(), raw ?: "{}"), email)
                Result.success(Unit)
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /** Swap the stored refresh token for a fresh access token. */
    private suspend fun refreshAccess(): String? = withContext(Dispatchers.IO) {
        val prefs = context.cloudStore.data.first()
        val refresh = prefs[refreshKey].orEmpty()
        if (refresh.isEmpty()) return@withContext null
        try {
            val req = Request.Builder()
                .url("$url/auth/v1/token?grant_type=refresh_token")
                .addHeader("apikey", anonKey)
                .post(jsonBody(AppJson.encodeToString(RefreshGrant.serializer(), RefreshGrant(refresh))))
                .build()
            http.newCall(req).execute().use { resp ->
                val raw = resp.body?.string()
                if (!resp.isSuccessful) return@withContext null
                val data = AppJson.decodeFromString(AuthResponse.serializer(), raw ?: "{}")
                store(data, prefs[emailKey].orEmpty())
                data.accessToken
            }
        } catch (_: Exception) {
            null
        }
    }

    /** A usable access token, refreshed shortly before expiry. Null when signed out. */
    suspend fun accessToken(): String? {
        val prefs = context.cloudStore.data.first()
        val access = prefs[accessKey].orEmpty()
        val expires = prefs[expiresKey]?.toLongOrNull() ?: 0L
        if (access.isEmpty() || System.currentTimeMillis() > expires - 60_000) return refreshAccess()
        return access
    }

    // ---- PostgREST ------------------------------------------------------------------
    //
    // Same surface the desktop's CloudSync exposes to its Party service, so the
    // two speak an identical protocol against the same tables.

    /**
     * Authenticated PostgREST call. [path] is everything after /rest/v1/.
     * Returns the raw body ("" for 204). Throws when signed out or on failure —
     * the party poll loop treats that as "try again next tick".
     */
    suspend fun rest(
        path: String,
        method: String = "GET",
        body: String? = null,
        prefer: String? = null,
    ): String = withContext(Dispatchers.IO) {
        if (!configured) throw IllegalStateException("Cloud accounts aren't configured in this build")
        var token = accessToken() ?: throw IllegalStateException("Signed out")

        fun call(t: String) = http.newCall(
            Request.Builder()
                .url("$url/rest/v1/$path")
                .addHeader("apikey", anonKey)
                .addHeader("Authorization", "Bearer $t")
                .addHeader("Content-Type", "application/json")
                .apply { if (prefer != null) addHeader("Prefer", prefer) }
                .method(method, if (method == "GET" || method == "DELETE") null else jsonBody(body ?: "{}"))
                .build()
        ).execute()

        var resp = call(token)
        if (resp.code == 401) { // access token died between the check and the call
            resp.close()
            token = refreshAccess() ?: throw IllegalStateException("Session expired")
            resp = call(token)
        }
        resp.use { r ->
            val raw = r.body?.string().orEmpty()
            if (!r.isSuccessful) throw Exception(errorOf(raw, "Cloud request failed (${r.code})"))
            raw
        }
    }

    /** Call a Postgres function exposed over PostgREST (used for account lookups). */
    suspend fun rpc(fn: String, argsJson: String): String = rest("rpc/$fn", "POST", argsJson)

    // ---- server discovery -------------------------------------------------------------

    private fun fetchServers(token: String): List<CloudServer> {
        val req = Request.Builder()
            .url("$url/rest/v1/servers?select=server_id,name,port,addresses,token,last_seen&order=last_seen.desc")
            .addHeader("apikey", anonKey)
            .addHeader("Authorization", "Bearer $token")
            .build()
        http.newCall(req).execute().use { r ->
            val raw = r.body?.string()
            if (!r.isSuccessful) throw Exception(errorOf(raw, "Lookup failed (${r.code})"))
            return AppJson.decodeFromString(ListSerializer(CloudServer.serializer()), raw ?: "[]")
        }
    }

    /** Servers this account has published, most recently seen first. */
    suspend fun servers(): Result<List<CloudServer>> = withContext(Dispatchers.IO) {
        if (!configured) return@withContext Result.failure(Exception("This build has no Supabase settings"))
        try {
            val access = accessToken() ?: return@withContext Result.failure(Exception("Please sign in again"))
            Result.success(fetchServers(access))
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * Sign in just long enough to read ANOTHER account's server list — how you
     * reach a friend's library. Deliberately never calls [store]: their
     * credentials are used once and dropped, so this can't sign you out of your
     * own account or move your watch progress into theirs. Mirrors the desktop's
     * CloudSync.lookupServers.
     */
    suspend fun lookupServers(email: String, password: String): Result<List<CloudServer>> =
        withContext(Dispatchers.IO) {
            if (!configured) return@withContext Result.failure(Exception("This build has no Supabase settings"))
            try {
                val req = Request.Builder()
                    .url("$url/auth/v1/token?grant_type=password")
                    .addHeader("apikey", anonKey)
                    .post(jsonBody(AppJson.encodeToString(PasswordGrant.serializer(), PasswordGrant(email, password))))
                    .build()
                val token = http.newCall(req).execute().use { resp ->
                    val raw = resp.body?.string()
                    if (!resp.isSuccessful) throw Exception(errorOf(raw, "Sign in failed (${resp.code})"))
                    AppJson.decodeFromString(AuthResponse.serializer(), raw ?: "{}").accessToken
                }
                if (token.isEmpty()) throw Exception("Sign in failed")
                Result.success(fetchServers(token))
            } catch (e: Exception) {
                Result.failure(e)
            }
        }

    /**
     * Base URL for one published address. LAN and Tailscale entries are bare
     * IPs on the server's own port; the Cloudflare tunnel publishes a complete
     * https URL, where the port belongs to Cloudflare rather than to us.
     */
    private fun baseOf(addr: CloudAddress, port: Int): String =
        if (addr.host.startsWith("http://") || addr.host.startsWith("https://")) addr.host.trimEnd('/')
        else "http://${addr.host}:$port"

    /**
     * Probe every published address at once and take the first that answers *in
     * preference order* — not the first to reply. The PC publishes LAN, then
     * Tailscale, then its tunnel, so at home we stay on the direct route even if
     * Cloudflare happens to respond sooner, and away from home we fall through
     * to the tunnel without asking the user anything.
     *
     * Returns a base URL, which the caller stores as the host.
     */
    suspend fun reachableHost(server: CloudServer): String? = coroutineScope {
        // Cancelling the coroutine isn't enough to abandon a loser: OkHttp's
        // execute() blocks on a socket and ignores interruption. Cancelling the
        // Call closes it, which is what actually makes the early return early.
        val calls = java.util.Collections.synchronizedList(mutableListOf<okhttp3.Call>())
        val tries = server.addresses.map { addr ->
            val base = baseOf(addr, server.port)
            // The tunnel goes phone -> Cloudflare -> PC, so it needs a longer
            // leash than a probe across the living room.
            val seconds = if (addr.kind == "remote") 10L else 3L
            async(Dispatchers.IO) {
                try {
                    val probe = OkHttpClient.Builder()
                        .connectTimeout(seconds, TimeUnit.SECONDS)
                        .readTimeout(seconds, TimeUnit.SECONDS)
                        .build()
                    val req = Request.Builder()
                        .url("$base/api/state")
                        .addHeader("X-Locality-Token", server.token)
                        .build()
                    val call = probe.newCall(req)
                    calls.add(call)
                    call.execute().use { if (it.isSuccessful) base else null }
                } catch (_: Exception) {
                    null
                }
            }
        }
        var winner: String? = null
        for (t in tries) {
            val r = try { t.await() } catch (_: Exception) { null }
            if (r != null) {
                winner = r
                break
            }
        }
        if (winner != null) synchronized(calls) { calls.forEach { it.cancel() } }
        winner
    }
}
