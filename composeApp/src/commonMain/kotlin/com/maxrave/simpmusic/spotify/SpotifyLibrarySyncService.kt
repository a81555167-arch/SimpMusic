package com.maxrave.simpmusic.spotify

import com.maxrave.domain.manager.DataStoreManager
import com.maxrave.spotify.Spotify
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.contentType
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import kotlin.time.Clock

data class SpotifyLibraryPlaylist(
    val id: String,
    val name: String,
    val uri: String,
    val imageUrl: String?,
    val trackCount: Int,
)

data class SpotifyLibraryTrack(
    val uri: String,
    val name: String,
    val artists: String,
    val albumName: String?,
    val imageUrl: String?,
    val uid: String? = null,
)

data class SpotifyLibrarySnapshot(
    val playlists: List<SpotifyLibraryPlaylist>,
    val likedTracks: List<SpotifyLibraryTrack>,
)

data class SpotifyHomeCard(
    val title: String,
    val uri: String,
    val imageUrl: String?,
    val subtitle: String?,
    val openUrl: String?,
)

data class SpotifyHomeSection(
    val title: String,
    val cards: List<SpotifyHomeCard>,
)

/**
 * Local-first Spotify library synchronizer. Spotify's web-player GraphQL API is undocumented
 * and its persisted-query hashes rotate. Raw JSON is cached to preserve data across schema changes.
 * Failed reads never replace the last good cache or advance the successful-sync timestamp.
 */
class SpotifyLibrarySyncService(
    private val dataStoreManager: DataStoreManager,
) {
    private val spotify = Spotify()
    private val http = HttpClient(CIO)
    private val libraryMutex = Mutex()
    private val homeMutex = Mutex()
    private val playlistMutex = Mutex()
    private val tokenMutex = Mutex()
    private val hashMutex = Mutex()
    private var hashRegistryLoaded = false
    private val operationHashes = mutableMapOf(
        "libraryV3" to HASH_LIBRARY_V3,
        "fetchPlaylist" to HASH_FETCH_PLAYLIST,
        "fetchLibraryTracks" to HASH_LIBRARY_TRACKS,
        "addToPlaylist" to HASH_PLAYLIST_MUTATION,
        "removeFromPlaylist" to HASH_PLAYLIST_MUTATION,
        "addToLibrary" to HASH_LIBRARY_MUTATION,
        "removeFromLibrary" to HASH_LIBRARY_MUTATION,
        "home" to HASH_HOME,
        "searchDesktop" to HASH_SEARCH,
    )
    private val previousHashes = mutableMapOf<String, String>()
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    suspend fun isLoggedIn(): Boolean = dataStoreManager.spdc.first().isNotBlank()

    suspend fun isDataSaverEnabled(): Boolean =
        dataStoreManager.getString(DATA_SAVER_KEY).first()?.toBooleanStrictOrNull() ?: true

    suspend fun setDataSaverEnabled(enabled: Boolean) {
        dataStoreManager.putString(DATA_SAVER_KEY, enabled.toString())
    }

    suspend fun clearLocalCache() {
        libraryMutex.withLock {
            homeMutex.withLock {
                playlistMutex.withLock {
                    tokenMutex.withLock {
                        val cachedPlaylistIds = runCatching {
                            dataStoreManager.getString(KEY_PLAYLIST_CACHE_IDS).first()
                                ?.takeIf { it.isNotBlank() }
                                ?.let { json.parseToJsonElement(it).jsonArray.mapNotNull { value -> value.jsonPrimitive.contentOrNull } }
                                .orEmpty()
                        }.getOrDefault(emptyList())
                        cachedPlaylistIds.forEach { playlistId ->
                            dataStoreManager.putString("$KEY_PLAYLIST_CACHE_PREFIX$playlistId", "")
                            dataStoreManager.putString("$KEY_PLAYLIST_TIME_PREFIX$playlistId", "0")
                        }
                        dataStoreManager.putString(KEY_PLAYLIST_CACHE_IDS, "")
                        dataStoreManager.putString(KEY_LIBRARY_CACHE, "")
                        dataStoreManager.putString(KEY_HOME_CACHE, "")
                        dataStoreManager.putString(KEY_LIBRARY_SYNC_TIME, "0")
                        dataStoreManager.putString(KEY_HOME_SYNC_TIME, "0")
                        dataStoreManager.setSpotifyPersonalToken("")
                        dataStoreManager.setSpotifyPersonalTokenExpires(0L)
                    }
                }
            }
        }
    }

    suspend fun cachedLibraryJson(): String? = dataStoreManager.getString(KEY_LIBRARY_CACHE).first()
    suspend fun cachedHomeJson(): String? = dataStoreManager.getString(KEY_HOME_CACHE).first()
    suspend fun cachedPlaylistTracksJson(playlistId: String): String? =
        dataStoreManager.getString("$KEY_PLAYLIST_CACHE_PREFIX$playlistId").first()

    /** Parses the persistent library snapshot without making network requests. */
    fun parseLibrarySnapshot(snapshot: String?): SpotifyLibrarySnapshot {
        if (snapshot.isNullOrBlank()) return SpotifyLibrarySnapshot(emptyList(), emptyList())
        return runCatching {
            val root = json.parseToJsonElement(snapshot) as? JsonObject
                ?: return@runCatching SpotifyLibrarySnapshot(emptyList(), emptyList())
            val playlists = ((root["playlists"] as? JsonArray).orEmpty()).mapNotNull { element ->
                runCatching {
                    val wrapper = element as? JsonObject ?: return@runCatching null
                    val uri = wrapper.text("_uri") ?: wrapper.text("uri") ?: return@runCatching null
                    if (!uri.startsWith("spotify:playlist:")) return@runCatching null
                    val data = wrapper["data"] as? JsonObject ?: return@runCatching null
                    val name = data.text("name") ?: return@runCatching null
                    val images = data["images"] as? JsonObject
                    val imageItems = images?.get("items") as? JsonArray
                    val firstImage = imageItems?.firstOrNull() as? JsonObject
                    val sources = firstImage?.get("sources") as? JsonArray
                    val imageUrl = (sources?.firstOrNull() as? JsonObject)?.text("url")
                    val content = data["content"] as? JsonObject
                    val tracks = data["tracks"] as? JsonObject
                    val trackCount = content?.text("totalCount")?.toIntOrNull()
                        ?: tracks?.text("total")?.toIntOrNull()
                        ?: 0
                    SpotifyLibraryPlaylist(
                        id = uri.substringAfterLast(":"),
                        name = name,
                        uri = uri,
                        imageUrl = imageUrl,
                        trackCount = trackCount,
                    )
                }.getOrNull()
            }
            val likedTracks = ((root["likedTracks"] as? JsonArray).orEmpty()).mapNotNull { element ->
                runCatching {
                    val entry = element as? JsonObject ?: return@runCatching null
                    val wrapper = (entry["track"] as? JsonObject) ?: entry
                    parseTrackWrapper(wrapper)
                }.getOrNull()
            }
            SpotifyLibrarySnapshot(playlists, likedTracks)
        }.getOrDefault(SpotifyLibrarySnapshot(emptyList(), emptyList()))
    }

    /** Parses the locally cached track page for a playlist, including Spotify's per-item UID. */
    fun parsePlaylistTracks(snapshot: String?): List<SpotifyLibraryTrack> {
        if (snapshot.isNullOrBlank()) return emptyList()
        return runCatching {
            val root = json.parseToJsonElement(snapshot) as? JsonObject ?: return@runCatching emptyList()
            ((root["tracks"] as? JsonArray).orEmpty()).mapNotNull { element ->
                runCatching {
                    val wrapper = element as? JsonObject ?: return@runCatching null
                    parseTrackWrapper(wrapper, wrapper.text("uid"))
                }.getOrNull()
            }
        }.getOrDefault(emptyList())
    }

    private fun parseTrackWrapper(wrapper: JsonObject, uid: String? = null): SpotifyLibraryTrack? {
        val data = wrapper["data"] as? JsonObject ?: return null
        val uri = wrapper.text("_uri") ?: wrapper.text("uri") ?: data.text("uri") ?: return null
        val name = data.text("name") ?: return null
        val artistsObj = data["artists"] as? JsonObject
        val artistItems = artistsObj?.get("items") as? JsonArray
        val artists = artistItems.orEmpty().mapNotNull { item ->
            val artist = item as? JsonObject ?: return@mapNotNull null
            val profile = artist["profile"] as? JsonObject
            profile?.text("name") ?: artist.text("name")
        }.joinToString(", ")
        val album = data["albumOfTrack"] as? JsonObject ?: data["album"] as? JsonObject
        val albumName = album?.text("name")
        val coverArt = data["coverArt"] as? JsonObject
        val sources = coverArt?.get("sources") as? JsonArray
        val imageUrl = (sources?.firstOrNull() as? JsonObject)?.text("url")
        return SpotifyLibraryTrack(uri, name, artists, albumName, imageUrl, uid)
    }

    /** Parses the cached Spotify home feed for the Home screen without any network access. */
    fun parseHomeSections(snapshot: String?): List<SpotifyHomeSection> {
        if (snapshot.isNullOrBlank()) return emptyList()
        return runCatching {
            val root = json.parseToJsonElement(snapshot) as? JsonObject ?: return@runCatching emptyList()
            val feed = root["feed"] as? JsonObject ?: return@runCatching emptyList()
            val sectionContainer = feed["sectionContainer"] as? JsonObject ?: return@runCatching emptyList()
            val sectionsObject = sectionContainer["sections"] as? JsonObject ?: return@runCatching emptyList()
            val sections = sectionsObject["items"] as? JsonArray ?: return@runCatching emptyList()
            sections.mapNotNull(::parseHomeSection)
        }.getOrDefault(emptyList())
    }

    private fun parseHomeSection(element: JsonElement): SpotifyHomeSection? = runCatching {
        val section = element as? JsonObject ?: return@runCatching null
        val sectionData = section["data"] as? JsonObject
        val titleData = sectionData?.get("title") as? JsonObject
        val title = titleData?.text("transformedLabel")
            ?: titleData?.text("translatedBaseText")
            ?: titleData?.text("text")
            ?: return@runCatching null
        val itemElements = ((section["sectionItems"] as? JsonObject)?.get("items") as? JsonArray).orEmpty()
        val cards = itemElements.mapNotNull(::parseHomeCard)
        if (cards.isEmpty()) null else SpotifyHomeSection(title, cards)
    }.getOrNull()

    private fun parseHomeCard(element: JsonElement): SpotifyHomeCard? = runCatching {
        val item = element as? JsonObject ?: return@runCatching null
        val content = item["content"] as? JsonObject ?: return@runCatching null
        val data = content["data"] as? JsonObject ?: return@runCatching null
        val wrapperType = content.text("__typename")
        val uri = data.text("uri") ?: data.text("id")?.let { id ->
            when (wrapperType) {
                "PlaylistResponseWrapper" -> "spotify:playlist:$id"
                "AlbumResponseWrapper" -> "spotify:album:$id"
                "ArtistResponseWrapper" -> "spotify:artist:$id"
                else -> null
            }
        } ?: return@runCatching null
        val profile = data["profile"] as? JsonObject
        val name = data.text("name")
            ?: profile?.text("name")
            ?: ((data["title"] as? JsonObject)?.text("text"))
            ?: return@runCatching null
        val coverArt = data["coverArt"] as? JsonObject
        val visuals = data["visuals"] as? JsonObject
        val avatar = visuals?.get("avatarImage") as? JsonObject
        val imagesObject = data["images"] as? JsonObject
        val imageSources =
            (coverArt?.get("sources") as? JsonArray)
                ?: (avatar?.get("sources") as? JsonArray)
                ?: (((imagesObject?.get("items") as? JsonArray)?.firstOrNull() as? JsonObject)
                    ?.get("sources") as? JsonArray)
        val imageUrl = (imageSources?.firstOrNull() as? JsonObject)?.text("url")
        SpotifyHomeCard(
            title = name,
            uri = uri,
            imageUrl = imageUrl,
            subtitle = data.text("description"),
            openUrl = uriToWebUrl(uri),
        )
    }.getOrNull()

    private fun JsonObject.text(key: String): String? =
        this[key]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }

    private fun uriToWebUrl(uri: String): String? {
        val parts = uri.split(":")
        if (parts.size < 3 || parts[0] != "spotify") return null
        return when (parts[1]) {
            "playlist", "album", "artist", "track", "show", "episode" ->
                "https://open.spotify.com/${parts[1]}/${parts.last()}"
            else -> null
        }
    }

    suspend fun syncLibrary(
        dataSaverEnabled: Boolean = false,
        forceRefresh: Boolean = false,
    ): Result<String> = libraryMutex.withLock {
        runCatching {
        val now = Clock.System.now().toEpochMilliseconds()
        val last = dataStoreManager.getString(KEY_LIBRARY_SYNC_TIME).first()?.toLongOrNull()
        if (!SpotifySyncPolicy.shouldRefresh(last, now, SpotifySyncPolicy.DEFAULT_LIBRARY_REFRESH_INTERVAL_MS, dataSaverEnabled, forceRefresh)) {
            return@runCatching cachedLibraryJson() ?: error("No cached Spotify library yet; perform a manual refresh.")
        }

        val token = accessToken()
        val playlists = fetchAllPages(token, "libraryV3", ::playlistVariables, "data", "me", "libraryV3")
        val likedTracks = fetchAllPages(token, "fetchLibraryTracks", ::pageVariables, "data", "me", "library", "tracks")
        val snapshot = buildJsonObject {
            put("syncedAt", now)
            put("playlists", JsonArray(playlists))
            put("likedTracks", JsonArray(likedTracks))
        }.toString()

        dataStoreManager.putString(KEY_LIBRARY_CACHE, snapshot)
        dataStoreManager.putString(KEY_LIBRARY_SYNC_TIME, now.toString())
        snapshot
        }
    }

    suspend fun syncHome(
        dataSaverEnabled: Boolean = false,
        forceRefresh: Boolean = false,
    ): Result<String> = homeMutex.withLock {
        runCatching {
        val now = Clock.System.now().toEpochMilliseconds()
        val last = dataStoreManager.getString(KEY_HOME_SYNC_TIME).first()?.toLongOrNull()
        if (!SpotifySyncPolicy.shouldRefresh(last, now, SpotifySyncPolicy.DEFAULT_HOME_REFRESH_INTERVAL_MS, dataSaverEnabled, forceRefresh)) {
            return@runCatching cachedHomeJson() ?: error("No cached Spotify home feed yet; perform a manual refresh.")
        }
        val token = accessToken()
        val variables = buildJsonObject {
            put("homeEndUserIntegration", "INTEGRATION_WEB_PLAYER")
            put("timeZone", "UTC")
            put("sp_t", "")
            put("facet", "")
            put("sectionItemsLimit", 10)
            put("includeEpisodeContentRatingsV2", false)
        }
        val response = graphql(token, "home", HASH_HOME, variables)
        val home = response["data"]?.jsonObject?.get("home")
            ?: error("Spotify home response did not contain data.home")
        val snapshot = buildJsonObject {
            put("syncedAt", now)
            put("feed", home)
        }.toString()
        dataStoreManager.putString(KEY_HOME_CACHE, snapshot)
        dataStoreManager.putString(KEY_HOME_SYNC_TIME, now.toString())
        snapshot
        }
    }

    suspend fun syncPlaylistTracks(
        playlistId: String,
        dataSaverEnabled: Boolean = false,
        forceRefresh: Boolean = false,
    ): Result<String> = playlistMutex.withLock {
        runCatching {
        require(playlistId.isNotBlank()) { "Playlist ID cannot be blank" }
        val cacheKey = "$KEY_PLAYLIST_CACHE_PREFIX$playlistId"
        val timeKey = "$KEY_PLAYLIST_TIME_PREFIX$playlistId"
        val now = Clock.System.now().toEpochMilliseconds()
        val last = dataStoreManager.getString(timeKey).first()?.toLongOrNull()
        if (!SpotifySyncPolicy.shouldRefresh(last, now, SpotifySyncPolicy.DEFAULT_LIBRARY_REFRESH_INTERVAL_MS, dataSaverEnabled, forceRefresh)) {
            return@runCatching dataStoreManager.getString(cacheKey).first()
                ?: error("No cached tracks for this playlist yet; perform a manual refresh.")
        }
        val token = accessToken()
        val tracks = fetchAllPages(token, "fetchPlaylist", { offset, limit ->
            buildJsonObject {
                put("uri", "spotify:playlist:$playlistId")
                put("offset", offset)
                put("limit", limit)
                put("enableWatchFeedEntrypoint", false)
            }
        }, "data", "playlistV2", "content")
        val snapshot = buildJsonObject {
            put("syncedAt", now)
            put("playlistId", playlistId)
            put("tracks", JsonArray(tracks))
        }.toString()
        dataStoreManager.putString(cacheKey, snapshot)
        dataStoreManager.putString(timeKey, now.toString())
        val cachedIds = runCatching {
            dataStoreManager.getString(KEY_PLAYLIST_CACHE_IDS).first()
                ?.takeIf { it.isNotBlank() }
                ?.let { json.parseToJsonElement(it).jsonArray.mapNotNull { value -> value.jsonPrimitive.contentOrNull } }
                .orEmpty()
        }.getOrDefault(emptyList())
        dataStoreManager.putString(KEY_PLAYLIST_CACHE_IDS, JsonArray((cachedIds + playlistId).distinct().map { kotlinx.serialization.json.JsonPrimitive(it) }).toString())
        snapshot
        }
    }

    suspend fun setLiked(
        trackUri: String,
        liked: Boolean,
        trackWrapper: JsonObject? = null,
    ): Result<Unit> = runCatching {
        require(trackUri.startsWith("spotify:track:")) { "Expected a Spotify track URI" }
        val token = accessToken()
        val variables = buildJsonObject {
            putJsonArray("libraryItemUris") { add(trackUri) }
        }
        graphql(token, if (liked) "addToLibrary" else "removeFromLibrary", HASH_LIBRARY_MUTATION, variables)
        libraryMutex.withLock {
            patchLikedCache(trackUri, liked, trackWrapper)
        }
        Unit
    }

    /**
     * Finds an exact title + artist match in Spotify's own search results. The caller can use the
     * returned wrapper for a mutation and local cache patch; no guessed first-result mapping.
     */
    suspend fun findTrackWrapper(title: String, artists: String): Result<JsonObject?> = runCatching {
        require(title.isNotBlank()) { "Track title cannot be blank" }
        val token = accessToken()
        val variables = buildJsonObject {
            put("searchTerm", listOf(title, artists).filter { it.isNotBlank() }.joinToString(" "))
            put("offset", 0)
            put("limit", 10)
            put("numberOfTopResults", 5)
            put("includeAudiobooks", false)
            put("includeArtistHasConcertsField", false)
            put("includePreReleases", false)
            put("includeLocalConcertsField", false)
            put("includeAuthors", false)
        }
        val response = graphql(token, "searchDesktop", HASH_SEARCH, variables)
        val search = response["data"]?.jsonObject?.get("searchV2") as? JsonObject
            ?: error("Spotify search response did not contain searchV2")
        val items = ((search["tracksV2"] as? JsonObject)?.get("items") as? JsonArray).orEmpty()
        val expectedTitle = normalize(title)
        val expectedArtists = artists.split(",", "&", " feat. ", " ft. ")
            .map(::normalize).filter { it.isNotBlank() }
        items.firstNotNullOfOrNull { element ->
            val item = element as? JsonObject ?: return@firstNotNullOfOrNull null
            val wrapper = item["item"] as? JsonObject ?: return@firstNotNullOfOrNull null
            val data = wrapper["data"] as? JsonObject ?: return@firstNotNullOfOrNull null
            if (normalize(data.text("name").orEmpty()) != expectedTitle) return@firstNotNullOfOrNull null
            val artistItems = (((data["artists"] as? JsonObject)?.get("items") as? JsonArray).orEmpty())
            val foundArtists = artistItems.mapNotNull { artistElement ->
                val artist = artistElement as? JsonObject ?: return@mapNotNull null
                (artist["profile"] as? JsonObject)?.text("name") ?: artist.text("name")
            }.map(::normalize)
            if (expectedArtists.isNotEmpty() && foundArtists.none { found -> expectedArtists.any { expected -> expected == found || expected.contains(found) || found.contains(expected) } }) {
                return@firstNotNullOfOrNull null
            }
            val uri = wrapper.text("_uri") ?: wrapper.text("uri") ?: return@firstNotNullOfOrNull null
            if (!uri.startsWith("spotify:track:")) return@firstNotNullOfOrNull null
            wrapper
        }
    }

    suspend fun setLikedByMetadata(title: String, artists: String, liked: Boolean): Result<Unit> = runCatching {
        val wrapper = findTrackWrapper(title, artists).getOrThrow()
            ?: error("Spotify could not find an exact title and artist match.")
        val uri = wrapper.text("_uri") ?: wrapper.text("uri") ?: error("Spotify search result has no track URI")
        setLiked(uri, liked, if (liked) wrapper else null).getOrThrow()
        Unit
    }

    suspend fun addTrackToPlaylistByMetadata(
        playlistId: String,
        title: String,
        artists: String,
    ): Result<Unit> = runCatching {
        val wrapper = findTrackWrapper(title, artists).getOrThrow()
            ?: error("Spotify could not find an exact title and artist match.")
        val uri = wrapper.text("_uri") ?: wrapper.text("uri") ?: error("Spotify search result has no track URI")
        addTracksToPlaylist(playlistId, listOf(uri)).getOrThrow()
        Unit
    }

    private suspend fun patchLikedCache(trackUri: String, liked: Boolean, trackWrapper: JsonObject?) {
        val raw = cachedLibraryJson() ?: return
        if (raw.isBlank()) return
        val root = json.parseToJsonElement(raw) as? JsonObject ?: return
        val tracks = (root["likedTracks"] as? JsonArray).orEmpty()
        val retained = tracks.filterNot { element ->
            val entry = element as? JsonObject ?: return@filterNot false
            val wrapper = entry["track"] as? JsonObject ?: entry
            (wrapper.text("_uri") ?: wrapper.text("uri")) == trackUri
        }
        val updatedTracks = if (liked && trackWrapper != null) {
            retained + buildJsonObject { put("track", trackWrapper) }
        } else {
            retained
        }
        val updated = buildJsonObject {
            root.forEach { (key, value) -> put(key, value) }
            put("likedTracks", JsonArray(updatedTracks))
        }
        dataStoreManager.putString(KEY_LIBRARY_CACHE, updated.toString())
    }

    private fun normalize(value: String): String =
        value.lowercase().filter { it.isLetterOrDigit() }

    suspend fun addTracksToPlaylist(playlistId: String, trackUris: List<String>): Result<Unit> = runCatching {
        require(playlistId.isNotBlank())
        require(trackUris.isNotEmpty())
        require(trackUris.all { it.startsWith("spotify:track:") }) { "Every item must be a Spotify track URI" }
        val token = accessToken()
        val variables = buildJsonObject {
            put("playlistUri", "spotify:playlist:$playlistId")
            putJsonArray("playlistItemUris") { trackUris.forEach { add(it) } }
            putJsonObject("newPosition") {
                put("moveType", "BOTTOM_OF_PLAYLIST")
                put("fromUid", JsonNull)
            }
        }
        graphql(token, "addToPlaylist", HASH_PLAYLIST_MUTATION, variables)
        dataStoreManager.putString("$KEY_PLAYLIST_TIME_PREFIX$playlistId", "0")
        syncPlaylistTracks(playlistId, dataSaverEnabled = false, forceRefresh = true).getOrThrow()
        Unit
    }

    suspend fun removeTracksFromPlaylist(playlistId: String, itemUids: List<String>): Result<Unit> = runCatching {
        require(playlistId.isNotBlank())
        require(itemUids.isNotEmpty())
        require(itemUids.all { it.isNotBlank() }) { "Playlist item IDs are required for safe removal" }
        val token = accessToken()
        val variables = buildJsonObject {
            put("playlistUri", "spotify:playlist:$playlistId")
            putJsonArray("uids") { itemUids.forEach { add(it) } }
        }
        graphql(token, "removeFromPlaylist", HASH_PLAYLIST_MUTATION, variables)
        dataStoreManager.putString("$KEY_PLAYLIST_TIME_PREFIX$playlistId", "0")
        syncPlaylistTracks(playlistId, dataSaverEnabled = false, forceRefresh = true).getOrThrow()
        Unit
    }

    private suspend fun accessToken(): String = tokenMutex.withLock {
        val spdc = dataStoreManager.spdc.first()
        require(spdc.isNotBlank()) { "Log in to Spotify before syncing your library." }
        val current = dataStoreManager.spotifyPersonalToken.first()
        val expiry = dataStoreManager.spotifyPersonalTokenExpires.first()
        val now = Clock.System.now().toEpochMilliseconds()
        if (current.isNotBlank() && expiry > now + TOKEN_REFRESH_SKEW_MS) return current

        val token = spotify.getPersonalTokenWithTotp(spdc).getOrThrow()
        require(!token.isAnonymous && token.accessToken.isNotBlank()) {
            "Spotify returned an anonymous token. Please sign in to Spotify again."
        }
        dataStoreManager.setSpotifyPersonalToken(token.accessToken)
        dataStoreManager.setSpotifyPersonalTokenExpires(token.accessTokenExpirationTimestampMs)
        token.accessToken
    }

    private suspend fun fetchAllPages(
        token: String,
        operation: String,
        variables: (offset: Int, limit: Int) -> JsonObject,
        vararg path: String,
    ): List<JsonElement> {
        val output = mutableListOf<JsonElement>()
        var offset = 0
        var expectedTotal: Int? = null
        val pageSize = 50
        while (true) {
            val response = graphql(
                token,
                operation,
                when (operation) {
                    "libraryV3" -> HASH_LIBRARY_V3
                    "fetchLibraryTracks" -> HASH_LIBRARY_TRACKS
                    "fetchPlaylist" -> HASH_FETCH_PLAYLIST
                    else -> error("Unsupported Spotify operation: $operation")
                },
                variables(offset, pageSize),
            )
            var node: JsonElement = response
            for (key in path) node = node.jsonObject[key] ?: error("Spotify response missing $key")
            val container = node.jsonObject
            val items = when (operation) {
                "libraryV3" -> container["items"]?.jsonArray.orEmpty().mapNotNull { it.jsonObject["item"] }
                "fetchLibraryTracks" -> container["items"]?.jsonArray.orEmpty()
                else -> container["items"]?.jsonArray.orEmpty().mapNotNull { element ->
                    val entry = element as? JsonObject ?: return@mapNotNull null
                    val item = entry["itemV2"] as? JsonObject ?: return@mapNotNull null
                    buildJsonObject {
                        item.forEach { (key, value) -> put(key, value) }
                        val uid = entry["uid"] ?: item["uid"]
                        if (uid != null) put("uid", uid)
                    }
                }
            }
            val total = container["totalCount"]?.jsonPrimitive?.intOrNull
            if (expectedTotal == null) expectedTotal = total
            output += items
            if (items.isEmpty()) break
            if (expectedTotal != null && output.size >= expectedTotal!!) break
            if (expectedTotal == null && items.size < pageSize) break
            offset += items.size
        }
        return output
    }

    private fun playlistVariables(offset: Int, limit: Int) = buildJsonObject {
        putJsonArray("filters") { add("Playlists") }
        put("order", JsonNull)
        put("textFilter", "")
        putJsonArray("features") {
            add("LIKED_SONGS")
            add("YOUR_EPISODES_V2")
            add("PRERELEASES")
            add("EVENTS")
        }
        put("limit", limit)
        put("offset", offset)
        put("flatten", true)
        putJsonArray("expandedFolders") {}
        put("folderUri", JsonNull)
        put("includeFoldersWhenFlattening", false)
    }

    private fun pageVariables(offset: Int, limit: Int) = buildJsonObject {
        put("offset", offset)
        put("limit", limit)
    }

    private suspend fun refreshHashRegistry(force: Boolean = false) {
        hashMutex.withLock {
            val now = Clock.System.now().toEpochMilliseconds()
            if (!hashRegistryLoaded) {
                dataStoreManager.getString(KEY_HASH_CACHE).first()
                    ?.takeIf { it.isNotBlank() }
                    ?.let(::applyHashRegistry)
                hashRegistryLoaded = true
            }
            val lastFetch = dataStoreManager.getString(KEY_HASH_FETCH_TIME).first()?.toLongOrNull()
            if (!force && lastFetch != null && now >= lastFetch && now - lastFetch < HASH_REFRESH_INTERVAL_MS) {
                return@withLock
            }
            // Throttle failed fetches too; otherwise an offline device would spend ten seconds
            // retrying the same tiny registry before every GraphQL operation.
            dataStoreManager.putString(KEY_HASH_FETCH_TIME, now.toString())
            try {
                val response = withTimeout(HASH_FETCH_TIMEOUT_MS) {
                    http.get(HASH_REGISTRY_URL) {
                        header(HttpHeaders.Accept, "application/json")
                    }
                }
                if (response.status.value !in 200..299) return@withLock
                val raw = response.bodyAsText()
                if (applyHashRegistry(raw)) {
                    dataStoreManager.putString(KEY_HASH_CACHE, raw)
                    dataStoreManager.putString(KEY_HASH_FETCH_TIME, now.toString())
                }
            } catch (_: Exception) {
                // The last cached registry and hardcoded release hashes remain available offline.
            }
        }
    }

    private fun applyHashRegistry(raw: String): Boolean = runCatching {
        val root = json.parseToJsonElement(raw) as? JsonObject ?: return@runCatching false
        val operations = root["operations"] as? JsonObject ?: return@runCatching false
        var applied = 0
        operations.forEach { (operation, value) ->
            val entry = value as? JsonObject ?: return@forEach
            val hash = entry["hash"]?.jsonPrimitive?.contentOrNull
                ?.takeIf { it.length == 64 && it.all(Char::isLetterOrDigit) }
                ?: return@forEach
            operationHashes[operation] = hash
            entry["previous_hash"]?.jsonPrimitive?.contentOrNull
                ?.takeIf { it.length == 64 }
                ?.let { previousHashes[operation] = it }
            applied++
        }
        applied > 0
    }.getOrDefault(false)

    private suspend fun graphql(
        token: String,
        operation: String,
        hash: String,
        variables: JsonObject,
    ): JsonObject {
        refreshHashRegistry()
        var activeHash = operationHashes[operation] ?: hash
        for (attempt in 0..3) {
            val body = buildJsonObject {
                put("operationName", operation)
                put("variables", variables)
                putJsonObject("extensions") {
                    putJsonObject("persistedQuery") {
                        put("version", 1)
                        put("sha256Hash", activeHash)
                    }
                }
            }
            val response = http.post(GRAPHQL_URL) {
                header(HttpHeaders.Authorization, "Bearer $token")
                header(HttpHeaders.Accept, "application/json")
                contentType(ContentType.Application.Json)
                setBody(body.toString())
            }
            val responseText = response.bodyAsText()
            if (response.status.value == 429 && attempt < 3) {
                delay((response.headers["Retry-After"]?.toLongOrNull() ?: (attempt + 1L) * 2L) * 1000L)
                continue
            }
            if (response.status.value !in 200..299) {
                if (response.status.value >= 500 && attempt < 3) {
                    delay((attempt + 1L) * 500L)
                    continue
                }
                error("Spotify $operation request failed (HTTP ${response.status.value})")
            }
            val parsed = json.parseToJsonElement(responseText).jsonObject
            val errors = parsed["errors"]?.jsonArray.orEmpty()
            if (errors.isNotEmpty()) {
                val message = errors.first().jsonObject["message"]?.jsonPrimitive?.contentOrNull.orEmpty()
                val persistedQueryRejected =
                    message.contains("PersistedQueryNotFound", ignoreCase = true) ||
                    message.contains("PersistedQueryNotSupported", ignoreCase = true)
                if (persistedQueryRejected && attempt == 0) {
                    refreshHashRegistry(force = true)
                    val refreshed = operationHashes[operation]
                    val previous = previousHashes[operation]
                    activeHash = when {
                        refreshed != null && refreshed != activeHash -> refreshed
                        previous != null && previous != activeHash -> previous
                        else -> activeHash
                    }
                    if (activeHash != hash || previous != null) continue
                }
                if (persistedQueryRejected && attempt == 1) {
                    val previous = previousHashes[operation]
                    if (previous != null && previous != activeHash) {
                        activeHash = previous
                        continue
                    }
                }
                throw IllegalStateException("Spotify $operation failed: $message")
            }
            return parsed
        }
        error("Spotify $operation failed after retries")
    }

    companion object {
        const val DATA_SAVER_KEY = "spotify_sync_data_saver_enabled_v1"
        private const val TOKEN_REFRESH_SKEW_MS = 60_000L
        private const val HASH_REGISTRY_URL = "https://francescograzioso.github.io/Meld/spotify-gql-hashes.json"
        private const val HASH_REFRESH_INTERVAL_MS = 24 * 60 * 60 * 1000L
        private const val HASH_FETCH_TIMEOUT_MS = 10_000L
        private const val KEY_HASH_CACHE = "spotify_sync_hash_registry_json_v1"
        private const val KEY_HASH_FETCH_TIME = "spotify_sync_hash_registry_fetch_ms_v1"
        private const val GRAPHQL_URL = "https://api-partner.spotify.com/pathfinder/v1/query"
        private const val KEY_LIBRARY_CACHE = "spotify_sync_library_json_v1"
        private const val KEY_HOME_CACHE = "spotify_sync_home_json_v1"
        private const val KEY_LIBRARY_SYNC_TIME = "spotify_sync_library_success_ms_v1"
        private const val KEY_HOME_SYNC_TIME = "spotify_sync_home_success_ms_v1"
        private const val KEY_PLAYLIST_CACHE_PREFIX = "spotify_sync_playlist_json_v1_"
        private const val KEY_PLAYLIST_TIME_PREFIX = "spotify_sync_playlist_success_ms_v1_"
        private const val KEY_PLAYLIST_CACHE_IDS = "spotify_sync_playlist_cache_ids_v1"
        private const val HASH_LIBRARY_V3 = "390c78e5b951029bad359785e69b07b536a509c581cbcd0aded5e5067f187455"
        private const val HASH_FETCH_PLAYLIST = "8964e8eafb21aa992a7d951d256d83285c04be2105d209262901de70cb97584a"
        private const val HASH_LIBRARY_TRACKS = "087278b20b743578a6262c2b0b4bcd20d879c503cc359a2285baf083ef944240"
        private const val HASH_PLAYLIST_MUTATION = "b907ad6b088d5bf7680fa81198996d58398c2d55dfcdf59fcc959e0be98a418a"
        private const val HASH_LIBRARY_MUTATION = "896ebcb47815681340860d121cb5d494e157e2a78d3950385cd54e0393c67148"
        private const val HASH_HOME = "5fb7da7a03ee2776c0670856609cb85a189a3cecc36cc160b91cde79063b9cf1"
        private const val HASH_SEARCH = "db61238974d27839a136c9dc02bfdbe3fab7635f21cf85976ebff9a1ee281345"
    }
}
