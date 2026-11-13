package com.kimyubin33.audioapp.ui

import android.app.Application
import android.os.SystemClock
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.kimyubin33.audioapp.data.AudioApi
import com.kimyubin33.audioapp.data.RadioCatalog
import com.kimyubin33.audioapp.data.SessionSignals
import kotlinx.coroutines.flow.drop
import com.kimyubin33.audioapp.data.location.AudioRegion
import com.kimyubin33.audioapp.data.location.RegionPoint
import com.kimyubin33.audioapp.data.location.audioRegions
import com.kimyubin33.audioapp.data.location.contains
import com.kimyubin33.audioapp.data.location.EncounterTracker
import com.kimyubin33.audioapp.data.location.LocationProblem
import com.kimyubin33.audioapp.data.location.LocationSample
import com.kimyubin33.audioapp.data.location.PhoneLocationSource
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import uniffi.audio_core.NearbyItem
import kotlin.math.roundToInt

data class NearbyCard(
    val content: HomeItem, val place: String, val character: String, val story: String,
    val distanceMeters: Int, val inside: Boolean, val met: Boolean,
    val latitude: Double? = null, val longitude: Double? = null,
)
data class NearbyUiState(
    val enabled: Boolean = false, val problem: LocationProblem? = null,
    val precise: Boolean = false, val accuracyMeters: Int? = null,
    val radiusMeters: Int = 10000, val loading: Boolean = false, val loaded: Boolean = false,
    val items: List<NearbyCard> = emptyList(), val error: String? = null,
    val truncated: Boolean = false, val encounters: Int = 0,
    val regions: List<AudioRegion> = emptyList(),
)

/** 사용자의 좌표는 SavedState/디스크에 쓰지 않는다. 탐색을 떠나면 요청과 좌표를 즉시 비운다. */
class NearbyViewModel(application: Application) : AndroidViewModel(application) {
    private val _state = MutableStateFlow(NearbyUiState())
    val state = _state.asStateFlow()
    private var foreground = false
    private var sample: LocationSample? = null
    private var queried: LocationSample? = null
    private var queriedAt = 0L
    private var request: Job? = null
    private var ticker: Job? = null
    private var generation = 0
    private var results = emptyList<NearbyItem>()
    private val encounters = EncounterTracker()
    private var met = emptySet<String>()
    private val source = PhoneLocationSource(application, ::receive, ::problem)

    init {
        viewModelScope.launch {
            SessionSignals.changes.drop(1).collect {
                invalidate()
                _state.value = _state.value.copy(items=emptyList(),regions=emptyList(),loaded=false)
                if(foreground && _state.value.enabled) start()
            }
        }
    }
    fun hasPermission() = source.hasPermission()
    fun enable() {
        _state.value = _state.value.copy(enabled = true)
        if (foreground) start()
    }
    fun disable() {
        stop()
        encounters.clear()
        met = emptySet()
        _state.value = NearbyUiState(radiusMeters = _state.value.radiusMeters)
    }
    fun enterForeground() {
        foreground = true
        if (_state.value.enabled) start()
    }
    fun leaveForeground() {
        foreground = false
        stop()
    }
    private fun start() {
        source.start()
        ticker?.cancel()
        ticker = viewModelScope.launch {
            while (true) {
                delay(5_000)
                if (!source.hasPermission()) {
                    source.stop()
                    problem(LocationProblem.Permission)
                } else if (sample?.isFresh(SystemClock.elapsedRealtime()) == false) {
                    problem(LocationProblem.Stale)
                }
            }
        }
    }
    private fun stop() {
        source.stop()
        ticker?.cancel()
        ticker = null
        invalidate()
        encounters.interrupt()
        _state.value = _state.value.copy(accuracyMeters = null, items = emptyList(), loaded = false, problem = null)
    }
    private fun invalidate() {
        generation++
        request?.cancel()
        request = null
        sample = null
        queried = null
        queriedAt = 0
        results = emptyList()
        RadioCatalog.clearNearby()
        _state.value = _state.value.copy(loading = false, regions = emptyList())
    }
    private fun problem(value: LocationProblem) {
        if (value != LocationProblem.Waiting) {
            invalidate()
            encounters.interrupt()
        }
        _state.value = _state.value.copy(problem = value, precise = source.precise(), items = emptyList(), loaded = false, accuracyMeters = null)
    }
    private fun receive(value: LocationSample) {
        if (!foreground || !_state.value.enabled || !source.hasPermission()) return
        val now = SystemClock.elapsedRealtime()
        if (!value.isFresh(now)) return
        val previous = sample
        if (previous != null && previous.isFresh(now) &&
            (value.elapsedMillis < previous.elapsedMillis ||
                (value.elapsedMillis - previous.elapsedMillis < 15_000 && value.accuracyMeters > previous.accuracyMeters * 2))) return
        sample = value
        _state.value = _state.value.copy(problem = null, precise = source.precise(), accuracyMeters = value.accuracyMeters.roundToInt())
        updateCards(value)
        val old = queried
        if (old == null || now - queriedAt >= 60_000 ||
            (now - queriedAt >= 15_000 && value.distanceTo(old.latitude, old.longitude) >= maxOf(100f, value.accuracyMeters))) query(value)
    }
    fun radius(meters: Int) {
        if (meters !in listOf(1000, 5000, 10000) || meters == _state.value.radiusMeters) return
        _state.value = _state.value.copy(radiusMeters = meters, items = emptyList(), loaded = false)
        results = emptyList()
        encounters.interrupt()
        refresh()
    }
    fun refresh() {
        if (!_state.value.enabled || !foreground) return
        val fix = sample
        if (fix != null && fix.isFresh(SystemClock.elapsedRealtime()) && source.hasPermission()) query(fix)
        else start()
    }
    private fun query(fix: LocationSample) {
        if (!source.hasPermission()) { problem(LocationProblem.Permission); return }
        request?.cancel()
        val version = ++generation
        queried = fix
        queriedAt = SystemClock.elapsedRealtime()
        _state.value = _state.value.copy(loading = true, error = null)
        val radius = _state.value.radiusMeters
        request = viewModelScope.launch {
            try {
                val page = withContext(Dispatchers.IO) {
                    AudioApi.get(getApplication()).nearbyContents(fix.latitude, fix.longitude, radius.toUInt())
                }
                if (version != generation || !foreground || !_state.value.enabled) return@launch
                if (!source.hasPermission()) { problem(LocationProblem.Permission); return@launch }
                val current = sample
                if (current == null || !current.isFresh(SystemClock.elapsedRealtime())) { problem(LocationProblem.Stale); return@launch }
                results = page.items.filter { it.item.content.kind == uniffi.audio_core.ContentKind.AUDIO }
                _state.value = _state.value.copy(loaded = true, loading = false, truncated = page.truncated)
                updateCards(current)
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) {
                if (version == generation) _state.value = _state.value.copy(loading = false, error = userMessage(e))
            }
        }
    }
    private fun updateCards(fix: LocationSample) {
        val inside = results.filter {
            it.location.character.isNotBlank() && fix.isInside(it.location.latitude, it.location.longitude, it.location.radiusMeters.toInt())
        }.map { it.item.id }.toSet()
        met = encounters.update(inside, fix.elapsedMillis)
        val cards = results.map { row ->
            NearbyCard(row.item.toHomeItem(),
                row.location.name, row.location.character, row.location.story,
                fix.distanceTo(row.location.latitude, row.location.longitude).roundToInt(),
                fix.isInside(row.location.latitude, row.location.longitude, row.location.radiusMeters.toInt()), row.item.id in met,
                row.location.latitude, row.location.longitude)
        }.filter { it.distanceMeters <= _state.value.radiusMeters }.sortedBy { it.distanceMeters }
        val regions = audioRegions(results.map {
            RegionPoint(it.item.id,it.location.name,it.location.latitude,it.location.longitude,it.location.radiusMeters.toInt())
        },fix)
        RadioCatalog.updateNearby(regions.filter { it.contains(fix.accuracyMeters) }.flatMap { it.contentIds })
        _state.value = _state.value.copy(items = cards, encounters = met.size, regions = regions)
    }
    override fun onCleared() { source.stop(); RadioCatalog.clearNearby(); super.onCleared() }
}
