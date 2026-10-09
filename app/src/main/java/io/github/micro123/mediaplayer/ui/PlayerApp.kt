package io.github.micro123.mediaplayer.ui

import android.content.Intent
import android.os.Build
import android.provider.Settings
import android.content.ActivityNotFoundException
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat
import kotlinx.coroutines.launch
import androidx.activity.compose.BackHandler
import androidx.activity.compose.LocalActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.platform.LocalView
import androidx.lifecycle.Lifecycle
import androidx.core.net.toUri
import androidx.core.content.edit
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.micro123.mediaplayer.AppContainer
import io.github.micro123.mediaplayer.MainActivity
import io.github.micro123.mediaplayer.core.PlaybackStatus
import io.github.micro123.mediaplayer.data.VideoOrientation
import io.github.micro123.mediaplayer.data.SavedBookmark
import io.github.micro123.mediaplayer.ui.components.*
import io.github.micro123.mediaplayer.ui.screens.*
import io.github.micro123.mediaplayer.ui.theme.VideoPlayerTheme

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PlayerApp(container: AppContainer) {
    val viewModel = remember(container) { container.player }
    val activity = LocalActivity.current as? MainActivity
    val library by viewModel.library.collectAsStateWithLifecycle()
    val playback by viewModel.playback.collectAsStateWithLifecycle()
    val audioMetadata by viewModel.audioMetadata.collectAsStateWithLifecycle()
    val seekPreview by viewModel.seekPreview.collectAsStateWithLifecycle()
    val seekThumbnail by viewModel.seekThumbnail.collectAsStateWithLifecycle()
    val selectedTab by viewModel.selectedTab.collectAsStateWithLifecycle()
    val audioPlayerOpen by viewModel.audioPlayerOpen.collectAsStateWithLifecycle()
    val queue by viewModel.queue.collectAsStateWithLifecycle()
    val lastPlayback by viewModel.lastPlayback.collectAsStateWithLifecycle()
    val preferences by viewModel.preferences.collectAsStateWithLifecycle()
    val updateState by container.updates.state.collectAsStateWithLifecycle()
    val updateDownloadState by container.updateDownloads.state.collectAsStateWithLifecycle()
    val speed by viewModel.baseSpeed.collectAsStateWithLifecycle()
    val boost by viewModel.speedBoost.collectAsStateWithLifecycle()
    val resumePosition by viewModel.resumePosition.collectAsStateWithLifecycle()
    val bookmarks by viewModel.bookmarks.collectAsStateWithLifecycle()
    val remoteBrowser by viewModel.remoteBrowser.collectAsStateWithLifecycle()
    val fileView by viewModel.fileView.collectAsStateWithLifecycle()
    val clip by viewModel.clip.collectAsStateWithLifecycle()
    val playlistFolderRequest by viewModel.playlistFolderRequest.collectAsStateWithLifecycle()
    val pipState by activity?.inPip?.collectAsState() ?: remember { mutableStateOf(false) }
    val pipTransition by activity?.enteringPip?.collectAsState() ?: remember { mutableStateOf(false) }
    val inPip = pipState || pipTransition
    val isVideo = playback.media?.isVideo == true
    val videoRatio = if (preferences.aspect.ratio > 0) preferences.aspect.ratio else playback.videoAspectRatio
    val configuration = LocalConfiguration.current
    val browsingState = rememberSaveableStateHolder()
    var showSpeed by rememberSaveable { mutableStateOf(false) }
    var showAspect by rememberSaveable { mutableStateOf(false) }
    var showQueue by rememberSaveable { mutableStateOf(false) }
    var showVideoSettings by rememberSaveable { mutableStateOf(false) }
    var showStorageAccess by rememberSaveable { mutableStateOf(false) }
    var showNetwork by rememberSaveable { mutableStateOf(false) }
    var editingLocation by remember { mutableStateOf<SavedBookmark?>(null) }
    var editingCredentials by remember { mutableStateOf<io.github.micro123.mediaplayer.data.network.NetworkCredentials?>(null) }
    LaunchedEffect(showNetwork, editingLocation?.id) {
        editingCredentials = null
        if (showNetwork) editingLocation?.let { bookmark ->
            try {
                editingCredentials = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                    container.network.credentials.read(bookmark.id)?.credentials ?: io.github.micro123.mediaplayer.data.network.NetworkCredentials()
                }
            } catch (_: Exception) {
                // Show a fresh form when the old device key no longer decrypts a profile.
                editingCredentials = io.github.micro123.mediaplayer.data.network.NetworkCredentials()
            }
        }
    }
    var bookmarkDialog by rememberSaveable { mutableStateOf<String?>(null) }
    var renamingFolder by remember { mutableStateOf<SavedBookmark?>(null) }
    var showClip by rememberSaveable { mutableStateOf(false) }
    DisposableEffect(activity, viewModel) {
        activity?.bindPlayer(viewModel)
        onDispose { activity?.bindPlayer(null) }
    }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { viewModel.onAccessChanged() }
    LaunchedEffect(activity, isVideo, playback.canControl, playback.status, videoRatio, preferences.autoPip) {
        activity?.updatePlaybackPresentation(isVideo && playback.canControl, playback.status == PlaybackStatus.PLAYING, videoRatio, preferences.autoPip)
    }
    BackHandler(enabled = !isVideo && !audioPlayerOpen && selectedTab != 0) { viewModel.selectTab(0) }
    BackHandler(enabled = !isVideo && !audioPlayerOpen && selectedTab == 1 && fileView == FileView.LOCAL) {
        if (library.folderPath.size > 1) viewModel.upFolder() else viewModel.showFileLocations()
    }
    BackHandler(enabled = !isVideo && !audioPlayerOpen && selectedTab == 1 && fileView == FileView.NETWORK) { viewModel.upRemote() }
    BackHandler(enabled = audioPlayerOpen && !isVideo) { viewModel.showAudioPlayer(false) }
    BackHandler(enabled = isVideo && !inPip) { viewModel.exitVideo() }
    LaunchedEffect(isVideo) {
        if (!isVideo) { showVideoSettings = false; showAspect = false; showSpeed = false }
    }
    val view = LocalView.current
    DisposableEffect(view, isVideo, playback.status) {
        view.keepScreenOn = isVideo && playback.status == PlaybackStatus.PLAYING
        onDispose { view.keepScreenOn = false }
    }
    val snackbar = remember { SnackbarHostState() }
    val uiScope = rememberCoroutineScope()
    var pendingNetworkAction by remember { mutableStateOf<(() -> Unit)?>(null) }
    val localNetworkPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        val pending = pendingNetworkAction
        pendingNetworkAction = null
        if (granted) pending?.invoke() else uiScope.launch { snackbar.showSnackbar("未授权本地网络访问，无法连接局域网媒体") }
    }
    val withNetworkAccess: (() -> Unit) -> Unit = { action ->
        if (Build.VERSION.SDK_INT >= 37 && activity != null &&
            ContextCompat.checkSelfPermission(activity, "android.permission.ACCESS_LOCAL_NETWORK") != PackageManager.PERMISSION_GRANTED) {
            pendingNetworkAction = action
            localNetworkPermission.launch("android.permission.ACCESS_LOCAL_NETWORK")
        } else action()
    }
    val openSavedBookmark: (SavedBookmark) -> Unit = { bookmark ->
        if (io.github.micro123.mediaplayer.core.mediaSourceKind(bookmark.address) in setOf(io.github.micro123.mediaplayer.core.MediaSourceKind.HTTP, io.github.micro123.mediaplayer.core.MediaSourceKind.SMB, io.github.micro123.mediaplayer.core.MediaSourceKind.NFS, io.github.micro123.mediaplayer.core.MediaSourceKind.NAVIDROME) || bookmark.items.any { it.sourceKind != io.github.micro123.mediaplayer.core.MediaSourceKind.LOCAL }) {
            withNetworkAccess { viewModel.openBookmark(bookmark) }
        } else viewModel.openBookmark(bookmark)
    }
    val selectMedia: (List<io.github.micro123.mediaplayer.core.MediaItem>, Int) -> Unit = { items, index ->
        if (items.any { it.sourceKind != io.github.micro123.mediaplayer.core.MediaSourceKind.LOCAL }) withNetworkAccess { viewModel.playList(items, index) }
        else viewModel.playList(items, index)
    }
    val selectQueueItem: (Int) -> Unit = { index -> selectMedia(queue.items, index) }
    val editBookmark: (SavedBookmark) -> Unit = {
        if (it.kind == io.github.micro123.mediaplayer.data.BookmarkKind.FOLDER) renamingFolder = it
        else { editingLocation = it; showNetwork = true }
    }
    LaunchedEffect(viewModel) { viewModel.messages.collect { snackbar.showSnackbar(it) } }
    LaunchedEffect(library.openFailed) {
        if (library.openFailed) { snackbar.showSnackbar("无法读取文件，请重新选择或调整授权。"); viewModel.dismissError() }
    }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { it?.let(viewModel::open) }
    val multiplePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { if (it.isNotEmpty()) viewModel.appendFiles(it) }
    val folderPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { it?.let(viewModel::selectTree) }
    val playlistPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { it?.let { viewModel.importPlaylist(it.toString()) } }
    val playlistWriter = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("audio/x-mpegurl")) { it?.let(viewModel::exportPlaylist) }
    val playlistRootPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        val address = playlistFolderRequest
        if (uri != null && address != null) viewModel.importPlaylist(address, uri) else viewModel.dismissPlaylistFolder()
    }
    val clipWriter = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("video/mp4")) {
        if (it != null) viewModel.exportClip(it) else showClip = true
    }
    val accessPicker = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { viewModel.refreshLibrary() }
    val allFilesPicker = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { viewModel.onAccessChanged() }
    val requestAllFiles = {
        if (Build.VERSION.SDK_INT >= 30) {
            try {
                allFilesPicker.launch(Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, "package:${activity?.packageName}".toUri()))
            } catch (_: ActivityNotFoundException) {
                allFilesPicker.launch(Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION))
            }
        } else accessPicker.launch(viewModel.requiredPermissions())
    }
    LaunchedEffect(activity) {
        val prompts = activity?.getSharedPreferences("permission_prompts", android.content.Context.MODE_PRIVATE)
        if (activity?.openedFromExternal != true && !container.mediaBrowser.access().allFiles && prompts?.getBoolean("all_files_explained", false) != true) showStorageAccess = true
    }
    val dismissStorageAccess = {
        showStorageAccess = false
        activity?.getSharedPreferences("permission_prompts", android.content.Context.MODE_PRIVATE)?.edit { putBoolean("all_files_explained", true) }
        Unit
    }
    val openFile = { picker.launch(arrayOf("audio/*", "video/*")) }
    val addFiles = { multiplePicker.launch(arrayOf("audio/*", "video/*")) }
    val importPlaylist = { playlistPicker.launch(arrayOf("audio/*", "application/*", "text/*")) }
    val videoContent: @Composable (Modifier) -> Unit = { modifier ->
        VideoPlayerTheme {
        VideoPlayerScreen(playback, queue, preferences.aspect, speed, boost, preferences.orientation, inPip, resumePosition,
            viewModel::togglePlayback, viewModel::seekTo, viewModel::attachSurface, viewModel::updateSurfaceSize,
            viewModel::setSpeedBoost, {
                viewModel.setVideoOrientation(if (configuration.orientation == android.content.res.Configuration.ORIENTATION_PORTRAIT)
                    VideoOrientation.LANDSCAPE else VideoOrientation.PORTRAIT)
            }, { activity?.requestPip() }, { showSpeed = true },
            { showAspect = true }, { showQueue = true }, viewModel::previous, viewModel::next,
            viewModel::resumeLastPosition, viewModel::retry, preferences.skipSeconds, viewModel::skipSegment,
            viewModel::exitVideo,
            { showVideoSettings = true }, modifier,
            onClip = {
                if (clip.startMs != null && clip.endMs == null) {
                    viewModel.markClipEnd()
                    if (viewModel.clip.value.endMs != null) showClip = true
                } else viewModel.markClipStart()
            }, clipStartMs = clip.startMs, clipEndMs = clip.endMs,
            seekPreview = seekPreview, seekThumbnail = seekThumbnail, onSeekBegin = viewModel::beginSeekPreview,
            onSeekUpdate = viewModel::updateSeekPreview, onSeekFinish = viewModel::finishSeekPreview)
        }
    }
    val audioContent: @Composable (Modifier) -> Unit = { modifier ->
        MusicPlayerScreen(playback, audioMetadata, queue, viewModel::togglePlayback, viewModel::seekTo,
            viewModel::previous, viewModel::next, { showQueue = true }, { showSpeed = true }, openFile, viewModel::retry,
            resumePosition, viewModel::resumeLastPosition, modifier)
    }
    Box(Modifier.fillMaxSize()) {
        if (isVideo) videoContent(Modifier.fillMaxSize())
        else if (audioPlayerOpen && playback.media != null) Scaffold(topBar = {
            TopAppBar(title = { Text("正在播放音乐") }, navigationIcon = {
                IconButton(onClick = { viewModel.showAudioPlayer(false) }) { PlayerSymbol(PlayerIcon.BACK, description = "返回浏览") }
            }, actions = { TextButton(onClick = viewModel::stopPlayback) { Text("停止") } })
        }) { padding -> audioContent(Modifier.padding(padding)) }
        else Scaffold(snackbarHost = { SnackbarHost(snackbar) }, bottomBar = {
            Column {
            if (playback.media != null) AudioMiniPlayer(playback, { viewModel.showAudioPlayer(true) }, viewModel::togglePlayback, viewModel::stopPlayback, audioMetadata)
            NavigationBar {
                listOf(Triple(0, "媒体库", PlayerIcon.VIDEO), Triple(1, "文件", PlayerIcon.FOLDER),
                    Triple(2, "播放列表", PlayerIcon.PLAYLIST), Triple(4, "书签", PlayerIcon.BOOKMARK),
                    Triple(3, "设置", PlayerIcon.SETTINGS)).forEach { (index, label, icon) ->
                    NavigationBarItem(selected = selectedTab == index, onClick = { viewModel.selectTab(index) }, icon = { PlayerSymbol(icon) }, label = { Text(label) })
                }
            }
            }
        }) { padding ->
            val modifier = Modifier.padding(padding)
            when (selectedTab) {
                0 -> browsingState.SaveableStateProvider("browse-0") {
                    LibraryScreen(library, openFile, selectMedia, viewModel::selectSource,
                    { accessPicker.launch(viewModel.requiredPermissions()) }, {
                        if (container.mediaBrowser.access().allFiles) viewModel.browseStorage() else showStorageAccess = true
                    },
                    viewModel::enterFolder, viewModel::upFolder, viewModel::refreshLibrary, addFiles, { viewModel.selectTab(2) },
                    preferences.groupMedia, viewModel::setGroupMedia, modifier,
                    onNetwork = { editingLocation = null; showNetwork = true }, onImportPlaylist = importPlaylist,
                    onBookmarks = { viewModel.selectTab(4) }, lastPlayback = lastPlayback, onReplay = {
                        if (lastPlayback?.items?.any { it.sourceKind != io.github.micro123.mediaplayer.core.MediaSourceKind.LOCAL } == true)
                            withNetworkAccess { viewModel.replayLastPlayback() } else viewModel.replayLastPlayback()
                    })
                }
                1 -> when (fileView) {
                    FileView.LOCATIONS -> FilesScreen(bookmarks, {
                        if (container.mediaBrowser.access().allFiles) viewModel.browseStorage() else showStorageAccess = true
                    }, { folderPicker.launch(null) }, { editingLocation = null; showNetwork = true },
                        openSavedBookmark, editBookmark, viewModel::removeBookmark, modifier)
                    FileView.LOCAL -> browsingState.SaveableStateProvider("browse-local") {
                        LibraryScreen(library, openFile, viewModel::playLocalDirectory, viewModel::selectSource,
                            { accessPicker.launch(viewModel.requiredPermissions()) }, {
                                if (container.mediaBrowser.access().allFiles) viewModel.browseStorage() else showStorageAccess = true
                            }, viewModel::enterFolder, viewModel::upFolder, viewModel::refreshLocalFolder, addFiles,
                            { viewModel.selectTab(2) }, preferences.groupMedia, viewModel::setGroupMedia, modifier,
                            onNetwork = { editingLocation = null; showNetwork = true }, onImportPlaylist = importPlaylist,
                            onBookmarks = { viewModel.selectTab(4) }, onFileRoot = viewModel::showFileLocations,
                            onSaveFolder = { bookmarkDialog = "folder" }, fileSort = preferences.fileSort,
                            fileSortDescending = preferences.fileSortDescending, onFileSort = viewModel::setFileSort)
                    }
                    FileView.NETWORK -> remoteBrowser?.let { state ->
                        val musicServer = io.github.micro123.mediaplayer.core.mediaSourceKind(state.current) == io.github.micro123.mediaplayer.core.MediaSourceKind.NAVIDROME
                        browsingState.SaveableStateProvider(if (musicServer) "navidrome-${state.root}" else "remote-${state.current}") {
                            if (musicServer) NavidromeBrowserScreen(state, { entry -> withNetworkAccess { viewModel.openRemoteEntry(entry) } },
                                viewModel::upRemote, { withNetworkAccess { viewModel.refreshRemote() } },
                                { query -> withNetworkAccess { viewModel.searchNavidrome(query) } },
                                { withNetworkAccess { viewModel.playRemoteAll() } }, { bookmarkDialog = "folder" }, {
                                    activity?.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, "package:${activity.packageName}".toUri()))
                                }, modifier, onCancelQueue = viewModel::cancelRemoteQueue) else
                            NetworkBrowserScreen(state, { entry -> withNetworkAccess { viewModel.openRemoteEntry(entry) } },
                                viewModel::upRemote, { withNetworkAccess { viewModel.refreshRemote() } }, modifier,
                                fileSort = preferences.fileSort, fileSortDescending = preferences.fileSortDescending, onFileSort = viewModel::setFileSort,
                                onBookmark = { bookmarkDialog = "folder" }, onNetworkSettings = {
                                    activity?.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, "package:${activity.packageName}".toUri()))
                                })
                        }
                    }
                }
                2 -> PlaylistContent(queue, preferences.autoNext, viewModel::setAutoNext, selectQueueItem,
                    viewModel::removeFromQueue, viewModel::moveQueueItem, addFiles, modifier.fillMaxSize().padding(20.dp),
                    onImport = importPlaylist, onExport = { playlistWriter.launch("媒体播放器-播放列表.m3u") },
                    onSaveBookmark = { bookmarkDialog = "playlist" }, onPreview = container.queuePreviews::read)
                4 -> BookmarksScreen(bookmarks, openSavedBookmark, viewModel::removeBookmark,
                    { editingLocation = null; showNetwork = true }, editBookmark, modifier)
                else -> SettingsScreen(preferences, speed, viewModel::setSpeed, viewModel::setRememberSpeed,
                    viewModel::setAspect, viewModel::setAutoNext, library.recent.isNotEmpty(), viewModel::clearRecent,
                    library.access.allFiles, { showStorageAccess = true }, viewModel::setSkipSeconds, viewModel::setVideoOrientation, modifier,
                    onAutoPip = viewModel::setAutoPip, onBackgroundVideo = viewModel::setBackgroundVideo,
                    updateState = updateState, onCheckUpdate = container.updates::checkNow,
                    downloadState = updateDownloadState, downloads = container.updateDownloads)
            }
        }
        if (isVideo && !inPip) SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter))
    }
    if (!inPip) {
        if (showNetwork) NetworkLocationDialog(editingLocation, editingCredentials,
            { name, address, id, credentials, open ->
                val action = { viewModel.saveNetworkLocation(name, address, id, credentials, open); Unit }
                if (open) withNetworkAccess(action) else action()
            }, { showNetwork = false; editingLocation = null })
        bookmarkDialog?.let { kind -> BookmarkNameDialog(when (kind) { "position" -> "保存播放位置"; "folder" -> "收藏当前目录"; else -> "保存播放列表" },
            when (kind) { "position" -> "${playback.media?.displayName.orEmpty()} ${formatTime(playback.positionMs)}"; "folder" -> viewModel.currentFolderName(); else -> "我的播放列表" },
            { when (kind) { "position" -> viewModel.savePositionBookmark(it); "folder" -> viewModel.saveCurrentFolderBookmark(it); else -> viewModel.savePlaylistBookmark(it) } }, { bookmarkDialog = null }) }
        renamingFolder?.let { bookmark -> BookmarkNameDialog("重命名目录书签", bookmark.name,
            { viewModel.renameBookmark(bookmark, it) }, { renamingFolder = null }) }
        if (playlistFolderRequest != null) AlertDialog(onDismissRequest = viewModel::dismissPlaylistFolder,
            title = { Text("选择播放列表所在文件夹") },
            text = { Text("此 M3U 使用相对路径。请选择它所在的文件夹，以便读取其中的音视频；安卓文档地址不能直接拼接文件路径。") },
            confirmButton = { TextButton(onClick = { playlistRootPicker.launch(null) }) { Text("选择文件夹") } },
            dismissButton = { TextButton(onClick = viewModel::dismissPlaylistFolder) { Text("取消") } })
        if (showStorageAccess) AlertDialog(onDismissRequest = dismissStorageAccess,
            title = { Text("访问所有文件") },
            text = { Text("开启后可直接浏览共享存储中的文件夹和音视频文件，无需逐个授权文件夹。安卓仍会限制其他应用的私有目录。你也可以暂不开启，仅授权媒体库或一个文件夹。") },
            confirmButton = { TextButton(onClick = { dismissStorageAccess(); requestAllFiles() }) { Text("前往授权") } },
            dismissButton = { Row {
                TextButton(onClick = { dismissStorageAccess(); folderPicker.launch(null) }) { Text("只选文件夹") }
                TextButton(onClick = dismissStorageAccess) { Text("稍后") }
            } })
        val playbackOverlays: @Composable () -> Unit = {
            if (showClip) ClipDialog(clip, playback, viewModel::markClipStart, viewModel::markClipEnd,
                { showClip = false; clipWriter.launch("媒体播放器-录制-${System.currentTimeMillis()}.mp4") },
                viewModel::cancelClip, { showClip = false })
            if (showSpeed) SpeedDialog(speed, preferences, viewModel::setSpeed, viewModel::setRememberSpeed) { showSpeed = false }
            if (showAspect) AspectDialog(preferences.aspect, viewModel::setAspect) { showAspect = false }
            if (showQueue) PlaylistSheet(queue, preferences.autoNext, viewModel::setAutoNext,
                { selectQueueItem(it); showQueue = false }, viewModel::removeFromQueue, viewModel::moveQueueItem,
                { showQueue = false; addFiles() }, { showQueue = false }, onPreview = container.queuePreviews::read)
            if (showVideoSettings && isVideo) VideoSettingsDialog(preferences, viewModel::setAspect,
                viewModel::setSkipSeconds, viewModel::setAutoNext, viewModel::setVideoOrientation,
                onDismiss = { showVideoSettings = false }, onAutoPip = viewModel::setAutoPip, onBackgroundVideo = viewModel::setBackgroundVideo)
        }
        if (isVideo) VideoPlayerTheme { playbackOverlays() } else playbackOverlays()
    }
}
