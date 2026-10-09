package io.github.micro123.mediaplayer.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.micro123.mediaplayer.data.update.GithubRelease
import io.github.micro123.mediaplayer.data.update.GithubReleaseClient
import io.github.micro123.mediaplayer.data.update.UpdateCheckException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

sealed interface UpdateState {
    data object Idle : UpdateState
    data object Checking : UpdateState
    data class Checked(val release: GithubRelease) : UpdateState
    data class Failed(val message: String) : UpdateState
}

class UpdateViewModel(private val fetch: suspend () -> GithubRelease = { GithubReleaseClient().latest() }) : ViewModel() {
    private val mutableState = MutableStateFlow<UpdateState>(UpdateState.Idle)
    val state = mutableState.asStateFlow()
    private var lastSuccessfulCheck = 0L

    fun checkNow() {
        if (state.value == UpdateState.Checking) return
        // Reopening the same result need not spend another anonymous API request.
        if (state.value is UpdateState.Checked && android.os.SystemClock.elapsedRealtime() - lastSuccessfulCheck < 60_000) return
        mutableState.value = UpdateState.Checking
        viewModelScope.launch {
            try {
                mutableState.value = UpdateState.Checked(fetch())
                lastSuccessfulCheck = android.os.SystemClock.elapsedRealtime()
            } catch (error: CancellationException) { mutableState.value = UpdateState.Idle; throw error }
            catch (error: UpdateCheckException) { mutableState.value = UpdateState.Failed(error.message ?: "无法检查更新") }
            catch (_: Exception) { mutableState.value = UpdateState.Failed("无法连接 GitHub，请检查网络后重试，或打开发布页面") }
        }
    }
}
