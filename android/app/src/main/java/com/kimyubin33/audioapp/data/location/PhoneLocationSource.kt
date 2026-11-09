package com.kimyubin33.audioapp.data.location

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Bundle
import android.os.Looper
import android.os.SystemClock
import androidx.core.content.ContextCompat
import androidx.core.location.LocationManagerCompat

enum class LocationProblem { Permission, Disabled, Unavailable, Waiting, Stale }

/** 화면의 START/STOP에 묶이는 전경 전용 수신기. 차량 연결 여부를 차량 GPS로 해석하지 않는다. */
class PhoneLocationSource(context: Context, private val onFix: (LocationSample) -> Unit, private val onProblem: (LocationProblem) -> Unit) {
    private val app = context.applicationContext
    private val manager = app.getSystemService(LocationManager::class.java)
    private var registered = false
    private var active = false

    fun hasPermission(): Boolean = granted(Manifest.permission.ACCESS_COARSE_LOCATION) || granted(Manifest.permission.ACCESS_FINE_LOCATION)
    fun precise(): Boolean = granted(Manifest.permission.ACCESS_FINE_LOCATION)
    private fun granted(permission: String): Boolean = ContextCompat.checkSelfPermission(app, permission) == PackageManager.PERMISSION_GRANTED
    private val listener = object : LocationListener {
        override fun onLocationChanged(location: Location) {
            if (!active) return
            if (!hasPermission()) { stop(); onProblem(LocationProblem.Permission); return }
            val sample = LocationSample(location.latitude, location.longitude, location.accuracy, location.elapsedRealtimeNanos / 1_000_000)
            if (sample.isFresh(SystemClock.elapsedRealtime())) onFix(sample)
        }
        override fun onProviderEnabled(provider: String) { if (active) start() }
        override fun onProviderDisabled(provider: String) {
            if (active && !LocationManagerCompat.isLocationEnabled(manager)) onProblem(LocationProblem.Disabled)
        }
        @Deprecated("Required on API 26–28")
        override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) = Unit
    }

    @SuppressLint("MissingPermission") // 각 API 직전에 권한을 다시 확인하고, 동시에 취소되는 경우도 처리한다.
    fun start() {
        stop()
        active = true
        if (!hasPermission()) { onProblem(LocationProblem.Permission); return }
        if (!LocationManagerCompat.isLocationEnabled(manager)) { onProblem(LocationProblem.Disabled); return }
        onProblem(LocationProblem.Waiting)
        try {
            val available = manager.allProviders
            // legacy fused 요청은 BALANCED 품질이라 GPS를 켜지 않을 수 있다.
            // 사용자가 정밀 위치를 허용한 전경 탐색은 GPS와 네트워크를 함께 사용한다.
            val providers = if (precise()) listOfNotNull(
                LocationManager.GPS_PROVIDER.takeIf { it in available },
                LocationManager.NETWORK_PROVIDER.takeIf { it in available },
            ) else if ("fused" in available) listOf("fused") else
                listOfNotNull(LocationManager.NETWORK_PROVIDER.takeIf { it in available })
            for (provider in providers) {
                if (!hasPermission()) { stop(); onProblem(LocationProblem.Permission); return }
                manager.requestLocationUpdates(provider, 10_000L, 0f, listener, Looper.getMainLooper())
                registered = true
                if (!hasPermission()) { stop(); onProblem(LocationProblem.Permission); return }
                manager.getLastKnownLocation(provider)?.let(listener::onLocationChanged)
            }
            if (!registered) onProblem(LocationProblem.Unavailable)
        } catch (_: SecurityException) {
            stop()
            onProblem(LocationProblem.Permission)
        } catch (_: IllegalArgumentException) {
            stop()
            onProblem(LocationProblem.Unavailable)
        }
    }

    fun stop() {
        active = false
        if (registered) {
            try { manager.removeUpdates(listener) } catch (_: SecurityException) { /* 권한이 취소된 경우 */ }
            registered = false
        }
    }
}
