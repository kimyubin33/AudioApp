@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)

package com.kimyubin33.audioapp

import android.content.Intent
import android.os.Process
import androidx.media3.common.*
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.*
import androidx.media3.session.MediaLibraryService.MediaLibrarySession
import com.google.common.collect.ImmutableList
import com.google.common.util.concurrent.*
import com.kimyubin33.audioapp.data.*
import com.kimyubin33.audioapp.ui.EXTRA_CREATOR_ID
import com.kimyubin33.audioapp.ui.EXTRA_AI_GENERATED
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.drop

/** One player for phone, Android Auto, lock screen and Bluetooth. Hosts render their own driving UI. */
class PlaybackService : MediaLibraryService() {
    private var session: MediaLibrarySession? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var radio = false
    private var generation = 0
    private var filling = false
    private val queued = linkedSetOf<String>()

    override fun onCreate() {
        super.onCreate()
        val player = ExoPlayer.Builder(this)
            .setAudioAttributes(AudioAttributes.Builder().setUsage(C.USAGE_MEDIA)
                .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC).build(), true)
            .setHandleAudioBecomingNoisy(true).build()
        session = MediaLibrarySession.Builder(this, player, Callback()).build()
        player.addListener(object : Player.Listener {
            override fun onMediaItemTransition(item: MediaItem?, reason: Int) {
                item?.let {
                    LocalLibrary.get(this@PlaybackService).recordPlay(LibraryEntry(it.mediaId,
                        it.mediaMetadata.title?.toString().orEmpty(),
                        it.mediaMetadata.extras?.getString(EXTRA_CREATOR_ID).orEmpty(),
                        it.mediaMetadata.extras?.getBoolean(EXTRA_AI_GENERATED) ?: false,
                        false, System.currentTimeMillis()))
                }
                replenish()
            }
            override fun onPlaybackStateChanged(playbackState: Int) {
                if (playbackState == Player.STATE_READY) replenish()
            }
        })
        scope.launch {
            SessionSignals.changes.drop(1).collect {
                generation++; radio = false; queued.clear(); RadioCatalog.clearNearby()
                player.stop(); player.clearMediaItems()
            }
        }
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaLibrarySession? = session
    override fun onTaskRemoved(rootIntent: Intent?) {
        if (session?.player?.playWhenReady != true) stopSelf()
    }
    override fun onDestroy() {
        scope.cancel()
        session?.run { player.release(); release() }
        session = null
        super.onDestroy()
    }

    private fun <T> future(block: suspend () -> T): ListenableFuture<T> {
        val result = SettableFuture.create<T>()
        val account = SessionSignals.changes.value
        val job = scope.launch {
            try {
                val value = block()
                if (account != SessionSignals.changes.value) throw CancellationException("Account changed")
                result.set(value)
            }
            catch (e: CancellationException) { result.cancel(false); throw e }
            catch (e: Exception) { result.setException(e) }
        }
        result.addListener({ if (result.isCancelled) job.cancel() }, MoreExecutors.directExecutor())
        return result
    }
    private suspend fun playable(ids: List<String>): MutableList<MediaItem> {
        val result = mutableListOf<MediaItem>()
        for (id in ids.distinct().take(12)) {
            try { result += RadioCatalog.resolve(this, id) }
            catch (e: CancellationException) { throw e }
            catch (_: Exception) { /* Removed, blocked or unavailable content never reaches the player. */ }
        }
        return result
    }
    private fun replenish() {
        val player = session?.player ?: return
        if (!radio || filling || player.mediaItemCount - player.currentMediaItemIndex > 3) return
        filling = true
        val epoch = generation
        scope.launch {
            try {
                val more = playable(RadioCatalog.mix(this@PlaybackService, queued))
                if (epoch == generation && radio && more.isNotEmpty()) {
                    queued.addAll(more.map { it.mediaId })
                    while (queued.size > 200) queued.remove(queued.first())
                    if(player.currentMediaItemIndex > 12) player.removeMediaItems(0,player.currentMediaItemIndex-12)
                    player.addMediaItems(more)
                }
            } catch (e: CancellationException) { throw e }
            catch (_: Exception) { /* Keep the already-buffered queue; retry on a later transition. */ }
            finally { filling = false }
        }
    }
    private fun folder(id: String, title: String, playable: Boolean = false) =
        MediaItem.Builder().setMediaId(id).setMediaMetadata(MediaMetadata.Builder()
            .setTitle(title).setIsBrowsable(!playable).setIsPlayable(playable)
            .setMediaType(if (playable) MediaMetadata.MEDIA_TYPE_RADIO_STATION else MediaMetadata.MEDIA_TYPE_FOLDER_MIXED)
            .build()).build()

    private inner class Callback : MediaLibrarySession.Callback {
        override fun onConnect(session: MediaSession, controller: MediaSession.ControllerInfo): MediaSession.ConnectionResult {
            // ControllerInfo is verified by Media3 against Binder UID. No package-name-only bypass.
            val allowed = controller.uid == Process.myUid() || controller.isTrusted || session.isMediaNotificationController(controller)
            return if (allowed) MediaSession.ConnectionResult.AcceptedResultBuilder(session,controller).build()
                else MediaSession.ConnectionResult.reject()
        }
        override fun onGetLibraryRoot(session: MediaLibrarySession, browser: MediaSession.ControllerInfo,
            params: LibraryParams?): ListenableFuture<LibraryResult<MediaItem>> =
            Futures.immediateFuture(LibraryResult.ofItem(folder(RadioCatalog.ROOT, "Audio"), params))

        override fun onGetChildren(session: MediaLibrarySession, browser: MediaSession.ControllerInfo,
            parentId: String, page: Int, pageSize: Int, params: LibraryParams?): ListenableFuture<LibraryResult<ImmutableList<MediaItem>>> = future {
            try {
                require(page >= 0 && pageSize > 0)
                val items = when (parentId) {
                    RadioCatalog.ROOT -> listOf(folder(RadioCatalog.MIX,"Drive Mix",true),folder(RadioCatalog.ALL,"전체"),
                        folder(RadioCatalog.FOLLOWING,"구독"),folder(RadioCatalog.SAVED,"Saved"))
                    RadioCatalog.ALL, RadioCatalog.FOLLOWING -> RadioCatalog.browse(this@PlaybackService,
                        following=parentId==RadioCatalog.FOLLOWING).map { row ->
                        MediaItem.Builder().setMediaId(row.id).setMediaMetadata(MediaMetadata.Builder()
                            .setTitle(row.content.title).setArtist(row.content.creatorId)
                            .setIsBrowsable(false).setIsPlayable(true).setMediaType(MediaMetadata.MEDIA_TYPE_MUSIC).build()).build()
                    }
                    RadioCatalog.SAVED -> LocalLibrary.get(this@PlaybackService).saved.value
                        .filterNot { it.isPost || it.isVideo }.take(50).map {
                            MediaItem.Builder().setMediaId(it.id).setMediaMetadata(MediaMetadata.Builder().setTitle(it.title)
                                .setArtist(it.creator).setIsBrowsable(false).setIsPlayable(true).build()).build()
                        }
                    else -> return@future LibraryResult.ofError(SessionError.ERROR_BAD_VALUE)
                }
                val start = (page.toLong()*pageSize).coerceAtMost(items.size.toLong()).toInt()
                LibraryResult.ofItemList(items.drop(start).take(pageSize.coerceAtMost(100)),params)
            } catch (e: CancellationException) { throw e }
            catch (e: uniffi.audio_core.NetException.Unauthorized) { LibraryResult.ofError(SessionError.ERROR_SESSION_AUTHENTICATION_EXPIRED) }
            catch (_: Exception) { LibraryResult.ofError(SessionError.ERROR_IO) }
        }

        override fun onGetItem(session: MediaLibrarySession, browser: MediaSession.ControllerInfo,
            mediaId: String): ListenableFuture<LibraryResult<MediaItem>> = future {
            try {
                val item = when (mediaId) {
                    RadioCatalog.ROOT -> folder(mediaId,"Audio")
                    RadioCatalog.ALL -> folder(mediaId,"전체")
                    RadioCatalog.FOLLOWING -> folder(mediaId,"구독")
                    RadioCatalog.SAVED -> folder(mediaId,"Saved")
                    RadioCatalog.MIX -> folder(mediaId,"Drive Mix",true)
                    else -> RadioCatalog.resolve(this@PlaybackService,mediaId)
                }
                LibraryResult.ofItem(item,null)
            } catch (e: CancellationException) { throw e }
            catch (_: Exception) { LibraryResult.ofError(SessionError.ERROR_BAD_VALUE) }
        }

        override fun onSearch(session: MediaLibrarySession, browser: MediaSession.ControllerInfo,
            query: String, params: LibraryParams?): ListenableFuture<LibraryResult<Void>> = future {
            try {
                require(query.isNotBlank() && query.length <= 100)
                val account = SessionSignals.changes.value
                val count = RadioCatalog.browse(this@PlaybackService,query=query).size
                if(account != SessionSignals.changes.value) throw CancellationException("Account changed")
                session.notifySearchResultChanged(browser,query,count,params)
                LibraryResult.ofVoid(params)
            } catch(e: CancellationException) { throw e }
            catch(_: Exception) { LibraryResult.ofError(SessionError.ERROR_IO) }
        }

        override fun onGetSearchResult(session: MediaLibrarySession, browser: MediaSession.ControllerInfo,
            query: String, page: Int, pageSize: Int, params: LibraryParams?): ListenableFuture<LibraryResult<ImmutableList<MediaItem>>> = future {
            try {
                require(query.isNotBlank() && query.length <= 100 && page >= 0 && pageSize > 0)
                val rows = RadioCatalog.browse(this@PlaybackService,query=query)
                val start = (page.toLong()*pageSize).coerceAtMost(rows.size.toLong()).toInt()
                LibraryResult.ofItemList(rows.drop(start).take(pageSize.coerceAtMost(100)).map {
                    MediaItem.Builder().setMediaId(it.id).setMediaMetadata(MediaMetadata.Builder()
                        .setTitle(it.content.title).setArtist(it.content.creatorId)
                        .setIsBrowsable(false).setIsPlayable(true).setMediaType(MediaMetadata.MEDIA_TYPE_MUSIC).build()).build()
                },params)
            } catch(e: CancellationException) { throw e }
            catch(_: Exception) { LibraryResult.ofError(SessionError.ERROR_IO) }
        }

        override fun onAddMediaItems(mediaSession: MediaSession, controller: MediaSession.ControllerInfo,
            mediaItems: MutableList<MediaItem>): ListenableFuture<MutableList<MediaItem>> {
            val epoch = ++generation
            radio = mediaItems.singleOrNull()?.mediaId == RadioCatalog.MIX
            queued.clear()
            return future {
                // Ignore caller-provided URLs. Resolve only approved audio IDs from our server.
                val search = mediaItems.singleOrNull()?.requestMetadata?.searchQuery
                val ids = when {
                    radio -> RadioCatalog.mix(this@PlaybackService, emptySet())
                    !search.isNullOrBlank() -> {
                        require(search.length <= 100)
                        RadioCatalog.browse(this@PlaybackService,query=search).map { it.id }
                    }
                    else -> mediaItems.map { it.mediaId }
                }
                val resolved = playable(ids)
                if (epoch != generation) throw CancellationException("Session changed")
                check(resolved.isNotEmpty()) { "No playable audio" }
                queued.addAll(resolved.map { it.mediaId })
                resolved
            }
        }
    }
}
