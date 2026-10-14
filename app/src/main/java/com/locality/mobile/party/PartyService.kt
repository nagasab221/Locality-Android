package com.locality.mobile.party

import com.locality.mobile.data.ApiClient
import com.locality.mobile.data.AppJson
import com.locality.mobile.data.CloudAuth
import com.locality.mobile.data.db.DownloadFileRow
import com.locality.mobile.data.db.LocalityDb
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import java.net.URLEncoder

/**
 * Watch party — the phone's half of the protocol implemented by the desktop's
 * electron/server/services/party.ts. Both talk to the SAME Supabase tables
 * (parties, party_members, party_state, party_contacts, profiles) over plain
 * PostgREST, so a phone and a Windows or Linux PC can sit in the same party.
 *
 * Two modes, chosen by whoever starts it:
 *   "local"  — everyone plays their OWN copy, matched by Locality's
 *              path-independent fingerprint. No video crosses the network.
 *              On a phone that copy is a download, or the connected server's file.
 *   "stream" — only the host has it; guests play the host's server directly.
 *
 * Playback state is POLLED (no websockets), exactly like the desktop, so the
 * two implementations stay behaviourally identical.
 */
const val PARTY_MAX = 6

private const val STATE_POLL_MS = 700L
private const val MEMBER_POLL_EVERY = 5
private const val INVITE_POLL_MS = 8000L
private const val HEARTBEAT_MS = 15_000L

enum class PartyMode(val wire: String) {
    Local("local"),
    Stream("stream");

    companion object {
        fun of(s: String?) = if (s == "stream") Stream else Local
    }
}

data class PartyMember(
    val userId: String,
    val username: String,
    val status: String, // invited | joined | declined | left
    val isHost: Boolean,
    val hasFile: Boolean,
    val lastSeen: String?,
)

data class PartyPlayback(
    val position: Double = 0.0,
    val paused: Boolean = true,
    val seq: Long = 0,
    val updatedBy: String? = null,
    val updatedAt: String? = null,
    /**
     * When THIS device saw the action, on its own clock (SystemClock-style ms).
     *
     * Deliberately not derived from [updatedAt]: that's the publisher's wall
     * clock, and a PC and a phone are routinely a second or two apart, which is
     * exactly the size of error we're trying to correct. Ageing an action by
     * local elapsed time since receipt is immune to that.
     */
    val receivedAt: Long = 0L,
)

data class PartyInvite(
    val partyId: String,
    val title: String,
    val mode: PartyMode,
    val hostUsername: String,
    val hasFile: Boolean,
)

data class PartyContact(val userId: String, val username: String, val email: String, val lastUsed: String?)

data class HostServer(val url: String, val token: String)

data class ActiveParty(
    val id: String,
    val mode: PartyMode,
    val title: String,
    val fingerprint: String,
    val hostFileId: Long?,
    val isHost: Boolean,
    val members: List<PartyMember>,
    val playback: PartyPlayback,
    /** "stream" guests only: where to fetch the video from. */
    val hostServer: HostServer?,
    /** This phone's own file id for the party's fingerprint, when it has it. */
    val localFileId: Long?,
    /** True when [localFileId] is a downloaded file rather than a server file. */
    val localIsDownload: Boolean,
)

data class PartySnapshot(
    val configured: Boolean = false,
    val signedIn: Boolean = false,
    val userId: String? = null,
    val username: String? = null,
    val party: ActiveParty? = null,
    val invites: List<PartyInvite> = emptyList(),
    val contacts: List<PartyContact> = emptyList(),
    val error: String? = null,
)

// ---- PostgREST row shapes (must match the desktop's writes) ----------------------

@Serializable
private data class HostServerRow(val url: String = "", val token: String = "")

@Serializable
private data class PartyRow(
    val id: String = "",
    val mode: String = "local",
    val title: String = "",
    val fingerprint: String = "",
    @SerialName("host_file_id") val hostFileId: Long? = null,
    @SerialName("host_server") val hostServer: HostServerRow? = null,
    @SerialName("host_id") val hostId: String = "",
    @SerialName("ended_at") val endedAt: String? = null,
)

@Serializable
private data class MemberRow(
    @SerialName("user_id") val userId: String = "",
    val username: String = "",
    val status: String = "invited",
    @SerialName("is_host") val isHost: Boolean = false,
    @SerialName("has_file") val hasFile: Boolean = false,
    @SerialName("last_seen") val lastSeen: String? = null,
)

@Serializable
private data class StateRow(
    @SerialName("position_seconds") val position: Double = 0.0,
    val paused: Boolean = true,
    val seq: Long = 0,
    @SerialName("updated_by") val updatedBy: String? = null,
    @SerialName("updated_at") val updatedAt: String? = null,
)

@Serializable
private data class InviteRow(
    @SerialName("party_id") val partyId: String = "",
    val parties: PartyRow? = null,
)

@Serializable
private data class ContactRow(
    @SerialName("user_id") val userId: String = "",
    val username: String = "",
    val email: String = "",
    @SerialName("last_used") val lastUsed: String? = null,
)

@Serializable
private data class ProfileRow(val username: String = "")

@Serializable
private data class FoundUser(
    @SerialName("user_id") val userId: String = "",
    val username: String = "",
)

// ---- write bodies ---------------------------------------------------------------

@Serializable
private data class ProfileWrite(
    @SerialName("user_id") val userId: String,
    val username: String,
    val email: String,
    @SerialName("updated_at") val updatedAt: String,
)

@Serializable
private data class PartyWrite(
    @SerialName("host_id") val hostId: String,
    val mode: String,
    val title: String,
    val fingerprint: String,
    @SerialName("host_file_id") val hostFileId: Long?,
    @SerialName("host_server") val hostServer: HostServerRow,
)

@Serializable
private data class MemberWrite(
    @SerialName("party_id") val partyId: String,
    @SerialName("user_id") val userId: String,
    val username: String,
    val status: String,
    @SerialName("is_host") val isHost: Boolean,
    @SerialName("has_file") val hasFile: Boolean = false,
)

@Serializable
private data class MemberPatch(
    val status: String? = null,
    @SerialName("last_seen") val lastSeen: String? = null,
    @SerialName("has_file") val hasFile: Boolean? = null,
)

@Serializable
private data class StateWrite(
    @SerialName("party_id") val partyId: String,
    @SerialName("position_seconds") val position: Double,
    val paused: Boolean,
    val seq: Long,
)

@Serializable
private data class StatePatch(
    @SerialName("position_seconds") val position: Double,
    val paused: Boolean,
    val seq: Long,
    @SerialName("updated_by") val updatedBy: String?,
    @SerialName("updated_at") val updatedAt: String,
)

@Serializable
private data class ContactWrite(
    @SerialName("owner_id") val ownerId: String,
    @SerialName("user_id") val userId: String,
    val username: String,
    val email: String = "",
    @SerialName("last_used") val lastUsed: String,
)

@Serializable
private data class EndPatch(@SerialName("ended_at") val endedAt: String)

@Serializable
private data class FindArgs(val identifier: String)

/** Letters only, 3–20 — same rule the desktop enforces. */
fun validateUsername(raw: String): String {
    val name = raw.trim()
    if (name.length < 3 || name.length > 20) throw IllegalArgumentException("Username must be 3–20 characters")
    if (!Regex("^[A-Za-z]+$").matches(name)) throw IllegalArgumentException("Username can only contain letters (A–Z)")
    return name
}

private fun nowIso(): String = java.time.Instant.now().toString()

class PartyService(
    private val cloud: CloudAuth,
    private val client: ApiClient,
    private val db: LocalityDb,
    private val scope: CoroutineScope,
) {
    private val _state = MutableStateFlow(PartySnapshot())
    val state: StateFlow<PartySnapshot> = _state

    private var username: String? = null
    private var party: ActiveParty? = null
    private var invites: List<PartyInvite> = emptyList()
    private var contacts: List<PartyContact> = emptyList()
    private var error: String? = null

    private var tick = 0
    private var lastHeartbeat = 0L
    /** Bumped locally on every publish so a poll can't hand back an older value. */
    private var pendingSeq = 0L

    /**
     * Whether any activity is on screen. The idle invite poll is pure battery
     * drain in the background; a running party keeps polling because the player
     * is what's on screen.
     */
    @Volatile
    var appActive: Boolean = true

    fun start() {
        scope.launch {
            cloud.load()
            while (true) {
                val delayMs = if (party != null) STATE_POLL_MS else INVITE_POLL_MS
                delay(delayMs)
                try {
                    poll()
                } catch (e: Exception) {
                    // A transient network blip must not kill the loop.
                    error = e.message
                    emit()
                }
            }
        }
    }

    private suspend fun poll() {
        val uid = cloud.userId()
        if (uid == null) {
            if (party != null || invites.isNotEmpty()) {
                party = null
                invites = emptyList()
                emit()
            }
            return
        }
        if (party == null && !appActive) return // nothing to keep in step
        tick++
        if (party != null) {
            pullState()
            if (tick % MEMBER_POLL_EVERY == 0) pullMembers(uid)
            if (System.currentTimeMillis() - lastHeartbeat > HEARTBEAT_MS) heartbeat(uid)
        } else {
            pullInvites(uid)
        }
        error = null
        emit()
    }

    // ---- identity ------------------------------------------------------------------

    suspend fun loadProfile(): String? {
        val uid = cloud.userId() ?: return null
        username?.let { return it }
        return try {
            val rows = AppJson.decodeFromString(
                ListSerializer(ProfileRow.serializer()),
                cloud.rest("profiles?user_id=eq.$uid&select=username"),
            )
            username = rows.firstOrNull()?.username?.takeIf { it.isNotEmpty() }
            emit()
            username
        } catch (_: Exception) {
            null
        }
    }

    /** Claim a username. The unique index on the table makes the race safe. */
    suspend fun setUsername(raw: String): Result<String> {
        val uid = cloud.userId() ?: return Result.failure(Exception("Sign in to your Locality account first"))
        return try {
            val name = validateUsername(raw)
            cloud.rest(
                "profiles",
                "POST",
                AppJson.encodeToString(
                    ProfileWrite.serializer(),
                    ProfileWrite(uid, name, cloud.current?.email ?: cloud.savedEmail().orEmpty(), nowIso()),
                ),
                prefer = "resolution=merge-duplicates,return=representation",
            )
            username = name
            emit()
            Result.success(name)
        } catch (e: Exception) {
            val msg = e.message.orEmpty()
            if (msg.contains("23505") || msg.contains("duplicate", ignoreCase = true)) {
                Result.failure(Exception("\"${raw.trim()}\" is taken — pick another"))
            } else {
                Result.failure(e)
            }
        }
    }

    /** Exact-match lookup by email or username (locality_find_user in the schema). */
    suspend fun findUser(identifier: String): FoundUserResult? {
        val id = identifier.trim()
        if (id.isEmpty()) return null
        val rows = AppJson.decodeFromString(
            ListSerializer(FoundUser.serializer()),
            cloud.rpc("locality_find_user", AppJson.encodeToString(FindArgs.serializer(), FindArgs(id))),
        )
        val hit = rows.firstOrNull() ?: return null
        return FoundUserResult(hit.userId, hit.username)
    }

    data class FoundUserResult(val userId: String, val username: String)

    // ---- invites --------------------------------------------------------------------

    suspend fun invite(identifier: String): Result<Unit> {
        val uid = cloud.userId() ?: return Result.failure(Exception("Sign in first"))
        val p = party ?: return Result.failure(Exception("You're not in a party"))
        if (!p.isHost) return Result.failure(Exception("Only the host can invite people"))
        if (p.members.count { it.status != "declined" && it.status != "left" } >= PARTY_MAX) {
            return Result.failure(Exception("A party can hold $PARTY_MAX people"))
        }
        return try {
            val found = findUser(identifier)
                ?: return Result.failure(Exception("No Locality account for \"${identifier.trim()}\""))
            if (found.userId == uid) return Result.failure(Exception("That's you"))
            if (p.members.any { it.userId == found.userId && it.status == "joined" }) {
                return Result.failure(Exception("${found.username} is already here"))
            }
            cloud.rest(
                "party_members",
                "POST",
                AppJson.encodeToString(
                    MemberWrite.serializer(),
                    MemberWrite(p.id, found.userId, found.username, "invited", false),
                ),
                prefer = "resolution=merge-duplicates",
            )
            cloud.rest(
                "party_contacts",
                "POST",
                AppJson.encodeToString(
                    ContactWrite.serializer(),
                    ContactWrite(
                        uid,
                        found.userId,
                        found.username,
                        if (identifier.contains("@")) identifier.trim() else "",
                        nowIso(),
                    ),
                ),
                prefer = "resolution=merge-duplicates",
            )
            pullMembers(uid)
            emit()
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    private suspend fun pullInvites(uid: String) {
        val rows = AppJson.decodeFromString(
            ListSerializer(InviteRow.serializer()),
            cloud.rest(
                "party_members?user_id=eq.$uid&status=eq.invited" +
                    "&select=party_id,parties(id,title,mode,fingerprint,host_id,ended_at)"
            ),
        )
        val open = rows.filter { it.parties != null && it.parties.endedAt == null }
        invites = open.map { row ->
            val p = row.parties!!
            PartyInvite(
                partyId = row.partyId.ifEmpty { p.id },
                title = p.title,
                mode = PartyMode.of(p.mode),
                hostUsername = hostNameOf(row.partyId.ifEmpty { p.id }),
                hasFile = localFileFor(p.fingerprint) != null,
            )
        }
    }

    /** The host's name isn't on the party row; read it off their member row. */
    private suspend fun hostNameOf(partyId: String): String = try {
        AppJson.decodeFromString(
            ListSerializer(MemberRow.serializer()),
            cloud.rest("party_members?party_id=eq.$partyId&is_host=is.true&select=username"),
        ).firstOrNull()?.username.orEmpty()
    } catch (_: Exception) {
        ""
    }

    suspend fun accept(partyId: String): Result<Unit> {
        val uid = cloud.userId() ?: return Result.failure(Exception("Sign in first"))
        return try {
            if (party != null && party?.id != partyId) leave()
            cloud.rest(
                "party_members?party_id=eq.$partyId&user_id=eq.$uid",
                "PATCH",
                AppJson.encodeToString(MemberPatch.serializer(), MemberPatch(status = "joined", lastSeen = nowIso())),
            )
            adopt(partyId, uid)
            party?.members?.firstOrNull { it.isHost }?.let { host ->
                cloud.rest(
                    "party_contacts",
                    "POST",
                    AppJson.encodeToString(
                        ContactWrite.serializer(),
                        ContactWrite(uid, host.userId, host.username, "", nowIso()),
                    ),
                    prefer = "resolution=merge-duplicates",
                )
            }
            emit()
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun decline(partyId: String): Result<Unit> {
        val uid = cloud.userId() ?: return Result.failure(Exception("Sign in first"))
        return try {
            cloud.rest(
                "party_members?party_id=eq.$partyId&user_id=eq.$uid",
                "PATCH",
                AppJson.encodeToString(MemberPatch.serializer(), MemberPatch(status = "declined")),
            )
            invites = invites.filter { it.partyId != partyId }
            emit()
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    // ---- lifecycle -------------------------------------------------------------------

    /**
     * Start hosting [fileId] (a file on the connected server, or a download).
     * "stream" needs the PC to be reachable from outside, so we publish the
     * remote address the account advertises for it.
     */
    suspend fun host(fileId: Long, fingerprint: String, title: String, mode: PartyMode): Result<Unit> {
        val uid = cloud.userId() ?: return Result.failure(Exception("Sign in to your Locality account first"))
        val name = loadProfile() ?: return Result.failure(Exception("Pick a username first"))
        if (fingerprint.isEmpty()) return Result.failure(Exception("That file has no fingerprint yet"))
        return try {
            var hostServer = HostServerRow()
            if (mode == PartyMode.Stream) {
                hostServer = streamAddress()
                    ?: return Result.failure(
                        Exception(
                            "Streaming to guests needs remote access on the PC you're connected to. " +
                                "Turn it on there (Settings → Network), then start the party."
                        )
                    )
            }
            if (party != null) leave()

            val created = AppJson.decodeFromString(
                ListSerializer(PartyRow.serializer()),
                cloud.rest(
                    "parties",
                    "POST",
                    AppJson.encodeToString(
                        PartyWrite.serializer(),
                        PartyWrite(uid, mode.wire, title, fingerprint, fileId, hostServer),
                    ),
                    prefer = "return=representation",
                ),
            )
            val partyId = created.firstOrNull()?.id.orEmpty()
            if (partyId.isEmpty()) return Result.failure(Exception("Could not create the party"))

            cloud.rest(
                "party_members",
                "POST",
                AppJson.encodeToString(
                    MemberWrite.serializer(),
                    MemberWrite(partyId, uid, name, "joined", true, hasFile = true),
                ),
                prefer = "resolution=merge-duplicates",
            )
            cloud.rest(
                "party_state",
                "POST",
                AppJson.encodeToString(StateWrite.serializer(), StateWrite(partyId, 0.0, true, 0)),
                prefer = "resolution=merge-duplicates",
            )
            adopt(partyId, uid)
            emit()
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * A guest-reachable address for the server this phone is connected to.
     * Prefers the account's published `remote` entry (the PC's tunnel); accepts
     * an https base URL we're already using, since that is a tunnel too.
     */
    private suspend fun streamAddress(): HostServerRow? {
        val cfg = client.config ?: return null
        val servers = cloud.servers().getOrNull().orEmpty()
        val match = servers.firstOrNull { it.token == cfg.token } ?: servers.firstOrNull()
        val remote = match?.addresses?.firstOrNull { it.kind == "remote" }?.host
        if (remote != null) return HostServerRow(remote.trimEnd('/'), match.token)
        val base = cfg.baseUrl.trimEnd('/')
        return if (base.startsWith("https://")) HostServerRow(base, cfg.token) else null
    }

    private suspend fun adopt(partyId: String, uid: String) {
        val rows = AppJson.decodeFromString(
            ListSerializer(PartyRow.serializer()),
            cloud.rest(
                "parties?id=eq.$partyId&select=id,mode,title,fingerprint,host_file_id,host_server,host_id,ended_at"
            ),
        )
        val p = rows.firstOrNull() ?: throw Exception("That party no longer exists")
        if (p.endedAt != null) throw Exception("That party has ended")
        val isHost = p.hostId == uid
        val hs = p.hostServer
        val local = localFileFor(p.fingerprint)
        party = ActiveParty(
            id = p.id,
            mode = PartyMode.of(p.mode),
            title = p.title,
            fingerprint = p.fingerprint,
            hostFileId = p.hostFileId,
            isHost = isHost,
            members = emptyList(),
            playback = PartyPlayback(),
            hostServer = if (!isHost && !hs?.url.isNullOrEmpty()) HostServer(hs!!.url, hs.token) else null,
            localFileId = local?.fileId,
            localIsDownload = local?.isDownload == true,
        )
        pendingSeq = 0
        invites = invites.filter { it.partyId != partyId }
        pullMembers(uid)
        pullState()
        heartbeat(uid)
    }

    suspend fun leave(): Result<Unit> {
        val p = party
        val uid = cloud.userId()
        party = null
        emit()
        if (p == null || uid == null) return Result.success(Unit)
        return try {
            if (p.isHost) {
                cloud.rest(
                    "parties?id=eq.${p.id}",
                    "PATCH",
                    AppJson.encodeToString(EndPatch.serializer(), EndPatch(nowIso())),
                )
            }
            cloud.rest(
                "party_members?party_id=eq.${p.id}&user_id=eq.$uid",
                "PATCH",
                AppJson.encodeToString(MemberPatch.serializer(), MemberPatch(status = "left")),
            )
            Result.success(Unit)
        } catch (_: Exception) {
            // Best effort — the row also ages out via last_seen.
            Result.success(Unit)
        }
    }

    // ---- playback state ---------------------------------------------------------------

    /**
     * Publish a local play/pause/seek. Anyone may control playback, so `seq`
     * gives the writes a total order and the highest wins.
     */
    suspend fun publish(position: Double, paused: Boolean) {
        val p = party ?: return
        val uid = cloud.userId() ?: return
        val seq = maxOf(p.playback.seq, pendingSeq) + 1
        pendingSeq = seq
        party = p.copy(
            playback = PartyPlayback(position, paused, seq, uid, nowIso(), System.currentTimeMillis())
        )
        emit()
        try {
            cloud.rest(
                "party_state?party_id=eq.${p.id}",
                "PATCH",
                AppJson.encodeToString(StatePatch.serializer(), StatePatch(position, paused, seq, uid, nowIso())),
            )
        } catch (_: Exception) {
            /* the next action re-publishes; a lost pause isn't worth a retry storm */
        }
    }

    private suspend fun pullState() {
        val p = party ?: return
        val rows = AppJson.decodeFromString(
            ListSerializer(StateRow.serializer()),
            cloud.rest("party_state?party_id=eq.${p.id}&select=position_seconds,paused,seq,updated_by,updated_at"),
        )
        val s = rows.firstOrNull() ?: return
        // Our own newer write hasn't landed in this read yet — keep ours.
        if (s.seq < p.playback.seq) return
        // Keep the original receipt time for a state we've already seen, so the
        // player's anchor doesn't creep forward on every poll of the same action.
        val receivedAt = if (s.seq == p.playback.seq && p.playback.receivedAt > 0) p.playback.receivedAt
        else System.currentTimeMillis()
        party = p.copy(
            playback = PartyPlayback(s.position, s.paused, s.seq, s.updatedBy, s.updatedAt, receivedAt)
        )
    }

    private suspend fun pullMembers(uid: String) {
        val p = party ?: return
        val rows = AppJson.decodeFromString(
            ListSerializer(MemberRow.serializer()),
            cloud.rest(
                "party_members?party_id=eq.${p.id}" +
                    "&select=user_id,username,status,is_host,has_file,last_seen&order=is_host.desc"
            ),
        )
        val members = rows.map {
            PartyMember(it.userId, it.username, it.status, it.isHost, it.hasFile, it.lastSeen)
        }
        party = p.copy(members = members)
        // The host closing the party (or us being removed) ends it here too.
        val me = members.firstOrNull { it.userId == uid }
        if (me != null && (me.status == "left" || me.status == "declined")) party = null
    }

    private suspend fun heartbeat(uid: String) {
        val p = party ?: return
        lastHeartbeat = System.currentTimeMillis()
        try {
            cloud.rest(
                "party_members?party_id=eq.${p.id}&user_id=eq.$uid",
                "PATCH",
                AppJson.encodeToString(
                    MemberPatch.serializer(),
                    MemberPatch(lastSeen = nowIso(), hasFile = p.localFileId != null || p.isHost),
                ),
            )
        } catch (_: Exception) {
            /* next tick */
        }
    }

    suspend fun loadContacts(): List<PartyContact> {
        val uid = cloud.userId() ?: return emptyList()
        return try {
            contacts = AppJson.decodeFromString(
                ListSerializer(ContactRow.serializer()),
                cloud.rest(
                    "party_contacts?owner_id=eq.$uid" +
                        "&select=user_id,username,email,last_used&order=last_used.desc&limit=12"
                ),
            ).map { PartyContact(it.userId, it.username, it.email, it.lastUsed) }
            emit()
            contacts
        } catch (_: Exception) {
            contacts
        }
    }

    // ---- helpers -----------------------------------------------------------------------

    data class LocalMatch(val fileId: Long, val isDownload: Boolean)

    /**
     * This phone's copy of a fingerprint: a completed download first (plays with
     * no server at all), else the connected server's file — which is what the
     * desktop looks up in its own database.
     */
    suspend fun localFileFor(fingerprint: String): LocalMatch? {
        if (fingerprint.isEmpty()) return null
        db.downloads().fileByFingerprint(fingerprint)?.let {
            if (it.status == DownloadFileRow.STATUS_DONE) return LocalMatch(it.fileId, true)
        }
        val api = client.api() ?: return null
        return try {
            val hit = api.fileByFingerprint(URLEncoder.encode(fingerprint, "UTF-8"))
            LocalMatch(hit.fileId, false)
        } catch (_: Exception) {
            null // 404 = not in this library, or the server is unreachable
        }
    }

    private fun emit() {
        _state.value = PartySnapshot(
            configured = cloud.configured,
            signedIn = cloud.current != null,
            userId = cloud.current?.userId,
            username = username,
            party = party,
            invites = invites,
            contacts = contacts,
            error = error,
        )
    }

    /** Force an immediate refresh (pull-to-refresh, or after signing in). */
    fun refreshNow() {
        scope.launch {
            cloud.load()
            try {
                loadProfile()
                loadContacts()
                poll()
            } catch (e: Exception) {
                error = e.message
                emit()
            }
        }
    }
}
