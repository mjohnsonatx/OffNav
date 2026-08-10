package com.example.offnav.recording

import android.content.Context
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.offnav.data.ActivityEntity
import com.example.offnav.data.ActivityRepository
import com.example.offnav.data.ActivitySummary
import com.example.offnav.data.ActivityType
import com.example.offnav.data.RecordingStatus
import com.example.offnav.export.ActivityCardRenderer
import com.example.offnav.export.GpxExporter
import com.example.offnav.export.GpxOptions
import com.example.offnav.service.RecordingForegroundService
import com.example.offnav.sharing.ActivitySharer
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import org.maplibre.geojson.Feature
import org.maplibre.geojson.FeatureCollection
import org.maplibre.geojson.MultiLineString
import org.maplibre.geojson.Point

private const val EMPTY_FC = """{"type":"FeatureCollection","features":[]}"""

class RecordViewModel(
    private val recorder: ActivityRecorder,
    private val repository: ActivityRepository,
    private val gpxExporter: GpxExporter,
    private val cardRenderer: ActivityCardRenderer,
) : ViewModel() {

    val stats = recorder.stats
    val status = recorder.status

    /** GeoJSON for the live track layer. Rebuilt at most once per second (recorder tick). */
    val liveTrackGeoJson = recorder.liveTrack
        .map { segments ->
            val usable = segments.filter { it.size >= 2 }
            if (usable.isEmpty()) return@map EMPTY_FC
            FeatureCollection.fromFeature(
                Feature.fromGeometry(
                    MultiLineString.fromLngLats(
                        usable.map { seg -> seg.map { Point.fromLngLat(it.longitude, it.latitude) } }
                    )
                )
            ).toJson()
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), EMPTY_FC)

    val activities = repository.observeCompleted()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private val _dangling = MutableStateFlow<ActivityEntity?>(null)
    val dangling = _dangling.asStateFlow()

    /** Shown after Stop, before the row is committed. */
    private val _pendingSave = MutableStateFlow(false)
    val pendingSave = _pendingSave.asStateFlow()

    private val _savedActivityId = MutableStateFlow<Long?>(null)
    val savedActivityId = _savedActivityId.asStateFlow()

    private val _sharing = MutableStateFlow(false)
    val sharing = _sharing.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    val error = _error.asStateFlow()

    init {
        viewModelScope.launch { _dangling.value = recorder.findDanglingSession() }
    }

    fun start(context: Context, type: ActivityType) {
        recorder.start(type)
        RecordingForegroundService.start(context)
    }

    fun pause() = recorder.pause()
    fun resume() = recorder.resume()

    /** Stop arms the save sheet; the row isn't finalised until [save] or [discard]. */
    fun requestStop() {
        recorder.pause()
        _pendingSave.value = true
    }

    fun cancelStop() {
        _pendingSave.value = false
        recorder.resume()
    }

    fun save(context: Context, title: String, note: String) = viewModelScope.launch {
        val id = recorder.finish(title, note)
        RecordingForegroundService.stop(context)
        _pendingSave.value = false
        _savedActivityId.value = id
        if (id == null) _error.value = "Track too short to save"
    }

    fun discard(context: Context) = viewModelScope.launch {
        recorder.discard()
        RecordingForegroundService.stop(context)
        _pendingSave.value = false
    }

    fun resumeDangling(context: Context) = viewModelScope.launch {
        val row = _dangling.value ?: return@launch
        _dangling.value = null
        recorder.resumeDangling(row)
        RecordingForegroundService.start(context)
    }

    fun saveDangling() = viewModelScope.launch {
        val row = _dangling.value ?: return@launch
        _dangling.value = null
        recorder.resumeDangling(row)
        recorder.pause()
        recorder.finish(row.title, row.note)
    }

    fun discardDangling() = viewModelScope.launch {
        _dangling.value = null
        recorder.discard()
    }

    fun consumeSavedId() { _savedActivityId.value = null }
    fun consumeError() { _error.value = null }

    fun shareCard(context: Context, id: Long) = viewModelScope.launch {
        _sharing.value = true
        try {
            val summary = repository.summary(id) ?: error("Activity missing")
            val segments = repository.trackSegments(id)
            val uri = cardRenderer.render(summary, segments)
            ActivitySharer.shareCard(context, uri, summary)
        } catch (t: Throwable) {
            Log.e("RecordViewModel", "Card render failed", t)
            _error.value = "Could not build share image"
        } finally {
            _sharing.value = false
        }
    }

    fun shareGpx(context: Context, id: Long, options: GpxOptions) = viewModelScope.launch {
        _sharing.value = true
        try {
            val summary = repository.summary(id) ?: error("Activity missing")
            ActivitySharer.shareGpx(context, gpxExporter.export(id, options), summary)
        } catch (t: Throwable) {
            Log.e("RecordViewModel", "GPX export failed", t)
            _error.value = "Could not export GPX"
        } finally {
            _sharing.value = false
        }
    }

    fun delete(id: Long) = viewModelScope.launch { repository.delete(id) }
    fun rename(id: Long, title: String, note: String) =
        viewModelScope.launch { repository.rename(id, title, note) }
}