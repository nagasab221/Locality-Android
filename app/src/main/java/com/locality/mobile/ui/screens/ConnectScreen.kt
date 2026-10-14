package com.locality.mobile.ui.screens

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
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.locality.mobile.data.CloudAuth
import com.locality.mobile.data.ConnectionState
import com.locality.mobile.data.Repo
import com.locality.mobile.data.ServerConfigStore
import com.locality.mobile.ui.theme.Palette
import kotlinx.coroutines.launch

/**
 * First-launch pairing. Default path: sign in with the same account as the PC
 * and we look up its published address ourselves. Manual IP entry stays one tap
 * away for when there's no cloud account (or it's unreachable).
 */
@Composable
fun ConnectScreen(
    store: ServerConfigStore,
    repo: Repo,
    initialHost: String = "",
    initialPort: String = "3777",
    initialToken: String = "",
    onConnected: () -> Unit,
    onSkip: (() -> Unit)? = null,
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val cloud = remember { CloudAuth(context) }
    // Manual mode is the only option when this build has no Supabase settings.
    var manual by remember { mutableStateOf(!cloud.configured || initialHost.isNotBlank()) }

    var host by remember { mutableStateOf(initialHost) }
    var port by remember { mutableStateOf(initialPort) }
    var token by remember { mutableStateOf(initialToken) }
    var email by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf<String?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()

    LaunchedEffect(Unit) { cloud.savedEmail()?.let { email = it } }

    val fieldColors = OutlinedTextFieldDefaults.colors(
        focusedBorderColor = Palette.Accent,
        unfocusedBorderColor = Palette.InBorder,
        focusedLabelColor = Palette.Accent,
        unfocusedLabelColor = Palette.Muted,
        cursorColor = Palette.Accent,
        focusedContainerColor = Palette.Input,
        unfocusedContainerColor = Palette.Input,
    )

    /** Shared tail of both paths: save the address and verify it answers. */
    suspend fun finish(h: String, p: Int, t: String): Boolean {
        store.save(h, p, t)
        return when (val c = repo.checkConnection()) {
            is ConnectionState.Connected -> { onConnected(); true }
            is ConnectionState.Offline -> { error = c.message; false }
            else -> { error = "Enter the PC's address first"; false }
        }
    }

    fun signInAndFind() {
        error = null
        busy = true
        status = "Signing in…"
        scope.launch {
            cloud.signIn(email.trim(), password).fold(
                onSuccess = {
                    status = "Looking for your PC…"
                    cloud.servers().fold(
                        onSuccess = { servers ->
                            when {
                                servers.isEmpty() -> error =
                                    "Signed in, but no PC was found. On the PC, turn on Remote access in Settings and sign in with the same account."
                                else -> {
                                    var connected = false
                                    for (s in servers) {
                                        status = "Reaching ${s.name}…"
                                        val reachable = cloud.reachableHost(s)
                                        if (reachable != null) {
                                            connected = finish(reachable, s.port, s.token)
                                            if (connected) break
                                        }
                                    }
                                    if (!connected && error == null) {
                                        val names = servers.joinToString(", ") { it.name }
                                        error =
                                            "Found $names, but couldn't reach it. On the same Wi-Fi, check Windows Firewall allows Locality on private networks. Away from home, turn on \"Reach this PC from outside the house\" in the PC's Settings."
                                    }
                                }
                            }
                        },
                        onFailure = { error = it.message },
                    )
                },
                onFailure = { error = it.message },
            )
            status = null
            busy = false
        }
    }

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text("Locality", style = MaterialTheme.typography.displayLarge)
        Spacer(Modifier.height(8.dp))
        Text(
            if (manual) "Connect to your desktop over Wi-Fi.\nTurn on Remote access in the PC's Settings."
            else "Sign in with the same account as your PC\nand we'll find it for you.",
            style = MaterialTheme.typography.bodyMedium,
            color = Palette.Muted,
        )
        Spacer(Modifier.height(28.dp))

        if (manual) {
            Row(Modifier.fillMaxWidth()) {
                OutlinedTextField(
                    value = host,
                    onValueChange = { host = it },
                    label = { Text("PC address") },
                    placeholder = { Text("192.168.1.23") },
                    singleLine = true,
                    colors = fieldColors,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                    modifier = Modifier.weight(2f),
                )
                Spacer(Modifier.width(10.dp))
                OutlinedTextField(
                    value = port,
                    onValueChange = { port = it.filter { c -> c.isDigit() } },
                    label = { Text("Port") },
                    singleLine = true,
                    colors = fieldColors,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.weight(1f),
                )
            }
            Spacer(Modifier.height(10.dp))
            OutlinedTextField(
                value = token,
                onValueChange = { token = it },
                label = { Text("Access token") },
                placeholder = { Text("shown on the PC") },
                singleLine = true,
                colors = fieldColors,
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(20.dp))
            Button(
                onClick = {
                    error = null
                    busy = true
                    scope.launch {
                        finish(host, port.toIntOrNull() ?: 3777, token)
                        busy = false
                    }
                },
                enabled = !busy && host.isNotBlank(),
                colors = ButtonDefaults.buttonColors(containerColor = Palette.Accent, contentColor = Palette.AccentText),
                modifier = Modifier.fillMaxWidth(),
            ) {
                if (busy) CircularProgressIndicator(Modifier.height(18.dp).width(18.dp), color = Palette.AccentText, strokeWidth = 2.dp)
                else Text("Connect")
            }
        } else {
            OutlinedTextField(
                value = email,
                onValueChange = { email = it },
                label = { Text("Email") },
                singleLine = true,
                colors = fieldColors,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email),
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(10.dp))
            OutlinedTextField(
                value = password,
                onValueChange = { password = it },
                label = { Text("Password") },
                singleLine = true,
                colors = fieldColors,
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(20.dp))
            Button(
                onClick = { signInAndFind() },
                enabled = !busy && email.isNotBlank() && password.isNotBlank(),
                colors = ButtonDefaults.buttonColors(containerColor = Palette.Accent, contentColor = Palette.AccentText),
                modifier = Modifier.fillMaxWidth(),
            ) {
                if (busy) CircularProgressIndicator(Modifier.height(18.dp).width(18.dp), color = Palette.AccentText, strokeWidth = 2.dp)
                else Text("Sign in & find my PC")
            }
        }

        status?.let {
            Spacer(Modifier.height(12.dp))
            Text(it, color = Palette.Muted, style = MaterialTheme.typography.bodySmall)
        }
        error?.let {
            Spacer(Modifier.height(12.dp))
            Text(it, color = Palette.Danger, style = MaterialTheme.typography.bodySmall)
        }

        if (cloud.configured) {
            Spacer(Modifier.height(8.dp))
            TextButton(onClick = { manual = !manual; error = null }) {
                Text(if (manual) "Sign in instead" else "Enter IP manually", color = Palette.Hint)
            }
        }
        if (onSkip != null) {
            TextButton(onClick = onSkip) { Text("Browse downloads offline", color = Palette.Hint) }
        }
    }
}
