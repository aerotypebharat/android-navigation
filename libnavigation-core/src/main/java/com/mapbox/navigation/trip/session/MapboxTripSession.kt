package com.mapbox.navigation.trip.session

import android.location.Location
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.mapbox.android.core.location.LocationEngine
import com.mapbox.android.core.location.LocationEngineCallback
import com.mapbox.android.core.location.LocationEngineRequest
import com.mapbox.android.core.location.LocationEngineResult
import com.mapbox.navigation.base.route.model.Route
import com.mapbox.navigation.base.trip.model.RouteProgress
import com.mapbox.navigation.navigator.MapboxNativeNavigator
import com.mapbox.navigation.trip.service.TripService
import com.mapbox.navigation.utils.ThreadController
import java.lang.ref.WeakReference
import java.util.Date
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.InternalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.Channel.Factory.CONFLATED
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@InternalCoroutinesApi
@ExperimentalCoroutinesApi
class MapboxTripSession(
    override val tripService: TripService,
    override val locationEngine: LocationEngine,
    override val locationEngineRequest: LocationEngineRequest,
    private val navigator: MapboxNativeNavigator,
    // TODO Remove and fix usages as property "mainHandler" is never used
    private val mainHandler: Handler,
    private val workerHandler: Handler
) : TripSession {

    override var route: Route? = null
        set(value) {
            field = value
            if (value != null) {
                workerHandler.post { navigator.setRoute(value) }
            }
        }

    private val mainLocationCallback =
        MainLocationCallback(this)

    private val locationObservers = CopyOnWriteArrayList<TripSession.LocationObserver>()
    private val routeProgressObservers = CopyOnWriteArrayList<TripSession.RouteProgressObserver>()

    private var rawLocation: Location? = null
    private var enhancedLocation: Location? = null
    private var routeProgress: RouteProgress? = null

//    private val serviceStateListener = object : TripService.StateListener {
//        override fun onStateChanged(state: Any) {
//            TODO("not implemented")
//        }
//    }

    private val navigatorLocationUpdateRunnable = Runnable {
        rawLocation?.let { navigator.updateLocation(it) }
    }

    private var listenLocationUpdatesJob: Job = Job()

    private var navigatorPollingJob: Job = Job()

    override fun getRawLocation() = rawLocation

    override fun getEnhancedLocation() = enhancedLocation

    override fun getRouteProgress() = routeProgress

    // TODO Remove / integrate as part of start()
    //  Currently temporal for testing purposes
    fun startLocationUpdates() {
        if (!locationChannel.isClosedForSend) {
            locationChannel.cancel()
        }
        locationChannel = Channel(CONFLATED)
        locationEngine.requestLocationUpdates(
            locationEngineRequest,
            locationEngineCallback,
            Looper.getMainLooper()
        )
        listenLocationUpdates()
    }

    override fun start() {
        tripService.startService()
        locationEngine.requestLocationUpdates(
            locationEngineRequest,
            mainLocationCallback,
            Looper.getMainLooper()
        )
        navigatorPollingJob = ThreadController.scope.launch {
            navigatorPolling()
            delay(STATUS_POLLING_INTERVAL)
        }
    }

    private suspend fun navigatorPolling() {
        val status = navigator.getStatus(Date())
        withContext(Dispatchers.Main) {
            updateEnhancedLocation(status.enhancedLocation)
            updateRouteProgress(status.routeProgress)
        }
    }

    private fun listenLocationUpdates() {
        listenLocationUpdatesJob = ThreadController.scope.launch {
            while (isActive) {
                when (!locationChannel.isClosedForReceive) {
                    true -> {
                        val location = locationChannel.receive()
                        Log.d("DEBUG", "$location")
                        updateLocation(location)
                    }
                    false -> {
                        Log.d(
                            "DEBUG",
                            "location channel is closed for receive"
                        )
                    }
                }
            }
        }
    }

    override fun stop() {
        tripService.stopService()
        locationEngine.removeLocationUpdates(mainLocationCallback)
        navigatorPollingJob.cancel()
        workerHandler.removeCallbacks(navigatorLocationUpdateRunnable)
    }

    // TODO Remove / integrate as part of stop()
    //  Currently temporal for testing purposes
    fun stopLocationUpdates() {
        locationEngine.removeLocationUpdates(locationEngineCallback)
        listenLocationUpdatesJob.cancel()
        if (!locationChannel.isClosedForSend) {
            locationChannel.cancel()
        }
    }

    override fun registerLocationObserver(locationObserver: TripSession.LocationObserver) {
        locationObservers.add(locationObserver)
        rawLocation?.let { locationObserver.onRawLocationChanged(it) }
        enhancedLocation?.let { locationObserver.onEnhancedLocationChanged(it) }
    }

    override fun unregisterLocationObserver(locationObserver: TripSession.LocationObserver) {
        locationObservers.remove(locationObserver)
    }

    override fun registerRouteProgressObserver(routeProgressObserver: TripSession.RouteProgressObserver) {
        routeProgressObservers.add(routeProgressObserver)
        routeProgress?.let { routeProgressObserver.onRouteProgressChanged(it) }
    }

    override fun unregisterRouteProgressObserver(routeProgressObserver: TripSession.RouteProgressObserver) {
        routeProgressObservers.remove(routeProgressObserver)
    }

    private var locationEngineCallback = object : LocationEngineCallback<LocationEngineResult> {
        override fun onSuccess(result: LocationEngineResult?) {
            result?.locations?.firstOrNull()?.let {
                when (!locationChannel.isClosedForSend) {
                    true -> locationChannel.offer(it)
                    false -> Log.d(
                        "DEBUG",
                        "location channel is closed for send"
                    )
                }
            }
        }

        override fun onFailure(exception: Exception) {
            Log.d("DEBUG", "location on failure", exception)
            stopLocationUpdates()
        }
    }

    // TODO Remove, will be replaced by locationEngineCallback
    //  Currently duplicated / not used for testing purposes
    private class MainLocationCallback(tripSession: MapboxTripSession) :
        LocationEngineCallback<LocationEngineResult> {

        private val tripSessionReference = WeakReference(tripSession)

        override fun onSuccess(result: LocationEngineResult?) {
            result?.locations?.firstOrNull()?.let {
                tripSessionReference.get()?.updateRawLocation(it)
            }
        }

        override fun onFailure(exception: Exception) {
            TODO("not implemented")
        }
    }

    // TODO Remove / integrate as part of updateRawLocation()
    //  Currently temporal for testing purposes
    private fun updateLocation(rawLocation: Location) {
        locationObservers.forEach { it.onRawLocationChanged(rawLocation) }
    }

    private fun updateRawLocation(rawLocation: Location) {
        this.rawLocation = rawLocation
        workerHandler.post(navigatorLocationUpdateRunnable)
        locationObservers.forEach { it.onRawLocationChanged(rawLocation) }
    }

    private fun updateEnhancedLocation(enhancedLocation: Location) {
        this.enhancedLocation = enhancedLocation
        locationObservers.forEach { it.onEnhancedLocationChanged(enhancedLocation) }
    }

    private fun updateRouteProgress(routeProgress: RouteProgress) {
        this.routeProgress = routeProgress
        routeProgressObservers.forEach { it.onRouteProgressChanged(routeProgress) }
    }

    companion object {
        private const val STATUS_POLLING_INTERVAL = 1000L
        private var locationChannel = Channel<Location>(CONFLATED)
    }
}
