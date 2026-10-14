package com.locality.mobile.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
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
import com.locality.mobile.data.SavedServer
import com.locality.mobile.data.ServerConfigStore
import com.locality.mobile.party.PartyService
import com.locality.mobile.ui.theme.Palette
import kotlinx.coroutines.launch

/**
 * First launch: the ACCOUNT comes first, a server is optional.
 *
 * Signing in is worth something on its own — it's how you get invited to watch
 * parties and how your progress follows you — so this never blocks on finding a
 * PC. We do look for one in the background once you're in, because most people
 * do have one, but an account with no server lands in the app just the same.
 */
@Composable
fun WelcomeScreen(
    cloud: CloudAuth,
    store: ServerConfigStore,
    party: PartyService,
    onDone: () -> Unit,
    onEnterAddress: () -> Unit,
) {
    var email by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var registering by remember { mutableStateOf(false) }
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

    /** Best-effort: connect to this account's PC if one answers. Never blocks entry. */
    suspend fun attachServerIfAny() {
        status = "Looking for your PC…"
        val servers = cloud.servers().getOrNull().orEmpty()
        for (s in servers) {
            val base = cloud.reachableHost(s) ?: continue
            store.saveServer(
                SavedServer(
                    id = s.serverId.ifEmpty { "srv_" + java.util.UUID.randomUUID().toString().take(8) },
                    name = s.name.ifEmpty { "My server" },
                    host = base,
                    port = s.port,
                    token = s.token,
                    owned = true,
                )
            )
            return
        }
    }

    fun submit() {
        error = null
        busy = true
        scope.launch {
            val r = if (registering) cloud.signUp(email.trim(), password) else cloud.signIn(email.trim(), password)
            r.fold(
                onSuccess = {
                    party.refreshNow()
                    runCatching { attachServerIfAny() }
                    status = null
                    busy = false
                    onDone()
                },
                onFailure = {
                    error = it.message
                    status = null
                    busy = false
                },
            )
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
            if (registering) "Create an account to watch together\nand carry your progress between devices."
            else "Sign in to find your PC, join watch parties\nand keep your progress in step.",
            style = MaterialTheme.typography.bodyMedium,
            color = Palette.Muted,
        )
        Spacer(Modifier.height(28.dp))

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
            visualTransformation = PasswordVisualTransformation(),
            colors = fieldColors,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
            modifier = Modifier.fillMaxWidth(),
        )
        if (registering) {
            Spacer(Modifier.height(4.dp))
            Text(
                "At least 6 characters.",
                style = MaterialTheme.typography.labelSmall,
                color = Palette.Muted,
                modifier = Modifier.fillMaxWidth(),
            )
        }
        Spacer(Modifier.height(18.dp))
        Button(
            onClick = { submit() },
            enabled = !busy && email.isNotBlank() && password.length >= 6,
            colors = ButtonDefaults.buttonColors(containerColor = Palette.Accent, contentColor = Palette.AccentText),
            modifier = Modifier.fillMaxWidth(),
        ) {
            if (busy) CircularProgressIndicator(Modifier.height(18.dp).width(18.dp), color = Palette.AccentText, strokeWidth = 2.dp)
            else Text(if (registering) "Create account" else "Sign in")
        }

        status?.let {
            Spacer(Modifier.height(10.dp))
            Text(it, color = Palette.Muted, style = MaterialTheme.typography.bodySmall)
        }
        error?.let {
            Spacer(Modifier.height(10.dp))
            Text(it, color = Palette.Danger, style = MaterialTheme.typography.bodySmall)
        }

        Spacer(Modifier.height(6.dp))
        TextButton(onClick = { registering = !registering; error = null }) {
            Text(
                if (registering) "I already have an account" else "Create a new account",
                color = Palette.Hint,
            )
        }
        TextButton(onClick = onEnterAddress) {
            Text("Connect a server by address instead", color = Palette.Hint)
        }
        TextButton(onClick = onDone) {
            Text("Skip for now", color = Palette.Muted)
        }
    }
}
