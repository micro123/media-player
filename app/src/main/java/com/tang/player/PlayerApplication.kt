package com.tang.player

import android.app.Application
import com.tang.player.core.PlaybackEngine
import com.tang.player.core.MpvPlaybackEngine
import com.tang.player.data.MediaRepository
import com.tang.player.data.MediaBrowserRepository
import com.tang.player.data.PlaybackStore
import com.tang.player.data.BookmarkRepository
import com.tang.player.data.PlaylistRepository
import com.tang.player.data.AndroidClipExporter

class PlayerApplication : Application() {
    val container: AppContainer by lazy { AppContainer(this) }
}

class AppContainer(private val application: Application,
    providers: List<com.tang.player.data.MediaSourceProvider> = emptyList(),
    private val playbackSources: com.tang.player.core.PlaybackSourceResolver? = null,
) {
    val bookmarks = BookmarkRepository(application)
    val network = com.tang.player.data.network.NetworkRepository(application, bookmarks)
    val mediaRepository = MediaRepository(application, network.providers + providers)
    val mediaBrowser = MediaBrowserRepository(application)
    val playbackStore = PlaybackStore(application)
    val playlists = PlaylistRepository(application, mediaRepository, network)
    val clips = AndroidClipExporter(application)
    val audioMetadata = com.tang.player.data.AudioMetadataRepository(playbackSources ?:
        com.tang.player.data.network.RemotePlaybackSourceResolver(application, network::open))
    val videoPreviews = com.tang.player.data.VideoPreviewRepository(com.tang.player.core.AndroidPlaybackSourceResolver(application))

    // A new instance belongs to each PlayerViewModel; it is released in onCleared().
    fun createPlaybackEngine(): PlaybackEngine = MpvPlaybackEngine(application,
        playbackSources ?: com.tang.player.data.network.RemotePlaybackSourceResolver(application, network::open))
}
