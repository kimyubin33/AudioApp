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
import com.kimyubin33.audioapp.ui.theme.AudioAppTheme

class MainActivity : ComponentActivity() {

    // 플레이어는 PlaybackService가 들고 있고, 화면은 이 컨트롤러로 조종한다
    private var controllerFuture: ListenableFuture<MediaController>? = null
    private var controller by mutableStateOf<MediaController?>(null)

    // 알림 권한을 거부해도 재생은 된다 (알림만 안 보임)
    private val notificationPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        connectToPlaybackService()
        setContent {
            AudioAppTheme {
                Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
                    // 서비스에 연결되기 전에는 아무것도 그리지 않는다 (보통 순식간)
                    controller?.let { player ->
                        AppNavigation(
                            player = player,
                            onPlayRequested = ::requestNotificationPermissionIfNeeded,
                            modifier = Modifier.padding(innerPadding),
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
}
