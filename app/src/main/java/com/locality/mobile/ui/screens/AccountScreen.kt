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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.locality.mobile.data.CloudAuth
import com.locality.mobile.data.CloudProgressSync
import com.locality.mobile.data.CloudServer
import com.locality.mobile.data.SavedServer
import com.locality.mobile.data.ServerConfigStore
import com.locality.mobile.party.PartyService
import com.locality.mobile.ui.theme.Palette
import kotlinx.coroutines.launch

/**
 * The Locality account: create one or sign in, and connect to servers.
 *
 * Signing in is deliberately independent of any server — the session lives on
 * this phone, so the app stays "signed in" with the PC switched off, on mobile
 * data, or on a plane. That's what lets watch parties and progress sync work
 * without first pairing to a library.
 *
 * "Connect to a friend's server" mirrors the desktop's Connect dialog: their
 * email and password are used for ONE lookup of the servers their account
 * publishes, and are never stored — your own session is untouched.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AccountScreen(
    cloud: CloudAuth,
    store: ServerConfigStore,
    party: PartyService,
    progress: CloudProgressSync,
    onBack: () -> Unit,
    onConnected: () -> Unit,
) {
    val session by cloud.session.collectAsState(initial = null)
    val scope = rememberCoroutineScope()

    var email by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var registering by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    var error by remember { mutableStateOf<String?>(null) }

    // Friend lookup
    var friendEmail by remember { mutableStateOf("") }
    var friendPassword by remember { mutableStateOf("") }
    var friendServers by remember { mutableStateOf<List<CloudServer>?>(null) }
    var lookingUp by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) { cloud.savedEmail()?.let { if (email.isBlank()) email = it } }

    val fieldColors = OutlinedTextFieldDefaults.colors(
        focusedBorderColor = Palette.Accent,
        unfocusedBorderColor = Palette.InBorder,
        focusedLabelColor = Palette.Accent,
        unfocusedLabelColor = Palette.Muted,
        cursorColor = Palette.Accent,
        focusedContainerColor = Palette.Input,
        unfocusedContainerColor = Palette.Input,
    )

    fun connectTo(s: CloudServer) {
        busy = true
        error = null
        message = "Reaching ${s.name.ifEmpty { "server" }}…"
        scope.launch {
            val base = cloud.reachableHost(s)
            if (base == null) {
                error = "Found ${s.name.ifEmpty { "that server" }}, but couldn't reach it. " +
                    "It may be switched off, or remote access is off on that PC."
                message = null
            } else {
                // Saved as NOT owned: we can browse and play, but the app won't
                // write our watching into their library (see Repo.canWriteToServer).
                store.saveServer(
                    SavedServer(
                        id = s.serverId.ifEmpty { "srv_" + java.util.UUID.randomUUID().toString().take(8) },
                        name = s.name.ifEmpty { friendEmail.trim().substringBefore('@') + "'s server" },
                        host = base,
                        port = s.port,
                        token = s.token,
                        owned = false,
                    )
                )
                message = "Connected to ${s.name.ifEmpty { base }}"
                onConnected()
            }
            busy = false
        }
    }

    Scaffold(
        containerColor = Palette.Bg,
        topBar = {
            TopAppBar(
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Palette.Bg, titleContentColor = Palette.Heading),
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back", tint = Palette.Muted) }
                },
                title = { Text("Account") },
            )
        },
    ) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(padding)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (!cloud.configured) {
                Text(
                    "This build has no Locality account settings, so sign-in is unavailable. " +
                        "Connect to a server by address instead.",
                    color = Palette.Muted,
                    style = MaterialTheme.typography.bodyMedium,
                )
                return@Column
            }

            // ---- signed in / signed out ----
            Card {
                if (session != null) {
                    Text("Signed in", style = MaterialTheme.typography.labelMedium, color = Palette.Muted)
                    Text(session!!.email, style = MaterialTheme.typography.titleMedium, color = Palette.Heading)
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "Stays signed in with no server and no internet — parties and progress sync pick up " +
                            "again whenever you're back online.",
                        style = MaterialTheme.typography.bodySmall,
                        color = Palette.Muted,
                    )
                    Spacer(Modifier.height(10.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(
                            enabled = !busy,
                            onClick = {
                                busy = true
                                scope.launch {
                                    val r = progress.sync()
                                    message = if (r.isSuccess) "Progress synced" else null
                                    error = r.exceptionOrNull()?.message
                                    busy = false
                                }
                            },
                        ) { Text("Sync progress now", color = Palette.Accent) }
                        OutlinedButton(
                            enabled = !busy,
                            onClick = {
                                scope.launch {
                                    party.leave()
                                    cloud.signOut()
                                    party.refreshNow()
                                    message = "Signed out"
                                }
                            },
                        ) { Text("Sign out", color = Palette.Danger) }
                    }
                } else {
                    Text(
                        if (registering) "Create an account" else "Sign in",
                        style = MaterialTheme.typography.titleMedium,
                        color = Palette.Heading,
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "One account across your phone, Windows and Linux. It finds your PC for you and lets " +
                            "friends invite you to watch together.",
                        style = MaterialTheme.typography.bodySmall,
                        color = Palette.Muted,
                    )
                    Spacer(Modifier.height(10.dp))
                    OutlinedTextField(
                        value = email,
                        onValueChange = { email = it },
                        label = { Text("Email") },
                        singleLine = true,
                        colors = fieldColors,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email),
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = password,
                        onValueChange = { password = it },
                        label = { Text("Password") },
                        singleLine = true,
                        visualTransformation = PasswordVisualTransformation(),
                        colors = fieldColors,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Spacer(Modifier.height(10.dp))
                    Button(
                        enabled = !busy && email.isNotBlank() && password.length >= 6,
                        onClick = {
                            busy = true
                            error = null
                            message = null
                            scope.launch {
                                val r = if (registering) cloud.signUp(email.trim(), password)
                                else cloud.signIn(email.trim(), password)
                                r.fold(
                                    onSuccess = {
                                        password = ""
                                        message = if (registering) "Account created" else "Signed in"
                                        party.refreshNow()
                                        progress.sync()
                                    },
                                    onFailure = { error = it.message },
                                )
                                busy = false
                            }
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = Palette.Accent, contentColor = Palette.AccentText),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        if (busy) CircularProgressIndicator(Modifier.height(18.dp).width(18.dp), color = Palette.AccentText, strokeWidth = 2.dp)
                        else Text(if (registering) "Create account" else "Sign in")
                    }
                    TextButton(onClick = { registering = !registering; error = null }) {
                        Text(
                            if (registering) "I already have an account" else "Create a new account",
                            color = Palette.Hint,
                        )
                    }
                }
            }

            // ---- connect to a friend's server ----
            Card {
                Text("Connect to a friend's server", style = MaterialTheme.typography.titleMedium, color = Palette.Heading)
                Spacer(Modifier.height(4.dp))
                Text(
                    "Enter the account details of the person whose library you want to browse. Used once to find " +
                        "their server's address and access token — nothing is saved, and you stay signed in as you.",
                    style = MaterialTheme.typography.bodySmall,
                    color = Palette.Muted,
                )
                Spacer(Modifier.height(10.dp))
                val list = friendServers
                if (list == null) {
                    OutlinedTextField(
                        value = friendEmail,
                        onValueChange = { friendEmail = it },
                        label = { Text("Their email") },
                        singleLine = true,
                        colors = fieldColors,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email),
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = friendPassword,
                        onValueChange = { friendPassword = it },
                        label = { Text("Their password") },
                        singleLine = true,
                        visualTransformation = PasswordVisualTransformation(),
                        colors = fieldColors,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Spacer(Modifier.height(10.dp))
                    Button(
                        enabled = !lookingUp && friendEmail.isNotBlank() && friendPassword.isNotBlank(),
                        onClick = {
                            lookingUp = true
                            error = null
                            scope.launch {
                                cloud.lookupServers(friendEmail.trim(), friendPassword).fold(
                                    onSuccess = {
                                        friendServers = it
                                        friendPassword = ""
                                        if (it.isEmpty()) {
                                            error = "That account has no servers published. On their PC: turn on " +
                                                "Remote access and sign in with the same account."
                                        }
                                    },
                                    onFailure = { error = it.message },
                                )
                                lookingUp = false
                            }
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = Palette.Accent, contentColor = Palette.AccentText),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        if (lookingUp) CircularProgressIndicator(Modifier.height(18.dp).width(18.dp), color = Palette.AccentText, strokeWidth = 2.dp)
                        else Text("Find their servers")
                    }
                } else {
                    list.forEach { s ->
                        Column(
                            Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(8.dp))
                                .border(1.dp, Palette.CardBorder, RoundedCornerShape(8.dp))
                                .clickable(enabled = !busy) { connectTo(s) }
                                .padding(12.dp)
                        ) {
                            Text(s.name.ifEmpty { "Locality server" }, color = Palette.Heading)
                            Text(
                                if (s.addresses.any { it.kind == "remote" }) "reachable from anywhere"
                                else "on their local network",
                                color = Palette.Muted,
                                style = MaterialTheme.typography.labelSmall,
                            )
                        }
                        Spacer(Modifier.height(8.dp))
                    }
                    TextButton(onClick = { friendServers = null }) {
                        Text("Use a different account", color = Palette.Hint)
                    }
                }
            }

            message?.let { Text(it, color = Palette.Ok, style = MaterialTheme.typography.bodySmall) }
            error?.let { Text(it, color = Palette.Danger, style = MaterialTheme.typography.bodySmall) }
        }
    }
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
