package com.locality.mobile.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Groups
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.locality.mobile.party.PartyMode
import com.locality.mobile.party.PartyService
import com.locality.mobile.ui.theme.Palette
import kotlinx.coroutines.launch

/**
 * Watch together — the phone's view of the same parties the desktop hosts.
 *
 * Everything here is account-scoped rather than server-scoped: you can accept an
 * invite and sync playback with a friend whose library you've never connected to,
 * as long as one of you has the file (or the host streams it).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PartyScreen(
    party: PartyService,
    onSignIn: () -> Unit,
    onPlayParty: () -> Unit,
) {
    val snap by party.state.collectAsState()
    val scope = rememberCoroutineScope()
    var invitee by remember { mutableStateOf("") }
    var nameDraft by remember { mutableStateOf("") }
    var message by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) { party.refreshNow() }

    val fieldColors = OutlinedTextFieldDefaults.colors(
        focusedBorderColor = Palette.Accent,
        unfocusedBorderColor = Palette.InBorder,
        cursorColor = Palette.Accent,
        focusedContainerColor = Palette.Input,
        unfocusedContainerColor = Palette.Input,
    )

    Scaffold(
        containerColor = Palette.Bg,
        topBar = {
            TopAppBar(
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = Palette.Bg,
                    titleContentColor = Palette.Heading,
                ),
                title = { Text("Watch together") },
            )
        },
    ) { padding ->
        LazyColumn(
            Modifier.fillMaxSize().padding(padding),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (!snap.signedIn) {
                item {
                    Card {
                        Text("Sign in to watch together", style = MaterialTheme.typography.titleMedium, color = Palette.Heading)
                        Spacer(Modifier.height(6.dp))
                        Text(
                            "A party syncs play, pause and seeking between you and up to 5 others — on phones, " +
                                "Windows and Linux alike. It needs a Locality account so people can invite each other.",
                            style = MaterialTheme.typography.bodySmall,
                            color = Palette.Muted,
                        )
                        Spacer(Modifier.height(10.dp))
                        Button(
                            onClick = onSignIn,
                            colors = ButtonDefaults.buttonColors(containerColor = Palette.Accent, contentColor = Palette.AccentText),
                        ) { Text("Sign in or create an account") }
                    }
                }
                return@LazyColumn
            }

            // ---- username claim ----
            if (snap.username.isNullOrEmpty()) {
                item {
                    Card {
                        Text("Pick a username", style = MaterialTheme.typography.titleMedium, color = Palette.Heading)
                        Spacer(Modifier.height(4.dp))
                        Text(
                            "This is how friends see you in a party. Letters only, 3–20 characters.",
                            style = MaterialTheme.typography.bodySmall,
                            color = Palette.Muted,
                        )
                        Spacer(Modifier.height(8.dp))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            OutlinedTextField(
                                value = nameDraft,
                                onValueChange = { nameDraft = it },
                                singleLine = true,
                                colors = fieldColors,
                                modifier = Modifier.weight(1f),
                            )
                            Spacer(Modifier.width(8.dp))
                            Button(
                                enabled = !busy && nameDraft.isNotBlank(),
                                onClick = {
                                    busy = true
                                    scope.launch {
                                        val r = party.setUsername(nameDraft)
                                        message = r.exceptionOrNull()?.message
                                        busy = false
                                    }
                                },
                                colors = ButtonDefaults.buttonColors(containerColor = Palette.Accent, contentColor = Palette.AccentText),
                            ) { Text("Claim") }
                        }
                    }
                }
            }

            // ---- invitations waiting ----
            if (snap.invites.isNotEmpty()) {
                item { SectionLabel("Invitations") }
                items(snap.invites.size) { i ->
                    val inv = snap.invites[i]
                    Card {
                        Text(inv.title.ifEmpty { "A film" }, style = MaterialTheme.typography.titleMedium, color = Palette.Heading)
                        Text(
                            buildString {
                                append("from ${inv.hostUsername.ifEmpty { "a friend" }}")
                                append(if (inv.mode == PartyMode.Stream) " · they're streaming it" else " · your own copy")
                                if (inv.mode == PartyMode.Local && !inv.hasFile) append(" · you don't have this one")
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = if (inv.mode == PartyMode.Local && !inv.hasFile) Palette.Warn else Palette.Muted,
                        )
                        Spacer(Modifier.height(10.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(
                                enabled = !busy,
                                onClick = {
                                    busy = true
                                    scope.launch {
                                        val r = party.accept(inv.partyId)
                                        message = r.exceptionOrNull()?.message
                                        busy = false
                                    }
                                },
                                colors = ButtonDefaults.buttonColors(containerColor = Palette.Accent, contentColor = Palette.AccentText),
                            ) { Text("Join") }
                            OutlinedButton(
                                enabled = !busy,
                                onClick = {
                                    busy = true
                                    scope.launch {
                                        party.decline(inv.partyId)
                                        busy = false
                                    }
                                },
                            ) { Text("Decline", color = Palette.Muted) }
                        }
                    }
                }
            }

            // ---- the party we're in ----
            val active = snap.party
            if (active != null) {
                item { SectionLabel("In progress") }
                item {
                    Card {
                        Text(active.title.ifEmpty { "Watching together" }, style = MaterialTheme.typography.titleMedium, color = Palette.Heading)
                        Text(
                            if (active.mode == PartyMode.Stream) {
                                if (active.isHost) "You're streaming this to everyone" else "Streaming from the host"
                            } else {
                                if (active.localFileId != null) {
                                    if (active.localIsDownload) "Playing your download" else "Playing your server's copy"
                                } else {
                                    "You don't have this file"
                                }
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = if (active.mode == PartyMode.Local && active.localFileId == null) Palette.Warn else Palette.Muted,
                        )
                        Spacer(Modifier.height(10.dp))
                        active.members.filter { it.status == "joined" || it.status == "invited" }.forEach { m ->
                            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 2.dp)) {
                                Icon(
                                    Icons.Filled.Groups,
                                    null,
                                    tint = if (m.status == "joined") Palette.Ok else Palette.Track,
                                    modifier = Modifier.width(18.dp),
                                )
                                Spacer(Modifier.width(8.dp))
                                Text(
                                    m.username.ifEmpty { "someone" } + if (m.isHost) " · host" else "",
                                    color = Palette.Body,
                                    style = MaterialTheme.typography.bodyMedium,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.weight(1f),
                                )
                                Text(
                                    when {
                                        m.status == "invited" -> "invited"
                                        m.hasFile -> "ready"
                                        else -> "no file"
                                    },
                                    color = Palette.Muted,
                                    style = MaterialTheme.typography.labelSmall,
                                )
                            }
                        }
                        Spacer(Modifier.height(10.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            val canPlay = active.localFileId != null || active.hostServer != null
                            Button(
                                enabled = canPlay,
                                onClick = onPlayParty,
                                colors = ButtonDefaults.buttonColors(containerColor = Palette.Accent, contentColor = Palette.AccentText),
                            ) {
                                Icon(Icons.Filled.PlayArrow, null)
                                Spacer(Modifier.width(6.dp))
                                Text("Open player")
                            }
                            OutlinedButton(onClick = { scope.launch { party.leave() } }) {
                                Text(if (active.isHost) "End party" else "Leave", color = Palette.Danger)
                            }
                        }
                    }
                }

                if (active.isHost) {
                    item {
                        Card {
                            Text("Invite someone", style = MaterialTheme.typography.titleMedium, color = Palette.Heading)
                            Spacer(Modifier.height(4.dp))
                            Text(
                                "Their Locality account email, or their username.",
                                style = MaterialTheme.typography.bodySmall,
                                color = Palette.Muted,
                            )
                            Spacer(Modifier.height(8.dp))
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                OutlinedTextField(
                                    value = invitee,
                                    onValueChange = { invitee = it },
                                    singleLine = true,
                                    colors = fieldColors,
                                    modifier = Modifier.weight(1f),
                                )
                                Spacer(Modifier.width(8.dp))
                                Button(
                                    enabled = !busy && invitee.isNotBlank(),
                                    onClick = {
                                        busy = true
                                        scope.launch {
                                            val r = party.invite(invitee)
                                            message = r.exceptionOrNull()?.message ?: "Invited"
                                            if (r.isSuccess) invitee = ""
                                            busy = false
                                        }
                                    },
                                    colors = ButtonDefaults.buttonColors(containerColor = Palette.Accent, contentColor = Palette.AccentText),
                                ) { Text("Invite") }
                            }
                            if (snap.contacts.isNotEmpty()) {
                                Spacer(Modifier.height(8.dp))
                                Text("Recent", style = MaterialTheme.typography.labelSmall, color = Palette.Muted)
                                snap.contacts.take(6).forEach { c ->
                                    Text(
                                        c.username.ifEmpty { c.email },
                                        color = Palette.Hint,
                                        style = MaterialTheme.typography.bodyMedium,
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .clickable { invitee = c.email.ifEmpty { c.username } }
                                            .padding(vertical = 6.dp),
                                    )
                                }
                            }
                        }
                    }
                }
            } else if (snap.invites.isEmpty()) {
                item {
                    Card {
                        Text("No party yet", style = MaterialTheme.typography.titleMedium, color = Palette.Heading)
                        Spacer(Modifier.height(6.dp))
                        Text(
                            "Start one from the player: open something, tap the people icon, and pick whether " +
                                "everyone plays their own copy or you stream yours. Invitations from friends show up here.",
                            style = MaterialTheme.typography.bodySmall,
                            color = Palette.Muted,
                        )
                    }
                }
            }

            (message ?: snap.error)?.let {
                item {
                    Text(it, color = if (it == "Invited") Palette.Ok else Palette.Warn, style = MaterialTheme.typography.bodySmall)
                }
            }
        }
    }
}

@Composable
private fun SectionLabel(text: String) {
    Text(text, style = MaterialTheme.typography.labelMedium, color = Palette.Muted)
}

@Composable
private fun Card(content: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit) {
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(Palette.Raised)
            .border(1.dp, Palette.CardBorder, RoundedCornerShape(10.dp))
            .padding(14.dp),
        content = content,
    )
}
