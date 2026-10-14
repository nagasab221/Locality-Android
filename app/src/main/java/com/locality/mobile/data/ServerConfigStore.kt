package com.locality.mobile.data

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer

data class ServerConfig(
    val host: String,
    val port: Int,
    val token: String,
    val name: String = "",
    /**
     * False for a server reached through someone else's account. We can read and
     * play from it, but nothing of ours is written there — see Repo, which drops
     * watched/progress/track-pref writes rather than mixing our viewing into
     * their Continue Watching.
     */
    val writable: Boolean = true,
) {
    /**
     * `host` is either a bare host/IP reached over plain HTTP on `port` (LAN,
     * Tailscale, manual entry) or a complete https URL when we're coming in
     * through the PC's Cloudflare tunnel, where the port is Cloudflare's, not
     * ours. Detect the scheme rather than storing a second field, so existing
     * saved configs keep working untouched.
     */
    val baseUrl: String
        get() = if (host.startsWith("http://") || host.startsWith("https://")) host.trimEnd('/') + "/"
        else "http://$host:$port/"

    fun imageUrl(rel: String?): String? = rel?.let { "${baseUrl}api/images/$it" }

    // Gallery thumbs/originals carry the token as a query param so BOTH Coil and
    // ExoPlayer authenticate without a custom header (ExoPlayer's data source
    // doesn't go through the shared OkHttp interceptor).
    private val tokenQuery: String get() = if (token.isEmpty()) "" else "?token=$token"
    fun galleryThumbUrl(id: Long): String = "${baseUrl}api/gallery/thumb/$id$tokenQuery"
    fun galleryFullUrl(id: Long): String = "${baseUrl}api/gallery/full/$id$tokenQuery"
}

/** One remembered server. `owned` = it belongs to the signed-in account (or was typed by hand). */
@Serializable
data class SavedServer(
    val id: String,
    val name: String = "",
    val host: String = "",
    val port: Int = 3777,
    val token: String = "",
    val owned: Boolean = true,
) {
    fun toConfig() = ServerConfig(host, port, token, name, writable = owned)
}

private val Context.dataStore by preferencesDataStore(name = "server")

/**
 * Every server this phone knows: your own PC, and any friend's library you've
 * connected to. One is active at a time and that's what the rest of the app
 * talks to; switching is instant because the credentials are already here.
 */
class ServerConfigStore(private val context: Context) {
    // Legacy single-server keys, still read once so an upgrade keeps working.
    private val hostKey = stringPreferencesKey("host")
    private val portKey = stringPreferencesKey("port")
    private val tokenKey = stringPreferencesKey("token")

    private val listKey = stringPreferencesKey("servers")
    private val activeKey = stringPreferencesKey("activeId")

    private fun parse(raw: String?): List<SavedServer> =
        if (raw.isNullOrBlank()) emptyList()
        else runCatching { AppJson.decodeFromString(ListSerializer(SavedServer.serializer()), raw) }
            .getOrDefault(emptyList())

    /** All remembered servers. Migrates a pre-0.11 single config on first read. */
    val servers: Flow<List<SavedServer>> = context.dataStore.data.map { p ->
        val list = parse(p[listKey])
        if (list.isNotEmpty()) return@map list
        val legacyHost = p[hostKey]?.trim().orEmpty()
        if (legacyHost.isEmpty()) emptyList()
        else listOf(
            SavedServer(
                id = "legacy",
                name = "My server",
                host = legacyHost,
                port = p[portKey]?.toIntOrNull() ?: 3777,
                token = p[tokenKey].orEmpty(),
                owned = true,
            )
        )
    }

    /** The server the app is talking to right now, or null when none is set up. */
    val config: Flow<ServerConfig?> = context.dataStore.data.map { p ->
        val list = parse(p[listKey])
        if (list.isEmpty()) {
            val legacyHost = p[hostKey]?.trim().orEmpty()
            return@map if (legacyHost.isEmpty()) null
            else ServerConfig(legacyHost, p[portKey]?.toIntOrNull() ?: 3777, p[tokenKey].orEmpty(), "My server")
        }
        val activeId = p[activeKey]
        (list.firstOrNull { it.id == activeId } ?: list.first()).toConfig()
    }

    val activeId: Flow<String?> = context.dataStore.data.map { p ->
        val list = parse(p[listKey])
        p[activeKey] ?: list.firstOrNull()?.id
    }

    private suspend fun write(list: List<SavedServer>, active: String?) {
        context.dataStore.edit { p ->
            p[listKey] = AppJson.encodeToString(ListSerializer(SavedServer.serializer()), list)
            if (active != null) p[activeKey] = active
            // The legacy keys would otherwise shadow a list we've just emptied.
            p.remove(hostKey)
            p.remove(portKey)
            p.remove(tokenKey)
        }
    }

    /** Existing list, with the pre-0.11 config folded in if it hasn't been yet. */
    private suspend fun current(): List<SavedServer> = servers.first()

    /**
     * Remember a server and (by default) switch to it. Matching is by address,
     * so re-pairing the same PC updates its token instead of duplicating it.
     */
    suspend fun saveServer(server: SavedServer, makeActive: Boolean = true) {
        val list = current().filterNot { it.id == server.id || (it.host == server.host && it.port == server.port) }
        write(list + server, if (makeActive) server.id else null)
    }

    /** Back-compat entry point used by pairing paths that only know an address. */
    suspend fun save(host: String, port: Int, token: String, name: String = "", owned: Boolean = true) {
        val h = host.trim()
        val existing = current().firstOrNull { it.host == h && it.port == port }
        saveServer(
            SavedServer(
                id = existing?.id ?: "srv_" + java.util.UUID.randomUUID().toString().take(8),
                name = name.ifEmpty { existing?.name ?: "" },
                host = h,
                port = port,
                token = token.trim(),
                owned = owned,
            )
        )
    }

    suspend fun setActive(id: String) {
        context.dataStore.edit { it[activeKey] = id }
    }

    suspend fun remove(id: String) {
        val list = current().filterNot { it.id == id }
        write(list, list.firstOrNull()?.id)
    }

    suspend fun clear() {
        context.dataStore.edit { it.clear() }
    }
}
