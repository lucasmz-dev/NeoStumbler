package xyz.malkki.neostumbler.scanner

import kotlin.math.abs
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import timber.log.Timber
import xyz.malkki.neostumbler.domain.AirPressureObservation
import xyz.malkki.neostumbler.domain.BluetoothBeacon
import xyz.malkki.neostumbler.domain.CellTower
import xyz.malkki.neostumbler.domain.ObservedDevice
import xyz.malkki.neostumbler.domain.Position
import xyz.malkki.neostumbler.domain.WifiAccessPoint
import xyz.malkki.neostumbler.extensions.buffer
import xyz.malkki.neostumbler.extensions.combineWithLatestFrom
import xyz.malkki.neostumbler.scanner.data.ReportData
import xyz.malkki.neostumbler.scanner.movement.ConstantMovementDetector
import xyz.malkki.neostumbler.scanner.movement.MovementDetector
import xyz.malkki.neostumbler.scanner.postprocess.ReportPostProcessor

// Don't emit new locations until the distance between them is at least 30 metres or when at least
// 10 seconds have passed
private val LOCATION_MAX_AGE_UNTIL_CHANGED = 10.seconds
private const val LOCATION_MAX_DISTANCE_DIFF_UNTIL_CHANGED = 30

// Maximum age of air pressure data, relative to the location timestamp
private val AIR_PRESSURE_MAX_AGE = 2.seconds

// Retain locations in the last 10 seconds
private val LOCATION_BUFFER_DURATION = 10.seconds

class WirelessScanner(
    private val locationSource: () -> Flow<Position>,
    private val airPressureSource: () -> Flow<AirPressureObservation>,
    private val cellInfoSource: () -> Flow<List<CellTower>>,
    private val wifiAccessPointSource: () -> Flow<List<WifiAccessPoint>>,
    private val bluetoothBeaconSource: () -> Flow<List<BluetoothBeacon>>,
    private val movementDetector: MovementDetector = ConstantMovementDetector,
    private val postProcessors: List<ReportPostProcessor> = emptyList(),
) {
    private fun getLocationsWithAirPressure(): Flow<Position> {
        val locationFlow = locationSource.invoke()
        val airPressureFlow = airPressureSource.invoke()

        return locationFlow.combineWithLatestFrom(airPressureFlow) { location, airPressure ->
            location.copy(
                pressure =
                    airPressure
                        ?.takeIf {
                            // Use air pressure data only if it's not too old
                            abs(location.timestamp - it.timestamp).milliseconds <=
                                AIR_PRESSURE_MAX_AGE
                        }
                        ?.airPressure
                        ?.toDouble()
            )
        }
    }

    private suspend fun <K, V : ObservedDevice<K>> startCollectingData(
        isMovingFlow: Flow<Boolean>,
        dataSource: () -> Flow<List<V>>,
        mapMutex: Mutex,
        map: MutableMap<K, V>,
    ) {
        isMovingFlow
            .flatMapLatest { isMoving ->
                if (isMoving) {
                    dataSource.invoke()
                } else {
                    emptyFlow()
                }
            }
            .collect { data -> mapMutex.withLock { data.forEach { map[it.uniqueKey] = it } } }
    }

    private fun Flow<Position>.filterInaccurateLocations(): Flow<Position> = filter { location ->
        location.accuracy != null &&
            location.accuracy <= ScanningConstants.LOCATION_MAX_ACCURACY_METERS
    }

    private fun Flow<Position>.distinctUntilChangedSignificantly(): Flow<Position> =
        distinctUntilChanged { a, b ->
            abs(a.timestamp - b.timestamp).milliseconds <= LOCATION_MAX_AGE_UNTIL_CHANGED &&
                a.latLng.distanceTo(b.latLng) <= LOCATION_MAX_DISTANCE_DIFF_UNTIL_CHANGED
        }

    fun createReports(): Flow<ReportData> = channelFlow {
        val mutex = Mutex()

        val wifiAccessPointByMacAddr = mutableMapOf<String, WifiAccessPoint>()
        val bluetoothBeaconsByMacAddr = mutableMapOf<String, BluetoothBeacon>()
        val cellTowersByKey = mutableMapOf<String, CellTower>()

        val isMovingFlow =
            movementDetector
                .getIsMovingFlow()
                .onEach {
                    if (it) {
                        Timber.i("Moving started, resuming scanning")
                    } else {
                        Timber.i("Moving stopped, pausing scanning")
                    }
                }
                .stateIn(this)

        launch(Dispatchers.Default) {
            startCollectingData(
                isMovingFlow,
                wifiAccessPointSource,
                mutex,
                wifiAccessPointByMacAddr,
            )
        }

        launch(Dispatchers.Default) {
            startCollectingData(
                isMovingFlow,
                bluetoothBeaconSource,
                mutex,
                bluetoothBeaconsByMacAddr,
            )
        }

        launch(Dispatchers.Default) {
            startCollectingData(isMovingFlow, cellInfoSource, mutex, cellTowersByKey)
        }

        isMovingFlow
            .flatMapLatest { isMoving ->
                if (isMoving) {
                    getLocationsWithAirPressure()
                } else {
                    emptyFlow()
                }
            }
            .filterInaccurateLocations()
            .distinctUntilChangedSignificantly()
            // Collect locations to a list so that we can choose the best based on timestamp
            .buffer(LOCATION_BUFFER_DURATION)
            .filter { it.isNotEmpty() }
            .map { locations ->
                val (cells, wifis, bluetooths) =
                    mutex.withLock {
                        val cells = cellTowersByKey.values.toList()
                        cellTowersByKey.clear()

                        // Take Wi-Fis only if there's at least two, because two are needed for a
                        // valid Geosubmit report
                        val wifis =
                            if (wifiAccessPointByMacAddr.size >= 2) {
                                wifiAccessPointByMacAddr.values.toList().apply {
                                    wifiAccessPointByMacAddr.clear()
                                }
                            } else {
                                emptyList()
                            }

                        val bluetooths = bluetoothBeaconsByMacAddr.values.toList()
                        bluetoothBeaconsByMacAddr.clear()

                        Triple(cells, wifis, bluetooths)
                    }

                createReports(locations, cells, wifis, bluetooths, postProcessors)
            }
            .collect { reports -> reports.forEach { report -> send(report) } }
    }
}
