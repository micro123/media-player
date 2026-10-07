package io.github.micro123.mediaplayer

import android.app.Application
import io.github.micro123.mediaplayer.core.PlaybackEngine
import io.github.micro123.mediaplayer.core.MpvPlaybackEngine
import io.github.micro123.mediaplayer.data.MediaRepository
import io.github.micro123.mediaplayer.data.MediaBrowserRepository
import io.github.micro123.mediaplayer.data.PlaybackStore
import io.github.micro123.mediaplayer.data.BookmarkRepository
import io.github.micro123.mediaplayer.data.PlaylistRepository
import io.github.micro123.mediaplayer.data.AndroidClipExporter

class PlayerApplication : Application() {
    val container: AppContainer by lazy { AppContainer(this) }
}

class AppContainer(private val application: Application,
    providers: List<io.github.micro123.mediaplayer.data.MediaSourceProvider> = emptyList(),
    private val playbackSources: io.github.micro123.mediaplayer.core.PlaybackSourceResolver? = null,
) {
    val bookmarks = BookmarkRepository(application)
    val network = io.github.micro123.mediaplayer.data.network.NetworkRepository(application, bookmarks)
    val navidrome = io.github.micro123.mediaplayer.data.navidrome.NavidromeRepository(bookmarks, network.credentials)
    private val remoteSources = io.github.micro123.mediaplayer.data.network.RemotePlaybackSourceResolver(application, network::open)
    private val resolvedSources = playbackSources ?: io.github.micro123.mediaplayer.core.PlaybackSourceResolver { media ->
        if (media.sourceKind == io.github.micro123.mediaplayer.core.MediaSourceKind.NAVIDROME) navidrome.open(media) else remoteSources.open(media)
    }
    val mediaRepository = MediaRepository(application, network.providers + navidrome + providers)
    val mediaBrowser = MediaBrowserRepository(application)
    val playbackStore = PlaybackStore(application)
    val playlists = PlaylistRepository(application, mediaRepository, network)
    val clips = AndroidClipExporter(application)
    val audioMetadata = io.github.micro123.mediaplayer.data.AudioMetadataRepository(resolvedSources, navidrome::tags)
    val videoPreviews = io.github.micro123.mediaplayer.data.VideoPreviewRepository(io.github.micro123.mediaplayer.core.AndroidPlaybackSourceResolver(application))

    // A new instance belongs to each PlayerViewModel; it is released in onCleared().
    fun createPlaybackEngine(): PlaybackEngine = MpvPlaybackEngine(application,
        resolvedSources)
}
