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
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.google.common.util.concurrent.ListenableFuture
import com.kimyubin33.audioapp.data.AudioApi
import com.kimyubin33.audioapp.ui.AppNavigation
import com.kimyubin33.audioapp.ui.theme.AudioAppTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : ComponentActivity() {

    // 플레이어는 PlaybackService가 들고 있고, 화면은 이 컨트롤러로 조종한다
    private var controllerFuture: ListenableFuture<MediaController>? = null
    private var controller by mutableStateOf<MediaController?>(null)

    // 임시 확인용: 서버(목업)와 통신이 되는지 화면 위쪽에 한 줄로 보여준다
    private var serverStatus by mutableStateOf("서버 확인 중...")

    // 알림 권한을 거부해도 재생은 된다 (알림만 안 보임)
    private val notificationPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    // debug 전용: 에뮬레이터에서 PC의 목업 서버(사설망 주소)에 접속하려면 필요하다 (Android 17+)
    private val localNetworkPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { checkServer() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        connectToPlaybackService()
        checkServerWhenAllowed()
        setContent {
            AudioAppTheme {
                Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
                    Column(modifier = Modifier.padding(innerPadding)) {
                        Text(
                            text = serverStatus,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(horizontal = 32.dp, vertical = 4.dp),
                        )
                        // 서비스에 연결되기 전에는 아무것도 그리지 않는다 (보통 순식간)
                        controller?.let { player ->
                            AppNavigation(
                                player = player,
                                onPlayRequested = ::requestNotificationPermissionIfNeeded,
                                modifier = Modifier.weight(1f),
                            )
                        }
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

    /** debug 빌드에서 로컬 네트워크 권한이 필요하면 먼저 묻고, 아니면 바로 서버를 확인한다. */
    private fun checkServerWhenAllowed() {
        val needsLocalNetworkPermission = BuildConfig.DEBUG &&
            Build.VERSION.SDK_INT >= 37 &&
            ContextCompat.checkSelfPermission(this, LOCAL_NETWORK_PERMISSION) !=
            PackageManager.PERMISSION_GRANTED
        if (needsLocalNetworkPermission) {
            localNetworkPermission.launch(LOCAL_NETWORK_PERMISSION)
        } else {
            checkServer()
        }
    }

    private fun checkServer() {
        lifecycleScope.launch {
            serverStatus = try {
                // Keystore·파일 읽기가 들어 있어서 메인 스레드 밖에서 만든다
                val api = withContext(Dispatchers.IO) { AudioApi.get(applicationContext) }
                val page = api.listContents(1u, null)
                val first = page.items.first()
                val detail = api.contentDetail(first.id)
                val https = detail.streamUrl.startsWith("https://")
                "서버 연결 성공: '${first.content.title}' · 다음 페이지 ${if (page.nextCursor != null) "있음" else "없음"} · 재생 주소 https=$https"
            } catch (e: Exception) {
                "서버 오류: ${e::class.simpleName}"
            }
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

    private companion object {
        const val LOCAL_NETWORK_PERMISSION = "android.permission.ACCESS_LOCAL_NETWORK"
    }

    private fun requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }
}
