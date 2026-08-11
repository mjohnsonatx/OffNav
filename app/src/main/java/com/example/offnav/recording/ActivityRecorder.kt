package com.example.offnav.recording

import android.content.Context
import android.location.Location
import android.os.Build
import android.os.SystemClock
import android.util.Log
import com.example.offnav.data.ActivityDao
import com.example.offnav.data.ActivityEntity
import com.example.offnav.data.ActivityStatus
import com.example.offnav.data.ActivityType
import com.example.offnav.data.ElevationSource
import com.example.offnav.data.RecordingStatus
import com.example.offnav.data.TrackPointEntity
import com.example.offnav.location.LocationProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.maplibre.android.geometry.LatLng

data class LiveStats(
    val status: RecordingStatus = RecordingStatus.IDLE,
    val type: ActivityType = ActivityType.OTHER,
    val activeMillis: Long = 0,
    val movingMillis: Long = 0,
    val distanceMeters: Double = 0.0,
    val currentSpeedMps: Double = 0.0,
    val avgMovingSpeedMps: Double = 0.0,
    val elevationGainMeters: Double = 0.0,
    val elevationLossMeters: Double = 0.0,
    val elevationSource: ElevationSource = ElevationSource.NONE,
    val pointCount: Int = 0,
    val gpsAccuracyMeters: Float? = null,
)

/**
 * App-scoped. Owns the GPS subscription for the duration of a recording session and
 * survives ViewModel / Activity death. The foreground service exists only to keep the
 * process (and the location permission grant) alive — it never drives this class.
 */
class ActivityRecorder(
    appContext: Context,
    private val locationProvider: LocationProvider,
    private val dao: ActivityDao,
    private val scope: CoroutineScope,
) {
    private val barometer = BarometerSource(appContext)

    private val _stats = MutableStateFlow(LiveStats())
    val stats: StateFlow<LiveStats> = _stats.asStateFlow()

    val status: StateFlow<RecordingStatus> get() = _statusFlow.asStateFlow()
    private val _statusFlow = MutableStateFlow(RecordingStatus.IDLE)

    /** Segments of the live track, newest last. The map renders this as a MultiLineString. */
    private val _liveTrack = MutableStateFlow<List<List<LatLng>>>(emptyList())
    val liveTrack: StateFlow<List<List<LatLng>>> = _liveTrack.asStateFlow()

    /** Non-null iff a session row exists in Room (RECORDING or PAUSED). */
    private val _activityId = MutableStateFlow<Long?>(null)
    val activityId: StateFlow<Long?> = _activityId.asStateFlow()

    private var sessionJob: Job? = null

    // ── Session state. Only ever touched from inside [runSession]'s single coroutine. ──
    private var type: ActivityType = ActivityType.OTHER
    private var metrics = MetricsAccumulator(type)
    private var elevation = ElevationTracker(barometer.isAvailable)
    private var segment = 0
    private var activeMillisBase = 0L        // accumulated across completed segments
    private var segmentStartRealtime = 0L
    private var pendingWrites = mutableListOf<TrackPointEntity>()
    private var trackSegments = mutableListOf<MutableList<LatLng>>()

    // ═══════════════════════════════════════════════════════════════════
    // Public control surface
    // ═══════════════════════════════════════════════════════════════════

    fun start(activityType: ActivityType) {
        if (_statusFlow.value != RecordingStatus.IDLE) return
        sessionJob?.cancel()
        sessionJob = scope.launch(Dispatchers.Default) {
            val now = System.currentTimeMillis()
            val row = ActivityEntity(
                type = activityType.name,
                status = ActivityStatus.RECORDING.name,
                startedAt = now,
                elevationSource = ElevationSource.NONE.name,
            )
            val id = dao.insertActivity(row)
            _activityId.value = id
            resetSession(activityType, restoredFrom = null)
            runSession(id)
        }
    }

    fun pause() {
        if (_statusFlow.value != RecordingStatus.RECORDING) return
        _statusFlow.value = RecordingStatus.PAUSED
    }

    fun resume() {
        if (_statusFlow.value != RecordingStatus.PAUSED) return
        _statusFlow.value = RecordingStatus.RECORDING
    }

    /** Finalises the row and returns its id, or null if the track was too short to keep. */
    suspend fun finish(title: String, note: String, discardIfEmpty: Boolean = true): Long? {
        val id = _activityId.value ?: return null
        sessionJob?.cancelAndJoin()
        sessionJob = null

        return withContext(NonCancellable + Dispatchers.IO) {
            flushPending(id)

            if (discardIfEmpty && metrics.acceptedCount < MIN_POINTS_TO_KEEP) {
                dao.deleteActivity(id)
                reset()
                return@withContext null
            }

            val existing = dao.activity(id) ?: run { reset(); return@withContext null }
            val endedAt = System.currentTimeMillis()
            dao.updateActivity(
                existing.copy(
                    status = ActivityStatus.COMPLETED.name,
                    title = title.ifBlank { defaultTitle(type, existing.startedAt) },
                    note = note,
                    endedAt = endedAt,
                    elapsedMillis = endedAt - existing.startedAt,
                    activeMillis = currentActiveMillis(),
                    movingMillis = metrics.movingMillis,
                    distanceMeters = metrics.distanceMeters,
                    maxSpeedMps = metrics.maxSpeedMps,
                    elevationGainMeters = elevation.gainMeters,
                    elevationLossMeters = elevation.lossMeters,
                    elevationSource = elevation.source.name,
                    minLat = if (metrics.hasBounds) metrics.minLat else 0.0,
                    minLon = if (metrics.hasBounds) metrics.minLon else 0.0,
                    maxLat = if (metrics.hasBounds) metrics.maxLat else 0.0,
                    maxLon = if (metrics.hasBounds) metrics.maxLon else 0.0,
                    pointCount = metrics.acceptedCount,
                )
            )
            reset()
            id
        }
    }

    suspend fun discard() {
        val id = _activityId.value
        sessionJob?.cancelAndJoin()
        sessionJob = null
        withContext(NonCancellable + Dispatchers.IO) {
            id?.let { dao.deleteActivity(it) }
            reset()
        }
    }

    /**
     * Call once at app start. If the process died mid-recording, the row is still
     * RECORDING. Returns it so the UI can offer Resume / Save / Discard. Nothing is
     * resumed implicitly — a silently-restarted GPS subscription is a battery bug.
     */
    suspend fun findDanglingSession(): ActivityEntity? =
        withContext(Dispatchers.IO) { dao.danglingSession() }

    /** Replays persisted points through the accumulator and continues the session. */
    suspend fun resumeDangling(row: ActivityEntity) {
        if (_statusFlow.value != RecordingStatus.IDLE) return
        val persisted = withContext(Dispatchers.IO) { dao.points(row.id) }
        _activityId.value = row.id
        resetSession(ActivityType.fromName(row.type), restoredFrom = persisted)
        activeMillisBase = row.activeMillis
        sessionJob = scope.launch(Dispatchers.Default) { runSession(row.id) }
    }

    // ═══════════════════════════════════════════════════════════════════
    // Session loop — single coroutine, no locks, no shared mutable state
    // ═══════════════════════════════════════════════════════════════════

    private sealed interface Event {
        @JvmInline value class Fix(val location: Location) : Event
        @JvmInline value class Pressure(val hPa: Float) : Event
        data object Tick : Event
    }

    private suspend fun runSession(id: Long) {
        _statusFlow.value = RecordingStatus.RECORDING
        segmentStartRealtime = SystemClock.elapsedRealtime()

        // LocationProvider.locations has replay = 1. Without this gate the first emission
        // can be a fix from several minutes ago, which would teleport the track's origin.
        val sessionStartNanos = SystemClock.elapsedRealtimeNanos()

        var ticksSinceFlush = 0

        val events: Flow<Event> = merge(
            locationProvider.locations.map { Event.Fix(it) },
            barometer.pressureHpa().map { Event.Pressure(it) },
            tickerFlow(TICK_MS).map { Event.Tick },
        )

        try {
            events.collect { event ->
                when (event) {
                    is Event.Pressure -> elevation.onPressure(event.hPa)

                    is Event.Fix -> {
                        if (_statusFlow.value != RecordingStatus.RECORDING) return@collect
                        val loc = event.location
                        if (loc.elapsedRealtimeNanos < sessionStartNanos) return@collect
                        onFix(id, loc)
                    }

                    Event.Tick -> {
                        publishStats()
                        if (++ticksSinceFlush >= FLUSH_EVERY_TICKS) {
                            ticksSinceFlush = 0
                            flushPending(id)
                            persistProgress(id)
                        }
                    }
                }
            }
        } finally {
            // Cancellation (finish/discard/process teardown) — the caller flushes under
            // NonCancellable. Don't touch the DB from a cancelled coroutine here.
            if (_statusFlow.value == RecordingStatus.RECORDING) {
                activeMillisBase = currentActiveMillis()
            }
        }
    }

    private fun onFix(activityId: Long, loc: Location) {
        val verticalAccuracy =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && loc.hasVerticalAccuracy())
                loc.verticalAccuracyMeters else null

        elevation.onGpsAltitude(
            timestamp = loc.time,
            gpsAltitude = if (loc.hasAltitude()) loc.altitude else null,
            verticalAccuracyMeters = verticalAccuracy,
        )

        val sample = TrackSample(
            timestamp = loc.time,
            lat = loc.latitude,
            lon = loc.longitude,
            altitudeMeters = elevation.altitudeMeters,
            accuracyMeters = if (loc.hasAccuracy()) loc.accuracy else Float.MAX_VALUE,
            speedMps = if (loc.hasSpeed()) loc.speed else null,
        )

        lastAccuracy = sample.accuracyMeters
        lastSpeed = sample.speedMps?.toDouble() ?: 0.0

        if (!metrics.accept(sample)) return

        pendingWrites += TrackPointEntity(
            activityId = activityId,
            segment = segment,
            timestamp = sample.timestamp,
            lat = sample.lat,
            lon = sample.lon,
            altitudeMeters = sample.altitudeMeters,
            accuracyMeters = sample.accuracyMeters,
            speedMps = sample.speedMps,
        )

        trackSegments.lastOrNull()?.add(LatLng(sample.lat, sample.lon))
            ?: trackSegments.add(mutableListOf(LatLng(sample.lat, sample.lon)))

        // Copy-on-emit: Compose/MapLibre must never see a list we keep mutating.
        _liveTrack.value = trackSegments.map { it.toList() }
    }

    private var lastAccuracy: Float? = null
    private var lastSpeed: Double = 0.0

    private fun publishStats() {
        val active = currentActiveMillis()
        _stats.value = LiveStats(
            status = _statusFlow.value,
            type = type,
            activeMillis = active,
            movingMillis = metrics.movingMillis,
            distanceMeters = metrics.distanceMeters,
            currentSpeedMps = if (_statusFlow.value == RecordingStatus.RECORDING) lastSpeed else 0.0,
            avgMovingSpeedMps = if (metrics.movingMillis > 0)
                metrics.distanceMeters / (metrics.movingMillis / 1000.0) else 0.0,
            elevationGainMeters = elevation.gainMeters,
            elevationLossMeters = elevation.lossMeters,
            elevationSource = elevation.source,
            pointCount = metrics.acceptedCount,
            gpsAccuracyMeters = lastAccuracy,
        )
    }

    private suspend fun flushPending(activityId: Long) {
        if (pendingWrites.isEmpty()) return
        val batch = pendingWrites
        pendingWrites = mutableListOf()
        runCatching { withContext(Dispatchers.IO) { dao.insertPoints(batch) } }
            .onFailure { Log.e(TAG, "Track point flush failed; ${batch.size} points lost", it) }
    }

    /** Keeps the row's summary roughly current so a hard kill still leaves usable numbers. */
    private suspend fun persistProgress(activityId: Long) {
        runCatching {
            withContext(Dispatchers.IO) {
                val existing = dao.activity(activityId) ?: return@withContext
                dao.updateActivity(
                    existing.copy(
                        status = if (_statusFlow.value == RecordingStatus.PAUSED)
                            ActivityStatus.PAUSED.name else ActivityStatus.RECORDING.name,
                        activeMillis = currentActiveMillis(),
                        movingMillis = metrics.movingMillis,
                        distanceMeters = metrics.distanceMeters,
                        maxSpeedMps = metrics.maxSpeedMps,
                        elevationGainMeters = elevation.gainMeters,
                        elevationLossMeters = elevation.lossMeters,
                        elevationSource = elevation.source.name,
                        pointCount = metrics.acceptedCount,
                    )
                )
            }
        }.onFailure { Log.w(TAG, "Progress checkpoint failed", it) }
    }

    private fun currentActiveMillis(): Long =
        if (_statusFlow.value == RecordingStatus.RECORDING)
            activeMillisBase + (SystemClock.elapsedRealtime() - segmentStartRealtime)
        else activeMillisBase

    // A pause freezes active time and breaks the polyline; a resume opens a new segment.
    init {
        scope.launch {
            var previous = RecordingStatus.IDLE
            _statusFlow.collect { current ->
                when {
                    previous == RecordingStatus.RECORDING && current == RecordingStatus.PAUSED -> {
                        activeMillisBase = activeMillisBase +
                                (SystemClock.elapsedRealtime() - segmentStartRealtime)
                    }
                    previous == RecordingStatus.PAUSED && current == RecordingStatus.RECORDING -> {
                        segmentStartRealtime = SystemClock.elapsedRealtime()
                        segment++
                        metrics.breakSegment()
                        trackSegments.add(mutableListOf())
                    }
                }
                previous = current
                publishStats()
            }
        }
    }

    private fun resetSession(activityType: ActivityType, restoredFrom: List<TrackPointEntity>?) {
        type = activityType
        metrics = MetricsAccumulator(activityType)
        elevation = ElevationTracker(barometer.isAvailable)
        segment = 0
        activeMillisBase = 0L
        pendingWrites = mutableListOf()
        trackSegments = mutableListOf(mutableListOf())
        lastAccuracy = null
        lastSpeed = 0.0

        restoredFrom?.let { rows ->
            // Replay: identical code path, identical numbers.
            var currentSegment = -1
            rows.forEach { p ->
                if (p.segment != currentSegment) {
                    currentSegment = p.segment
                    metrics.breakSegment()
                    if (trackSegments.last().isNotEmpty()) trackSegments.add(mutableListOf())
                }
                metrics.accept(
                    TrackSample(p.timestamp, p.lat, p.lon, p.altitudeMeters, p.accuracyMeters, p.speedMps)
                )
                trackSegments.last().add(LatLng(p.lat, p.lon))
            }
            segment = currentSegment.coerceAtLeast(0)
            _liveTrack.value = trackSegments.map { it.toList() }
        }
    }

    private fun reset() {
        type = ActivityType.OTHER
        metrics = MetricsAccumulator(type)
        elevation = ElevationTracker(barometer.isAvailable)
        segment = 0
        activeMillisBase = 0L
        segmentStartRealtime = 0L
        lastAccuracy = null
        lastSpeed = 0.0
        pendingWrites = mutableListOf()
        trackSegments = mutableListOf()
        _activityId.value = null
        _liveTrack.value = emptyList()
        _stats.value = LiveStats()
        _statusFlow.value = RecordingStatus.IDLE
    }

    private fun defaultTitle(type: ActivityType, startedAt: Long): String {
        val hour = java.util.Calendar.getInstance()
            .apply { timeInMillis = startedAt }
            .get(java.util.Calendar.HOUR_OF_DAY)
        val partOfDay = when (hour) {
            in 5..11 -> "Morning"; in 12..16 -> "Afternoon"
            in 17..20 -> "Evening"; else -> "Night"
        }
        return "$partOfDay ${type.displayName}"
    }

    private fun tickerFlow(periodMillis: Long): Flow<Unit> = flow {
        while (true) { emit(Unit); delay(periodMillis) }
    }

    private companion object {
        const val TAG = "ActivityRecorder"
        const val TICK_MS = 1_000L
        const val FLUSH_EVERY_TICKS = 5      // persist points every ~5 s
        const val MIN_POINTS_TO_KEEP = 5
    }
}
