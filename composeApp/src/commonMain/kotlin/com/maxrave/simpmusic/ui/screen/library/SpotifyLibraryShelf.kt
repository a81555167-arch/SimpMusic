package com.maxrave.simpmusic.ui.screen.library

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import kotlinx.coroutines.launch
import coil3.compose.AsyncImage
import com.maxrave.simpmusic.spotify.SpotifyLibraryPlaylist
import com.maxrave.simpmusic.spotify.SpotifyLibrarySnapshot
import com.maxrave.simpmusic.spotify.SpotifyLibrarySyncService
import com.maxrave.simpmusic.spotify.SpotifyLibraryTrack
import com.maxrave.simpmusic.ui.navigation.destination.search.SearchDestination
import com.maxrave.simpmusic.viewModel.SearchViewModel

@Composable
@OptIn(ExperimentalMaterial3Api::class)
fun SpotifyLibraryShelf(
    service: SpotifyLibrarySyncService,
    searchViewModel: SearchViewModel,
    navController: NavController,
    modifier: Modifier = Modifier,
) {
    val coroutineScope = rememberCoroutineScope()
    var spotifyLoggedIn by remember { mutableStateOf(false) }
    var library by remember { mutableStateOf(SpotifyLibrarySnapshot(emptyList(), emptyList())) }
    var selectedPlaylist by remember { mutableStateOf<SpotifyLibraryPlaylist?>(null) }
    var showLikedSongs by remember { mutableStateOf(false) }
    var selectedTracks by remember { mutableStateOf<List<SpotifyLibraryTrack>>(emptyList()) }
    var loading by remember { mutableStateOf(false) }
    var refreshing by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }

    suspend fun loadLibrary(force: Boolean) {
        refreshing = true
        val result = service.syncLibrary(
            dataSaverEnabled = service.isDataSaverEnabled(),
            forceRefresh = force,
        )
        result.onSuccess { snapshot ->
            library = service.parseLibrarySnapshot(snapshot)
            message = null
        }.onFailure { error ->
            message = error.message
            service.cachedLibraryJson()?.let { library = service.parseLibrarySnapshot(it) }
        }
        refreshing = false
    }

    LaunchedEffect(Unit) {
        spotifyLoggedIn = service.isLoggedIn()
        service.cachedLibraryJson()?.let { library = service.parseLibrarySnapshot(it) }
        if (spotifyLoggedIn) loadLibrary(force = false)
    }

    LaunchedEffect(selectedPlaylist?.id) {
        val playlist = selectedPlaylist ?: return@LaunchedEffect
        selectedTracks = service.parsePlaylistTracks(service.cachedPlaylistTracksJson(playlist.id))
        loading = true
        service.syncPlaylistTracks(
            playlistId = playlist.id,
            dataSaverEnabled = service.isDataSaverEnabled(),
        ).onSuccess { selectedTracks = service.parsePlaylistTracks(it) }
            .onFailure { message = it.message }
        loading = false
    }

    if (spotifyLoggedIn || library.playlists.isNotEmpty() || library.likedTracks.isNotEmpty()) {
        Column(
            modifier = modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("Spotify library", style = MaterialTheme.typography.titleLarge)
                    Text(
                        text = "${library.playlists.size} playlists · ${library.likedTracks.size} liked songs",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                TextButton(onClick = { coroutineScope.launch { loadLibrary(force = true) } }, enabled = !refreshing) {
                    if (refreshing) CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                    else Text("Refresh")
                }
            }
            LazyRow(
                contentPadding = PaddingValues(horizontal = 2.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                item(key = "spotify-liked-songs") {
                    SpotifyLibraryCard(
                        title = "Liked Songs",
                        subtitle = "${library.likedTracks.size} tracks",
                        imageUrl = library.likedTracks.firstOrNull()?.imageUrl,
                        onClick = {
                            selectedPlaylist = null
                            showLikedSongs = true
                            selectedTracks = library.likedTracks
                        },
                    )
                }
                items(library.playlists, key = { it.id }) { playlist ->
                    SpotifyLibraryCard(
                        title = playlist.name,
                        subtitle = "${playlist.trackCount} tracks",
                        imageUrl = playlist.imageUrl,
                        onClick = {
                            showLikedSongs = false
                            selectedPlaylist = playlist
                        },
                    )
                }
            }
            message?.let {
                Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
            }
        }
    }

    if (showLikedSongs || selectedPlaylist != null) {
        val title = if (showLikedSongs) "Spotify Liked Songs" else selectedPlaylist?.name.orEmpty()
        ModalBottomSheet(
            onDismissRequest = {
                showLikedSongs = false
                selectedPlaylist = null
            },
        ) {
            Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(title, style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
                    if (selectedPlaylist != null) {
                        TextButton(
                            onClick = {
                                val playlist = selectedPlaylist ?: return@TextButton
                                coroutineScope.launch {
                                    loading = true
                                    service.syncPlaylistTracks(playlist.id, dataSaverEnabled = false, forceRefresh = true)
                                        .onSuccess { selectedTracks = service.parsePlaylistTracks(it) }
                                        .onFailure { message = it.message }
                                    loading = false
                                }
                            },
                            enabled = !loading,
                        ) { Text("Refresh") }
                    }
                }
                if (loading) {
                    CircularProgressIndicator(modifier = Modifier.align(Alignment.CenterHorizontally).padding(16.dp))
                }
                LazyColumn(
                    modifier = Modifier.fillMaxWidth(),
                    contentPadding = PaddingValues(bottom = 28.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    items(selectedTracks, key = { it.uri + (it.uid ?: "") }) { track ->
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            AsyncImage(
                                model = track.imageUrl,
                                contentDescription = track.name,
                                contentScale = ContentScale.Crop,
                                modifier = Modifier.size(48.dp).clip(RoundedCornerShape(8.dp)),
                            )
                            Column(
                                modifier = Modifier.weight(1f).clickable {
                                    val query = listOf(track.name, track.artists).filter { it.isNotBlank() }.joinToString(" ")
                                    searchViewModel.requestSongSearch(query)
                                    navController.navigate(SearchDestination)
                                    showLikedSongs = false
                                    selectedPlaylist = null
                                }.padding(horizontal = 10.dp, vertical = 4.dp),
                            ) {
                                Text(track.name, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Text(
                                    listOf(track.artists, track.albumName).filter { !it.isNullOrBlank() }.joinToString(" · "),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                            TextButton(
                                onClick = {
                                    coroutineScope.launch {
                                        if (showLikedSongs) {
                                            service.setLiked(track.uri, false).onSuccess {
                                                service.cachedLibraryJson()?.let { raw ->
                                                    library = service.parseLibrarySnapshot(raw)
                                                    selectedTracks = library.likedTracks
                                                }
                                            }.onFailure { message = it.message }
                                        } else {
                                            val playlist = selectedPlaylist ?: return@launch
                                            val uid = track.uid ?: return@launch
                                            service.removeTracksFromPlaylist(playlist.id, listOf(uid)).onSuccess {
                                                service.cachedPlaylistTracksJson(playlist.id)?.let {
                                                    selectedTracks = service.parsePlaylistTracks(it)
                                                }
                                            }.onFailure { message = it.message }
                                        }
                                    }
                                },
                            ) { Text("Remove") }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SpotifyLibraryCard(
    title: String,
    subtitle: String,
    imageUrl: String?,
    onClick: () -> Unit,
) {
    Card(
        modifier = Modifier.width(148.dp).clickable(onClick = onClick),
        shape = RoundedCornerShape(14.dp),
    ) {
        Column(modifier = Modifier.padding(8.dp)) {
            AsyncImage(
                model = imageUrl,
                contentDescription = title,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxWidth().height(126.dp).clip(RoundedCornerShape(10.dp)),
            )
            Spacer(Modifier.height(8.dp))
            Text(title, maxLines = 2, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyMedium)
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
