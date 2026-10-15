package com.maxrave.simpmusic.ui.screen.player

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.maxrave.simpmusic.spotify.SpotifyLibrarySnapshot
import com.maxrave.simpmusic.spotify.SpotifyLibrarySyncService
import kotlinx.coroutines.launch

@Composable
@OptIn(ExperimentalMaterial3Api::class)
fun SpotifyAddToPlaylistSheet(
    songTitle: String,
    artists: String,
    onDismiss: () -> Unit,
    service: SpotifyLibrarySyncService,
) {
    val coroutineScope = rememberCoroutineScope()
    var library by remember { mutableStateOf(SpotifyLibrarySnapshot(emptyList(), emptyList())) }
    var addingPlaylistId by remember { mutableStateOf<String?>(null) }
    var likingTrack by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    var messageIsError by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        service.cachedLibraryJson()?.let { library = service.parseLibrarySnapshot(it) }
        if (service.isLoggedIn()) {
            service.syncLibrary(dataSaverEnabled = service.isDataSaverEnabled())
                .onSuccess { library = service.parseLibrarySnapshot(it) }
                .onFailure {
                    messageIsError = true
                    message = it.message
                }
        } else {
            messageIsError = true
            message = "Log in to Spotify in Settings first."
        }
    }

    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 18.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text("Add to Spotify playlist", style = MaterialTheme.typography.titleLarge)
            Text(
                text = if (artists.isBlank()) songTitle else "$songTitle · $artists",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("Liked Songs", style = MaterialTheme.typography.bodyLarge)
                    Text(
                        "Save this track to your Spotify account",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (likingTrack) {
                    CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                } else {
                    TextButton(
                        onClick = {
                            coroutineScope.launch {
                                likingTrack = true
                                message = null
                                messageIsError = false
                                service.setLikedByMetadata(songTitle, artists, true)
                                    .onSuccess {
                                        messageIsError = false
                                        message = "Added to Spotify Liked Songs."
                                        service.cachedLibraryJson()?.let { library = service.parseLibrarySnapshot(it) }
                                    }
                                    .onFailure {
                                        messageIsError = true
                                        message = it.message ?: "Could not add this track to Spotify Liked Songs."
                                    }
                                likingTrack = false
                            }
                        },
                        enabled = !likingTrack && addingPlaylistId == null,
                    ) { Text("Like") }
                }
            }
            message?.let {
                Text(
                    it,
                    color = if (messageIsError) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            LazyColumn(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                items(library.playlists, key = { it.id }) { playlist ->
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        AsyncImage(
                            model = playlist.imageUrl,
                            contentDescription = playlist.name,
                            contentScale = ContentScale.Crop,
                            modifier = Modifier.size(48.dp).clip(RoundedCornerShape(8.dp)),
                        )
                        Column(modifier = Modifier.weight(1f).padding(horizontal = 10.dp)) {
                            Text(playlist.name, style = MaterialTheme.typography.bodyLarge)
                            Text(
                                playlist.trackCount.toString() + " tracks",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        if (addingPlaylistId == playlist.id) {
                            CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                        } else {
                            TextButton(onClick = {
                                coroutineScope.launch {
                                    addingPlaylistId = playlist.id
                                    message = null
                                    messageIsError = false
                                    service.addTrackToPlaylistByMetadata(playlist.id, songTitle, artists)
                                        .onSuccess {
                                            messageIsError = false
                                            message = "Added to " + playlist.name + " on Spotify."
                                        }
                                        .onFailure {
                                            messageIsError = true
                                            message = it.message ?: "Could not add this track to Spotify."
                                        }
                                    addingPlaylistId = null
                                }
                            }, enabled = addingPlaylistId == null) { Text("Add") }
                        }
                    }
                }
            }
            Spacer(Modifier.size(12.dp))
        }
    }
}
