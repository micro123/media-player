package com.tang.player.ui

import android.net.Uri
import com.tang.player.ExternalMediaRequest
import com.tang.player.mediaMimeType
import androidx.core.net.toUri
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.tang.player.core.MediaItem
import com.tang.player.core.PlaybackEngine
import com.tang.player.core.PlaybackState
import com.tang.player.core.PlaybackStatus
import com.tang.player.data.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.channels.Channel
import com.tang.player.data.network.*

enum class LibrarySource(val label: String) { MEDIA_STORE("安卓媒体库"), FOLDER("文件夹"), RECENT("最近打开") }
enum class FileView { LOCATIONS, LOCAL, NETWORK }

data class LibraryState(
    val recent: List<MediaItem> = emptyList(),
    val media: List<MediaItem> = emptyList(),
    val mediaEntries: List<LibraryMediaEntry> = emptyList(),
    val entries: List<FolderEntry> = emptyList(),
    val folderPath: List<FolderLocation> = emptyList(),
    val source: LibrarySource = LibrarySource.MEDIA_STORE,
    val access: MediaAccess = MediaAccess(false, false, false),
    val isLoading: Boolean = true,
    val openFailed: Boolean = false,
    val browserError: String? = null,
    val storageBrowser: Boolean = false,
)

data class RemoteBrowserState(val root: String, val current: String, val entries: List<SourceEntry> = emptyList(),
    val loading: Boolean = true, val error: String? = null, val networkRestricted: Boolean = false)

class PlayerViewModel(
    private val repository: MediaRepository,
    private val engine: PlaybackEngine,
    private val savedStateHandle: SavedStateHandle,
    private val browser: MediaBrowserRepository,
    private val store: PlaybackStore,
    private val bookmarksStore: BookmarkRepository,
    private val playlists: PlaylistRepository,
    private val clips: ClipExporter,
    private val network: NetworkRepository? = null,
    private val audioMetadataRepository: AudioMetadataRepository? = null,
    private val videoPreviewRepository: VideoPreviewRepository? = null,
) : ViewModel() {
    private val mutableSeekPreview = MutableStateFlow<VideoSeekPreview?>(null)
    val seekPreview = mutableSeekPreview.asStateFlow()
    private val mutableSeekThumbnail = MutableStateFlow<android.graphics.Bitmap?>(null)
    val seekThumbnail = mutableSeekThumbnail.asStateFlow()
    private val mutableAudioMetadata = MutableStateFlow(AudioMetadata())
    val audioMetadata = mutableAudioMetadata.asStateFlow()
    private val mutableRemoteBrowser = MutableStateFlow<RemoteBrowserState?>(null)
    val remoteBrowser = mutableRemoteBrowser.asStateFlow()
    private var remoteBrowseJob: Job? = null
    private var remoteBrowseGeneration = 0L
    val bookmarks = bookmarksStore.items
    private val messageChannel = Channel<String>(Channel.BUFFERED)
    val messages = messageChannel.receiveAsFlow()
    private val mutablePlaylistFolder = MutableStateFlow<String?>(null)
    val playlistFolderRequest = mutablePlaylistFolder.asStateFlow()
    private val mutableClip = MutableStateFlow(ClipSelection())
    val clip = mutableClip.asStateFlow()
    private var clipPreparation: Job? = null
    private val mutableLibrary = MutableStateFlow(LibraryState())
    val library = mutableLibrary.asStateFlow()
    val playback = engine.state
    private val mutableQueue = MutableStateFlow(PlaylistState())
    val queue = mutableQueue.asStateFlow()
    private val mutablePreferences = MutableStateFlow(store.readPreferences())
    val preferences = mutablePreferences.asStateFlow()
    private val mutableSpeed = MutableStateFlow(if (preferences.value.rememberSpeed) preferences.value.lastSpeed else 1.0)
    val baseSpeed = mutableSpeed.asStateFlow()
    private val mutableBoost = MutableStateFlow(false)
    val speedBoost = mutableBoost.asStateFlow()
    private val mutableResume = MutableStateFlow(0L)
    val resumePosition = mutableResume.asStateFlow()
    val selectedTab = savedStateHandle.getStateFlow("selected_tab", 0)
    private val mutableFileView = MutableStateFlow(FileView.LOCATIONS)
    val fileView = mutableFileView.asStateFlow()
    val fullScreen = savedStateHandle.getStateFlow("full_screen", false)
    val audioPlayerOpen = savedStateHandle.getStateFlow("audio_player_open", false)
    private var tree: Uri? = browser.lastTree()
    private var loadJob: Job? = null
    private val initialized = CompletableDeferred<Unit>()
    private var externalOpenJob: Job? = null
    private var browseJob: Job? = null
    private var completedUri: String? = null
    private var bookmarkStamp = 0L
    private var bookmarkUri: String? = null

    init {
        viewModelScope.launch {
            // Sequential + conflated: a drag never spawns concurrent decoders for stale positions.
            seekPreview.map { it?.takeUnless { value -> value.cancelled }?.let { value -> value.mediaUri to value.targetPositionMs / 1000 } }
                .distinctUntilChanged().collect { request ->
                    mutableSeekThumbnail.value = null
                    if (request != null) {
                        kotlinx.coroutines.delay(100)
                        val current = seekPreview.value
                        val media = playback.value.media
                        if (current != null && !current.cancelled && media?.uri == current.mediaUri) {
                            val frame = videoPreviewRepository?.read(media, current.targetPositionMs)
                            val latest = seekPreview.value
                            if (latest?.mediaUri == current.mediaUri && !latest.cancelled && latest.targetPositionMs / 1000 == current.targetPositionMs / 1000)
                                mutableSeekThumbnail.value = frame
                        }
                    }
                }
        }
        viewModelScope.launch {
            playback.map { it.media?.takeUnless { media -> media.isVideo } }.distinctUntilChangedBy { it?.uri }.collectLatest { media ->
                mutableAudioMetadata.value = if (media == null) AudioMetadata() else AudioMetadata(uri = media.uri, title = media.displayName, loading = true)
                if (media != null) mutableAudioMetadata.value = audioMetadataRepository?.read(media) ?: AudioMetadata(uri = media.uri, title = media.displayName)
            }
        }
        engine.setSpeed(baseSpeed.value)
        engine.setVideoAspectRatio(preferences.value.aspect.ratio)
        viewModelScope.launch {
            preferences.drop(1).collect { store.writePreferences(it) }
        }
        viewModelScope.launch {
            try {
                val recent = repository.readRecent()
                val items = store.readQueue()
                mutableQueue.value = PlaylistState(items)
                mutableLibrary.update { it.copy(recent = recent, isLoading = false) }
                // Subscribe after restoring, so startup never overwrites a saved queue with an empty one.
                launch { queue.map { it.items }.distinctUntilChanged().drop(1).collect { store.writeQueue(it) } }
                refreshLibrary()
                tree?.let { restoreFolder(it) }
                savedStateHandle.get<String>("selected_uri")?.let { uri ->
                    val index = items.indexOfFirst { it.uri == uri }
                    if (index >= 0) playIndex(index) else open(uri.toUri())
                }
            } finally { initialized.complete(Unit) }
        }
        viewModelScope.launch {
            playback.collect { state ->
                if (state.status == PlaybackStatus.PAUSED || state.status == PlaybackStatus.ERROR || state.status == PlaybackStatus.ENDED) setSpeedBoost(false)
                val uri = state.media?.uri
                if (clip.value.media?.uri != uri && !clip.value.exporting && (uri != null || clip.value.endMs == null)) mutableClip.value = ClipSelection()
                val now = android.os.SystemClock.elapsedRealtime()
                if (uri != null && state.durationMs > 0 && state.canControl &&
                    (uri != bookmarkUri || now - bookmarkStamp >= 3000 || state.status != PlaybackStatus.PLAYING)) {
                    savePosition(state)
                    bookmarkStamp = now
                    bookmarkUri = uri
                }
                if (state.status == PlaybackStatus.ENDED && uri != null && completedUri != uri) {
                    completedUri = uri
                    if (preferences.value.autoNext && queue.value.current?.uri == uri && queue.value.hasNext) next()
                } else if (state.status == PlaybackStatus.PLAYING) completedUri = null
            }
        }
    }

    // Tabs are browsing destinations. Playback is a separate screen, never a tab.
    fun selectTab(index: Int) {
        val tab = index.coerceIn(0, 4)
        savedStateHandle["selected_tab"] = tab
        if (tab == 0 && library.value.source == LibrarySource.FOLDER) selectSource(LibrarySource.MEDIA_STORE)
        if (tab == 1 && fileView.value == FileView.LOCAL) mutableLibrary.update { it.copy(source = LibrarySource.FOLDER) }
    }

    fun showFileLocations() {
        ++remoteBrowseGeneration
        remoteBrowseJob?.cancel()
        mutableRemoteBrowser.value = null
        mutableFileView.value = FileView.LOCATIONS
        savedStateHandle["selected_tab"] = 1
    }

    fun showAudioPlayer(show: Boolean) { savedStateHandle["audio_player_open"] = show && playback.value.media?.isVideo == false }

    fun exitVideo() {
        if (playback.value.media?.isVideo != true) return
        stopPlayback()
    }

    fun onBackground() {
        if (playback.value.media?.isVideo == true || loadJob?.isActive == true || externalOpenJob?.isActive == true) stopPlayback() else pause()
    }

    fun stopPlayback() {
        val snapshot = playback.value
        clearSeekPreview()
        externalOpenJob?.cancel()
        loadJob?.cancel()
        setSpeedBoost(false)
        // Stop immediately; keep the queue and the browsing destination for a later resume.
        viewModelScope.launch { savePosition(snapshot) }
        engine.stop()
        savedStateHandle.remove<String>("selected_uri")
        savedStateHandle["full_screen"] = false
        savedStateHandle["audio_player_open"] = false
        mutableResume.value = 0
    }

    fun selectSource(source: LibrarySource) {
        savedStateHandle["selected_tab"] = if (source == LibrarySource.FOLDER) 1 else 0
        mutableLibrary.update { it.copy(source = source) }
        if (source == LibrarySource.FOLDER) mutableFileView.value = FileView.LOCAL
        if (source == LibrarySource.MEDIA_STORE) refreshLibrary()
        if (source == LibrarySource.FOLDER && browser.access().allFiles && library.value.folderPath.isEmpty()) browseStorage()
    }

    fun requiredPermissions() = browser.requiredPermissions()

    fun browseStorage() {
        val access = browser.access()
        mutableLibrary.update { it.copy(access = access) }
        if (!access.allFiles) return
        savedStateHandle["selected_tab"] = 1
        mutableFileView.value = FileView.LOCAL
        folderJob?.cancel()
        mutableLibrary.update { it.copy(source = LibrarySource.FOLDER, storageBrowser = true, folderPath = listOf(browser.storageRoot())) }
        browseFolder()
    }

    fun onAccessChanged() {
        val access = browser.access()
        mutableLibrary.update { it.copy(access = access) }
        if (library.value.storageBrowser) {
            if (access.allFiles) browseFolder() else mutableLibrary.update { it.copy(entries = emptyList(), browserError = "访问所有文件权限已关闭，请重新授权。") }
        }
        refreshLibrary()
    }

    fun refreshLibrary() {
        browseJob?.cancel()
        browseJob = viewModelScope.launch {
            mutableLibrary.update { it.copy(isLoading = true, access = browser.access(), browserError = null) }
            try {
                val media = browser.queryLibrary()
                val grouped = withContext(Dispatchers.Default) { MediaSeriesGrouper.group(media) }
                mutableLibrary.update { it.copy(media = media, mediaEntries = grouped) }
            } catch (error: CancellationException) { throw error }
            catch (_: Exception) { mutableLibrary.update { it.copy(browserError = "无法读取媒体库，请刷新或重新授权。") } }
            finally { mutableLibrary.update { it.copy(isLoading = false) } }
        }
    }

    fun selectTree(uri: Uri) {
        viewModelScope.launch {
            try {
                val location = browser.selectTree(uri)
                tree = uri
                savedStateHandle["selected_tab"] = 1
                mutableFileView.value = FileView.LOCAL
                mutableLibrary.update { it.copy(source = LibrarySource.FOLDER, storageBrowser = false, folderPath = listOf(location)) }
                browseFolder()
            } catch (error: CancellationException) { throw error }
            catch (_: Exception) { mutableLibrary.update { it.copy(browserError = "无法访问文件夹，请重新选择。") } }
        }
    }

    private fun restoreFolder(uri: Uri) {
        viewModelScope.launch {
            try {
                val root = browser.selectTree(uri)
                if (library.value.storageBrowser) return@launch
                mutableLibrary.update { it.copy(folderPath = listOf(root)) }
                browseFolder()
            } catch (error: CancellationException) { throw error }
            catch (_: Exception) { tree = null }
        }
    }

    fun enterFolder(entry: FolderEntry) {
        if (!entry.isDirectory) return
        mutableLibrary.update { it.copy(folderPath = it.folderPath + FolderLocation(entry.documentId, entry.name)) }
        browseFolder()
    }

    fun upFolder() {
        if (library.value.folderPath.size <= 1) return
        mutableLibrary.update { it.copy(folderPath = it.folderPath.dropLast(1)) }
        browseFolder()
    }
    fun refreshLocalFolder() { browseFolder() }

    private var folderJob: Job? = null
    private fun browseFolder() {
        val currentTree = tree
        if (!library.value.storageBrowser && currentTree == null) return
        val storage = library.value.storageBrowser
        val location = library.value.folderPath.lastOrNull() ?: return
        folderJob?.cancel()
        folderJob = viewModelScope.launch {
            mutableLibrary.update { it.copy(isLoading = true, browserError = null) }
            try {
                val entries = if (storage) browser.listStorageFolder(location.documentId)
                    else browser.listFolder(requireNotNull(currentTree), location.documentId)
                mutableLibrary.update { it.copy(entries = entries) }
            } catch (error: CancellationException) { throw error }
            catch (_: Exception) { mutableLibrary.update { it.copy(entries = emptyList(), browserError = "无法列出文件夹，授权可能已失效。") } }
            finally { mutableLibrary.update { it.copy(isLoading = false) } }
        }
    }

    fun open(uri: Uri, showPlayer: Boolean = true) {
        startPlayback(listOf(MediaItem(uri.toString(), "所选文件", null, null)), 0, showPlayer)
    }

    fun openExternal(request: ExternalMediaRequest) {
        externalOpenJob?.cancel()
        externalOpenJob = viewModelScope.launch {
            // A cold external launch must win over restoration of the old queue.
            initialized.await()
            startPlayback(listOf(MediaItem(request.uri.toString(), "所选文件", request.mimeType, null)), 0, external = true)
        }
    }

    fun playList(items: List<MediaItem>, index: Int = 0) {
        if (items.isEmpty()) return
        startPlayback(items.distinctBy { it.uri }, index)
    }

    fun playIndex(index: Int) { startPlayback(queue.value.items, index) }
    fun previous() { if (queue.value.hasPrevious) playIndex(queue.value.index - 1) }
    fun next() { if (queue.value.hasNext) playIndex(queue.value.index + 1) }

    private fun startPlayback(items: List<MediaItem>, index: Int, showPlayer: Boolean = true, explicitPosition: Long? = null, external: Boolean = false) {
        val selected = items.getOrNull(index) ?: return
        clearSeekPreview()
        val oldState = playback.value
        setSpeedBoost(false)
        loadJob?.cancel()
        loadJob = viewModelScope.launch {
            mutableLibrary.update { it.copy(openFailed = false) }
            try {
                savePosition(oldState)
                val resolved = repository.resolve(selected.uri.toUri())
                val media = resolved.copy(mimeType = if (external) mediaMimeType(resolved.mimeType) ?: mediaMimeType(selected.mimeType) else resolved.mimeType ?: selected.mimeType,
                    displayName = selected.displayName.takeUnless { it == "所选文件" } ?: resolved.displayName)
                if (external) require(media.mimeType != null) { "无法识别音视频类型，请从文件管理器选择正确的打开方式" }
                val bookmark = store.readBookmark(media.uri)
                mutableResume.value = bookmark?.positionMs ?: 0
                val updated = items.toMutableList().apply { this[index] = media }
                mutableQueue.value = PlaylistState(updated, index)
                val recent = (listOf(media) + library.value.recent.filterNot { it.uri == media.uri }).take(LocalMediaRepository.MAX_RECENT)
                repository.writeRecent(recent)
                mutableLibrary.update { it.copy(recent = recent) }
                engine.load(media, explicitPosition ?: mutableResume.value)
                savedStateHandle["selected_uri"] = media.uri
                savedStateHandle["full_screen"] = media.isVideo
                savedStateHandle["audio_player_open"] = !media.isVideo && showPlayer
            } catch (error: CancellationException) { throw error }
            catch (error: Exception) {
                messageChannel.send(if (external) when (error) {
                    is SecurityException -> "没有读取此文件的权限，请在文件管理器中重新选择本播放器打开"
                    is java.io.FileNotFoundException -> "文件不存在或无法读取，请在文件管理器中重新选择"
                    is IllegalArgumentException -> error.message ?: "无法识别音视频文件"
                    else -> "无法打开文件，请检查文件是否可读并重新选择"
                } else error.message ?: "无法打开媒体，请检查地址或文件授权")
            }
        }
    }

    fun openNetwork(address: String) = reportOperation {
        val validated = validateNetworkAddress(address, true)
        if (com.tang.player.core.mediaSourceKind(validated) in setOf(com.tang.player.core.MediaSourceKind.SMB, com.tang.player.core.MediaSourceKind.NFS)) {
            browseRemote(RemoteAddress.parse(validated).address); return@reportOperation
        }
        val path = java.net.URI(validated).path.lowercase()
        if (path.endsWith(".m3u") || path.endsWith(".m3u8")) importPlaylistNow(validated, null)
        else open(validated.toUri())
    }

    fun importPlaylist(address: String, folder: Uri? = null) = reportOperation { importPlaylistNow(address, folder) }
    private suspend fun importPlaylistNow(address: String, folder: Uri?) {
        val imported = playlists.read(address, folder)
        if (imported.needsFolder) { mutablePlaylistFolder.value = address; return }
        mutablePlaylistFolder.value = null
        appendQueue(imported.items)
        selectTab(2)
        messageChannel.send("已导入 ${imported.items.size} 项${if (imported.rejected > 0) "，跳过 ${imported.rejected} 个无效地址" else ""}")
    }
    fun dismissPlaylistFolder() { mutablePlaylistFolder.value = null }
    fun exportPlaylist(uri: Uri) = reportOperation {
        playlists.write(uri, queue.value.items)
        messageChannel.send("播放列表已保存为 M3U")
    }
    private fun appendQueue(items: List<MediaItem>) {
        mutableQueue.update { it.copy(items = (it.items + items).distinctBy { media -> media.uri }) }
    }

    fun saveLocation(name: String, address: String, id: String? = null) = reportOperation {
        val value = validateNetworkAddress(address, allowFutureSources = true)
        val bookmark = SavedBookmark(name = name.trim(), kind = BookmarkKind.LOCATION, address = value)
        bookmarksStore.save(if (id == null) bookmark else bookmark.copy(id = id))
        messageChannel.send("网络位置已保存")
    }
    fun saveNetworkLocation(name: String, address: String, id: String?, credentials: NetworkCredentials, open: Boolean) = reportOperation {
        val kind = com.tang.player.core.mediaSourceKind(address.trim())
        val value = if (kind in setOf(com.tang.player.core.MediaSourceKind.SMB, com.tang.player.core.MediaSourceKind.NFS))
            RemoteAddress.parse(address).address else validateNetworkAddress(address)
        if (open && kind == com.tang.player.core.MediaSourceKind.HTTP && id == null) { openNetwork(value); return@reportOperation }
        val bookmark = SavedBookmark(id = id ?: java.util.UUID.randomUUID().toString(), name = name.trim(), kind = BookmarkKind.LOCATION, address = value)
        require(bookmark.name.isNotBlank() && bookmark.name.length <= 200) { "书签名称须为 1～200 字" }
        require(bookmarksStore.items.value.any { it.id == bookmark.id } || bookmarksStore.items.value.size < 200) { "最多保存 200 个书签" }
        withContext(Dispatchers.IO) {
            if (kind in setOf(com.tang.player.core.MediaSourceKind.SMB, com.tang.player.core.MediaSourceKind.NFS)) {
                val vault = requireNotNull(network) { "网络来源未配置" }.credentials
                val old = runCatching { vault.read(bookmark.id) }.getOrNull()
                val auth = if (kind == com.tang.player.core.MediaSourceKind.SMB && credentials.guest)
                    credentials.copy(username = "", password = "", domain = "") else credentials
                vault.save(NetworkProfile(bookmark.id, value, auth))
                try { bookmarksStore.save(bookmark) }
                catch (error: Exception) {
                    runCatching { if (old == null) vault.remove(bookmark.id) else vault.save(old) }; throw error
                }
            } else { bookmarksStore.save(bookmark); network?.credentials?.remove(bookmark.id) }
        }
        if (open) openNetwork(value) else messageChannel.send("网络位置已保存")
    }
    private fun browseRemote(address: String, root: String = address) {
        remoteBrowseJob?.cancel()
        val generation = ++remoteBrowseGeneration
        val request = RemoteBrowserState(root, address)
        mutableRemoteBrowser.value = request
        mutableFileView.value = FileView.NETWORK
        selectTab(1)
        remoteBrowseJob = viewModelScope.launch {
            try {
                val entries = repository.list(address)
                if (remoteBrowseGeneration == generation) mutableRemoteBrowser.value = request.copy(entries = entries, loading = false)
            } catch (error: CancellationException) { throw error }
            catch (error: Exception) {
                if (remoteBrowseGeneration == generation) mutableRemoteBrowser.value = request.copy(loading = false,
                    error = error.message ?: "无法读取网络目录",
                    networkRestricted = (error as? RemoteAccessException)?.kind == NetworkFailureKind.SYSTEM_BLOCKED)
            }
        }
    }
    fun refreshRemote() { remoteBrowser.value?.let { browseRemote(it.current, it.root) } }
    fun upRemote() {
        val state = remoteBrowser.value ?: return
        if (state.current == state.root) showFileLocations()
        else browseRemote(RemoteAddress.parse(state.current).parent(), state.root)
    }
    fun openRemoteEntry(entry: SourceEntry) {
        val state = remoteBrowser.value ?: return
        if (entry.directory) { browseRemote(entry.address, state.root); return }
        if (entry.name.endsWith(".m3u", true) || entry.name.endsWith(".m3u8", true)) { importPlaylist(entry.address); return }
        val ordered = sortBrowseItems(state.entries, preferences.value.fileSort, preferences.value.fileSortDescending,
            { it.name }, { it.directory }, { it.media?.sizeBytes })
        val items = ordered.filter { !it.directory && !it.name.endsWith(".m3u", true) && !it.name.endsWith(".m3u8", true) }.mapNotNull { it.media }
        val index = items.indexOfFirst { it.uri == entry.address }
        if (index >= 0) playList(items, index)
    }
    fun savePositionBookmark(name: String) {
        val state = playback.value
        val media = state.media ?: return
        if (!state.canControl || !state.seekable) return
        reportOperation {
            bookmarksStore.save(SavedBookmark(name = name.trim(), kind = BookmarkKind.POSITION, address = media.uri,
                positionMs = state.positionMs, items = listOf(media)))
            messageChannel.send("已保存播放位置书签")
        }
    }
    fun savePlaylistBookmark(name: String) {
        val items = queue.value.items
        if (items.isEmpty()) return
        reportOperation { bookmarksStore.save(SavedBookmark(name = name.trim(), kind = BookmarkKind.PLAYLIST, items = items)); messageChannel.send("播放列表书签已保存") }
    }
    fun removeBookmark(id: String) = reportOperation {
        bookmarksStore.remove(id)
        withContext(Dispatchers.IO) { network?.credentials?.remove(id) }
    }
    fun renameBookmark(bookmark: SavedBookmark, name: String) = reportOperation {
        bookmarksStore.save(bookmark.copy(name = name.trim()))
        messageChannel.send("书签名称已更新")
    }
    fun currentFolderName(): String = if (fileView.value == FileView.NETWORK)
        remoteBrowser.value?.current?.let { RemoteAddress.parse(it).parts.lastOrNull() }.orEmpty()
        else library.value.folderPath.lastOrNull()?.name.orEmpty()

    fun saveCurrentFolderBookmark(name: String) {
        val remote = if (fileView.value == FileView.NETWORK) remoteBrowser.value else null
        val local = library.value
        val treeUri = tree
        reportOperation {
            val location = local.folderPath.lastOrNull()
            val address = remote?.current ?: if (local.storageBrowser) android.net.Uri.fromFile(java.io.File(requireNotNull(location).documentId)).toString()
                else requireNotNull(treeUri) { "请先选择文件夹" }.toString()
            val bookmark = SavedBookmark(name = name.trim(), kind = BookmarkKind.FOLDER, address = address,
                folderDocumentId = if (remote == null && !local.storageBrowser) requireNotNull(location).documentId else "")
            withContext(Dispatchers.IO) {
                val repository = network
                if (remote != null && repository != null) {
                    val profile = repository.profileFor(remote.current)
                    repository.credentials.save(profile.copy(id = bookmark.id))
                    try { bookmarksStore.save(bookmark) }
                    catch (error: Exception) { runCatching { repository.credentials.remove(bookmark.id) }; throw error }
                } else bookmarksStore.save(bookmark)
            }
            messageChannel.send("目录书签已保存到文件页")
        }
    }

    private fun openFolderBookmark(bookmark: SavedBookmark) = reportOperation {
        val uri = bookmark.address.toUri()
        if (uri.scheme in setOf("smb", "nfs")) { openNetwork(bookmark.address); return@reportOperation }
        folderJob?.cancel()
        val path = if (uri.scheme == "file") {
            require(browser.access().allFiles) { "请先在设置中开启访问所有文件权限" }
            val root = browser.storageRoot()
            val folder = withContext(Dispatchers.IO) { java.io.File(java.net.URI(bookmark.address)).canonicalFile }
            require(folder.path == root.documentId || folder.path.startsWith(root.documentId + java.io.File.separator)) { "此目录不在共享存储中" }
            require(withContext(Dispatchers.IO) { folder.isDirectory }) { "目录已移动或删除" }
            listOf(root) + generateSequence(folder) { it.parentFile }
                .takeWhile { it.path != root.documentId }.map { FolderLocation(it.path, it.name) }.toList().asReversed()
        } else {
            require(uri.scheme == "content") { "不支持的目录书签" }
            val root = browser.selectTree(uri)
            val target = bookmark.folderDocumentId.ifEmpty { root.documentId }
            browser.listFolder(uri, target) // Verify the retained grant before switching destinations.
            tree = uri
            listOf(root) + if (target == root.documentId) emptyList() else listOf(FolderLocation(target, bookmark.name))
        }
        mutableLibrary.update { it.copy(source = LibrarySource.FOLDER, storageBrowser = uri.scheme == "file", folderPath = path) }
        mutableFileView.value = FileView.LOCAL
        selectTab(1)
        browseFolder()
    }
    fun openBookmark(bookmark: SavedBookmark) {
        when (bookmark.kind) {
            BookmarkKind.LOCATION -> openNetwork(bookmark.address)
            BookmarkKind.FOLDER -> openFolderBookmark(bookmark)
            BookmarkKind.POSITION -> startPlayback(bookmark.items, 0, explicitPosition = bookmark.positionMs)
            BookmarkKind.PLAYLIST -> { appendQueue(bookmark.items); selectTab(2) }
        }
    }

    fun markClipStart() {
        val state = playback.value
        if (state.media?.isVideo != true || !state.canControl || !state.seekable || clip.value.exporting) return
        clipPreparation?.cancel()
        mutableClip.value = ClipSelection(state.media, state.positionMs)
    }
    fun markClipEnd() {
        val state = playback.value
        val selection = clip.value
        if (selection.media?.uri != state.media?.uri || selection.startMs == null || selection.exporting) return
        if (state.positionMs <= selection.startMs) { reportOperation { error("终点必须晚于起点") }; return }
        clipPreparation?.cancel()
        val selected = selection.copy(endMs = state.positionMs, preparing = true, actualStartMs = null)
        mutableClip.value = selected
        clipPreparation = reportOperation {
            try {
                val preview = clips.prepare(ClipRange(requireNotNull(selected.media), requireNotNull(selected.startMs), requireNotNull(selected.endMs)))
                if (clip.value == selected) mutableClip.value = selected.copy(preparing = false, actualStartMs = preview.actualStartMs)
            } finally { if (clip.value == selected) mutableClip.value = selected.copy(preparing = false) }
        }
    }
    fun cancelClip() { if (!clip.value.exporting) { clipPreparation?.cancel(); mutableClip.value = ClipSelection() } }
    fun exportClip(uri: Uri) {
        val selection = clip.value
        val media = selection.media ?: return
        val start = selection.startMs ?: return
        val end = selection.endMs ?: return
        if (selection.exporting || selection.preparing || selection.actualStartMs == null) return
        mutableClip.value = selection.copy(exporting = true)
        reportOperation {
            try {
                val result = clips.export(ClipRange(media, start, end), uri)
                messageChannel.send("片段已保存（原速 ${com.tang.player.ui.components.formatTime(result.actualStartMs)}～${com.tang.player.ui.components.formatTime(result.endMs)}）")
            } finally { mutableClip.value = ClipSelection() }
        }
    }
    private fun reportOperation(operation: suspend () -> Unit): Job = viewModelScope.launch {
        try { operation() } catch (error: CancellationException) { throw error }
        catch (error: Exception) { messageChannel.send(error.message ?: "操作失败，请重试") }
    }

    fun appendFiles(uris: List<Uri>) {
        viewModelScope.launch {
            val added = uris.mapNotNull { uri ->
                try { repository.resolve(uri) } catch (error: CancellationException) { throw error }
                catch (_: Exception) { mutableLibrary.update { it.copy(openFailed = true) }; null }
            }
            if (queue.value.items.isEmpty()) playList(added)
            else mutableQueue.update { it.copy(items = (it.items + added).distinctBy { media -> media.uri }) }
        }
    }

    fun removeFromQueue(index: Int) {
        val previous = queue.value
        if (index !in previous.items.indices) return
        val items = previous.items.toMutableList().apply { removeAt(index) }
        val current = if (index < previous.index) previous.index - 1 else previous.index.coerceAtMost(items.lastIndex)
        mutableQueue.value = PlaylistState(items, current)
        if (index == previous.index) {
            if (items.isEmpty()) {
                stopPlayback()
            } else playIndex(current)
        }
    }

    fun moveQueueItem(index: Int, delta: Int) {
        val value = queue.value
        val target = index + delta
        if (index !in value.items.indices || target !in value.items.indices) return
        val selectedUri = value.current?.uri
        val items = value.items.toMutableList().apply { add(target, removeAt(index)) }
        mutableQueue.value = PlaylistState(items, items.indexOfFirst { it.uri == selectedUri })
    }

    fun setSpeed(value: Double) {
        mutableSpeed.value = normalizeSpeed(value)
        engine.setSpeed(if (speedBoost.value) boostedSpeed(baseSpeed.value) else baseSpeed.value)
        if (preferences.value.rememberSpeed) mutablePreferences.update { it.copy(lastSpeed = baseSpeed.value) }
    }

    fun setRememberSpeed(enabled: Boolean) {
        mutablePreferences.update { it.copy(rememberSpeed = enabled, lastSpeed = if (enabled) baseSpeed.value else 1.0) }
    }

    fun setAspect(aspect: VideoAspect) {
        mutablePreferences.update { it.copy(aspect = aspect) }
        engine.setVideoAspectRatio(aspect.ratio)
    }

    fun setVideoOrientation(orientation: VideoOrientation) {
        mutablePreferences.update { it.copy(orientation = orientation) }
    }

    fun setAutoNext(enabled: Boolean) { mutablePreferences.update { it.copy(autoNext = enabled) } }

    fun setGroupMedia(enabled: Boolean) { mutablePreferences.update { it.copy(groupMedia = enabled) } }

    fun setSkipSeconds(seconds: Int) {
        if (seconds > 0) mutablePreferences.update { it.copy(skipSeconds = seconds) }
    }

    fun skipSegment() {
        val state = playback.value
        if (state.media?.isVideo == true) seekTo(state.positionMs + preferences.value.skipSeconds.toLong() * 1000)
    }

    fun setSpeedBoost(enabled: Boolean) {
        val boost = enabled && playback.value.media?.isVideo == true && playback.value.status == PlaybackStatus.PLAYING
        if (mutableBoost.value == boost) return
        mutableBoost.value = boost
        engine.setSpeed(if (boost) boostedSpeed(baseSpeed.value) else baseSpeed.value)
    }

    fun dismissError() { mutableLibrary.update { it.copy(openFailed = false) } }
    fun clearRecent() {
        viewModelScope.launch {
            repository.writeRecent(emptyList())
            mutableLibrary.update { it.copy(recent = emptyList()) }
        }
    }

    fun togglePlayback() {
        if (!playback.value.canControl) return
        if (playback.value.status == PlaybackStatus.PLAYING) pause() else engine.play()
    }
    fun pause() { setSpeedBoost(false); engine.pause() }
    fun setFileSort(sort: BrowseSort, descending: Boolean) {
        mutablePreferences.update { it.copy(fileSort = sort, fileSortDescending = descending) }
    }
    fun beginSeekPreview() {
        val state = playback.value
        val media = state.media ?: return
        if (seekPreview.value != null || !media.isVideo || !state.canControl || !state.seekable || state.durationMs <= 0) return
        setSpeedBoost(false)
        mutableSeekPreview.value = VideoSeekPreview(media.uri, state.positionMs, state.positionMs, state.status == PlaybackStatus.PLAYING)
        if (state.status == PlaybackStatus.PLAYING) engine.pause()
    }
    fun updateSeekPreview(positionMs: Long, cancelled: Boolean) {
        mutableSeekPreview.update { it?.copy(targetPositionMs = positionMs.coerceIn(0, playback.value.durationMs.coerceAtLeast(0)), cancelled = cancelled) }
    }
    fun finishSeekPreview(commit: Boolean) {
        val preview = seekPreview.value ?: return
        clearSeekPreview()
        if (playback.value.media?.uri != preview.mediaUri || !playback.value.canControl) return
        if (commit && !preview.cancelled) seekTo(preview.targetPositionMs)
        if (preview.wasPlaying) engine.play()
    }
    private fun clearSeekPreview() { mutableSeekPreview.value = null; mutableSeekThumbnail.value = null }
    fun retry() { if (queue.value.index >= 0) playIndex(queue.value.index) }
    fun attachSurface(surface: android.view.Surface?) = engine.attachSurface(surface)
    fun updateSurfaceSize(width: Int, height: Int) = engine.updateSurfaceSize(width, height)
    fun seekTo(positionMs: Long) {
        if (playback.value.canControl && playback.value.seekable) engine.seekTo(positionMs.coerceIn(0, playback.value.durationMs.coerceAtLeast(0)))
    }
    fun resumeLastPosition() = seekTo(resumePosition.value)

    private suspend fun savePosition(state: PlaybackState) {
        val media = state.media ?: return
        if (state.durationMs <= 0 || !state.canControl || !state.seekable) return
        val position = if (state.status == PlaybackStatus.ENDED || state.durationMs - state.positionMs < 1500) 0L else state.positionMs
        store.writeBookmark(media.uri, position, state.durationMs)
    }

    override fun onCleared() {
        val snapshot = playback.value
        CoroutineScope(Dispatchers.IO).launch { savePosition(snapshot) }
        engine.release()
        super.onCleared()
    }
}
