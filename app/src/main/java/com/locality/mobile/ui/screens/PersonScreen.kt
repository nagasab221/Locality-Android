package com.locality.mobile.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.locality.mobile.data.Repo
import com.locality.mobile.data.api.PersonPageDto
import com.locality.mobile.ui.components.CenteredLoading
import com.locality.mobile.ui.components.CenteredMessage
import com.locality.mobile.ui.components.MediaItemRow
import com.locality.mobile.ui.components.SectionRow
import com.locality.mobile.ui.theme.Palette

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PersonScreen(repo: Repo, tmdbId: Long, onBack: () -> Unit, onOpenItem: (Long) -> Unit) {
    var page by remember { mutableStateOf<PersonPageDto?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(tmdbId) {
        repo.person(tmdbId).fold(onSuccess = { page = it }, onFailure = { error = it.message })
    }

    Scaffold(
        containerColor = Palette.Bg,
        topBar = {
            TopAppBar(
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Palette.Bg, titleContentColor = Palette.Heading),
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back", tint = Palette.Muted) } },
                title = { Text(page?.person?.name ?: "") },
            )
        },
    ) { padding ->
        val p = page
        when {
            p == null && error != null -> CenteredMessage(error ?: "")
            p == null -> CenteredLoading()
            else -> LazyColumn(Modifier.fillMaxSize(), contentPadding = padding) {
                item {
                    Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            Modifier
                                .size(96.dp)
                                .clip(CircleShape)
                                .background(Palette.Raised),
                            contentAlignment = Alignment.Center,
                        ) {
                            if (p.person.photo != null) {
                                AsyncImage(
                                    model = repo.imageUrl(p.person.photo),
                                    contentDescription = null,
                                    contentScale = ContentScale.Crop,
                                    modifier = Modifier.fillMaxSize(),
                                )
                            } else {
                                Text(
                                    p.person.name.split(" ").mapNotNull { it.firstOrNull() }.take(2).joinToString(""),
                                    style = MaterialTheme.typography.headlineMedium,
                                    color = Palette.Hint,
                                )
                            }
                        }
                        Column(Modifier.padding(start = 16.dp)) {
                            Text(p.person.name, style = MaterialTheme.typography.headlineSmall)
                            val meta = listOfNotNull(p.person.knownFor, p.person.birthday, p.person.placeOfBirth)
                            meta.forEach { Text(it, color = Palette.Muted, style = MaterialTheme.typography.labelMedium) }
                        }
                    }
                }
                if (!p.person.bio.isNullOrBlank()) {
                    item {
                        Text(
                            p.person.bio,
                            color = Palette.Body,
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.padding(horizontal = 16.dp),
                        )
                    }
                }
                if (p.inLibrary.isNotEmpty()) {
                    item {
                        Spacer(Modifier.height(12.dp))
                        SectionRow("In your library") {
                            MediaItemRow(p.inLibrary, repo::imageUrl) { onOpenItem(it.id) }
                        }
                    }
                }
                item { Spacer(Modifier.height(24.dp)) }
            }
        }
    }
}
