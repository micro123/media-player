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

    private val playerStore = androidx.lifecycle.ViewModelStore()
    private val playerOwner = object : androidx.lifecycle.ViewModelStoreOwner { override val viewModelStore = playerStore }
    val player: io.github.micro123.mediaplayer.ui.PlayerViewModel by lazy {
        androidx.lifecycle.ViewModelProvider(playerOwner, object : androidx.lifecycle.ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : androidx.lifecycle.ViewModel> create(modelClass: Class<T>): T =
                io.github.micro123.mediaplayer.ui.PlayerViewModel(mediaRepository, createPlaybackEngine(), sessionState(),
                    mediaBrowser, playbackStore, bookmarks, playlists, clips, network, audioMetadata, videoPreviews, navidrome,
                    onForegroundPlaybackStarting = { MusicPlaybackService.start(application) }) as T
        })[io.github.micro123.mediaplayer.ui.PlayerViewModel::class.java]
    }

    val updates: io.github.micro123.mediaplayer.ui.UpdateViewModel by lazy {
        androidx.lifecycle.ViewModelProvider(playerOwner, object : androidx.lifecycle.ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : androidx.lifecycle.ViewModel> create(modelClass: Class<T>): T =
                io.github.micro123.mediaplayer.ui.UpdateViewModel() as T
        })[io.github.micro123.mediaplayer.ui.UpdateViewModel::class.java]
    }
    val updateDownloads by lazy { io.github.micro123.mediaplayer.data.update.UpdateDownloads(application) }

    // Only browsing/player presentation lives here. Persistent preferences, queues and
    // progress use PlaybackStore; a killed process must not silently restart music.
    @android.annotation.SuppressLint("VisibleForTests")
    private fun sessionState() = androidx.lifecycle.SavedStateHandle()

    val queuePreviews = io.github.micro123.mediaplayer.data.QueuePreviewRepository(audioMetadata, videoPreviews, java.io.File(application.cacheDir, "queue-previews-v1"))

    // A new instance belongs to each PlayerViewModel; it is released in onCleared().
    fun createPlaybackEngine(): PlaybackEngine = MpvPlaybackEngine(application,
        resolvedSources)
}
