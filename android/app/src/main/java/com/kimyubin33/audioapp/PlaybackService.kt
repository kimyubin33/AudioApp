@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)

package com.kimyubin33.audioapp

import android.content.Intent
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import com.google.common.collect.ImmutableList
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture

/**
 * 플레이어를 화면(Activity) 밖에서 들고 있는 서비스.
 * 화면을 꺼도 재생이 이어지고, 알림창·잠금화면·블루투스에서 제어할 수 있다.
 */
class PlaybackService : MediaSessionService() {

    private var mediaSession: MediaSession? = null

    override fun onCreate() {
        super.onCreate()
        val player = ExoPlayer.Builder(this)
            // 다른 앱이 소리를 내면 양보하고, 이어폰이 빠지면 자동으로 멈춘다
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(C.USAGE_MEDIA)
                    .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
                    .build(),
                /* handleAudioFocus = */ true,
            )
            .setHandleAudioBecomingNoisy(true)
            .build()
        mediaSession = MediaSession.Builder(this, player)
            .setCallback(SessionCallback())
            .build()
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? = mediaSession

    /** 사용자가 앱을 최근 앱 목록에서 밀어 지웠을 때: 재생 중이 아니면 서비스를 끝낸다. */
    override fun onTaskRemoved(rootIntent: Intent?) {
        val player = mediaSession?.player
        if (player == null || !player.playWhenReady || player.mediaItemCount == 0) {
            stopSelf()
        }
    }

    override fun onDestroy() {
        mediaSession?.run {
            player.release()
            release()
        }
        mediaSession = null
        super.onDestroy()
    }

    /** 이 세션에 누가 연결하고 무엇을 재생시킬 수 있는지 제한한다. */
    private inner class SessionCallback : MediaSession.Callback {

        override fun onConnect(
            session: MediaSession,
            controller: MediaSession.ControllerInfo,
        ): MediaSession.ConnectionResult {
            // 우리 앱, 시스템(알림·잠금화면·블루투스 등 신뢰된 컨트롤러)만 허용한다
            val allowed = controller.packageName == packageName ||
                controller.isTrusted ||
                session.isMediaNotificationController(controller)
            return if (allowed) {
                MediaSession.ConnectionResult.AcceptedResultBuilder(session).build()
            } else {
                MediaSession.ConnectionResult.reject()
            }
        }

        override fun onAddMediaItems(
            mediaSession: MediaSession,
            controller: MediaSession.ControllerInfo,
            mediaItems: MutableList<MediaItem>,
        ): ListenableFuture<MutableList<MediaItem>> {
            // https 주소만 재생한다. 그 외(http, file, content 등)는 조용히 버린다.
            val safe = mediaItems.filter { it.localConfiguration?.uri?.scheme == "https" }
            return Futures.immediateFuture(ImmutableList.copyOf(safe))
        }
    }
}
