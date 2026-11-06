package com.kimyubin33.audioapp

import android.Manifest
import android.content.ComponentName
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.core.content.ContextCompat
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.google.common.util.concurrent.ListenableFuture
import com.kimyubin33.audioapp.ui.AppNavigation
import com.kimyubin33.audioapp.ui.HomeViewModel
import com.kimyubin33.audioapp.ui.theme.AudioAppTheme

class MainActivity : ComponentActivity() {

    private val homeViewModel: HomeViewModel by viewModels()

    // 플레이어는 PlaybackService가 들고 있고, 화면은 이 컨트롤러로 조종한다
    private var controllerFuture: ListenableFuture<MediaController>? = null
    private var controller by mutableStateOf<MediaController?>(null)

    // 알림 권한을 거부해도 재생은 된다 (알림만 안 보임)
    private val notificationPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    // debug 전용: 에뮬레이터에서 PC의 목업 서버(사설망 주소)에 접속하려면 필요하다 (Android 17+).
    // 허용하든 거부하든 목록 불러오기를 시작한다 (거부하면 화면에 오류와 "다시 시도"가 보인다).
    private val localNetworkPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { homeViewModel.loadIfNeeded() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        connectToPlaybackService()
        loadListWhenAllowed()
        setContent {
            AudioAppTheme {
                Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
                    // 서비스에 연결되기 전에는 아무것도 그리지 않는다 (보통 순식간)
                    controller?.let { player ->
                        AppNavigation(
                            player = player,
                            homeState = homeViewModel.state,
                            onRetry = homeViewModel::retry,
                            onLoadMore = homeViewModel::loadMore,
                            loadDetail = homeViewModel::loadDetail,
                            onReport = homeViewModel::report,
                            onBlock = homeViewModel::block,
                            onRefresh = homeViewModel::refresh,
                            onPlayRequested = ::requestNotificationPermissionIfNeeded,
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(innerPadding),
                        )
                    }
                }
            }
        }
    }

    override fun onDestroy() {
        // 컨트롤러만 놓는다. 재생 중이면 서비스는 계속 살아 있다.
        controllerFuture?.let { MediaController.releaseFuture(it) }
        controllerFuture = null
        controller = null
        super.onDestroy()
    }

    /** debug 빌드에서 로컬 네트워크 권한이 필요하면 먼저 묻고, 아니면 바로 목록을 불러온다. */
    private fun loadListWhenAllowed() {
        val needsLocalNetworkPermission = BuildConfig.DEBUG &&
            Build.VERSION.SDK_INT >= 37 &&
            ContextCompat.checkSelfPermission(this, LOCAL_NETWORK_PERMISSION) !=
            PackageManager.PERMISSION_GRANTED
        if (needsLocalNetworkPermission) {
            localNetworkPermission.launch(LOCAL_NETWORK_PERMISSION)
        } else {
            homeViewModel.loadIfNeeded()
        }
    }

    private fun connectToPlaybackService() {
        val token = SessionToken(this, ComponentName(this, PlaybackService::class.java))
        val future = MediaController.Builder(this, token).buildAsync()
        controllerFuture = future
        future.addListener(
            { controller = runCatching { future.get() }.getOrNull() },
            ContextCompat.getMainExecutor(this),
        )
    }

    private fun requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    private companion object {
        const val LOCAL_NETWORK_PERMISSION = "android.permission.ACCESS_LOCAL_NETWORK"
    }
}
