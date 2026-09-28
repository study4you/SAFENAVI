package com.safenavi.app.location

import android.Manifest
import android.app.*
import android.content.*
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.media.AudioManager
import android.media.ToneGenerator
import android.os.*
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationCompat
import com.safenavi.app.MainActivity
import com.safenavi.app.data.EnforcementDataUpdater
import com.safenavi.app.data.SafetyDatabase
import com.safenavi.app.safety.*
import com.safenavi.app.voice.VoiceGuide
import kotlinx.coroutines.*
import kotlin.math.cos
import kotlin.math.roundToInt

class DrivingLocationService : Service(), LocationListener {

    companion object {
        const val ACTION_STOP = "com.safenavi.app.STOP_DRIVE"
        const val ACTION_APP_FOREGROUND = "com.safenavi.app.APP_FOREGROUND"
        const val ACTION_APP_BACKGROUND = "com.safenavi.app.APP_BACKGROUND"
    }

    private lateinit var lm: LocationManager
    private lateinit var db: SafetyDatabase
    private lateinit var voice: VoiceGuide
    private lateinit var dataUpdater: EnforcementDataUpdater
    private lateinit var windowManager: WindowManager

    private val engine = SafetyEngine()
    private val tracker = AlertTracker()
    private val trajectory = TrajectoryTracker()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val roadSnapper = RoadSnapper()

    private var overlayView: View? = null
    private var speedText: TextView? = null
    private var distanceText: TextView? = null
    private var limitText: TextView? = null
    private var warningText: TextView? = null

    private val handler = Handler(Looper.getMainLooper())
    private var toneGenerator: ToneGenerator? = null
    private var speeding = false
    private var appInBackground = false

    private val warningTone = object : Runnable {
        override fun run() {
            if (!speeding) return
            toneGenerator?.startTone(ToneGenerator.TONE_CDMA_ALERT_CALL_GUARD, 650)
            handler.postDelayed(this, 1700L)
        }
    }

    private val visibilityReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                ACTION_APP_FOREGROUND -> {
                    appInBackground = false
                    hideOverlay()
                }
                ACTION_APP_BACKGROUND -> {
                    appInBackground = true
                    showOverlayIfAllowed()
                }
            }
        }
    }

    override fun onCreate() {
        super.onCreate()

        db = SafetyDatabase.getInstance(this)
        voice = VoiceGuide(this)
        dataUpdater = EnforcementDataUpdater(this, db)
        lm = getSystemService(Context.LOCATION_SERVICE) as LocationManager
        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        toneGenerator = ToneGenerator(AudioManager.STREAM_ALARM, 95)

        val filter = IntentFilter().apply {
            addAction(ACTION_APP_FOREGROUND)
            addAction(ACTION_APP_BACKGROUND)
        }
        if (Build.VERSION.SDK_INT >= 33) {
            registerReceiver(visibilityReceiver, filter, RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("DEPRECATION")
            registerReceiver(visibilityReceiver, filter)
        }

        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(
                "drive",
                "안전운행",
                NotificationManager.IMPORTANCE_LOW
            )
        )
        nm.createNotificationChannel(
            NotificationChannel(
                "speed_warning",
                "속도위반 경고",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                enableVibration(true)
                description = "제한속도를 초과한 동안 경고합니다."
            }
        )
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }

        startForeground(1001, buildDriveNotification())

        scope.launch {
            dataUpdater.pruneLegacyData()
            dataUpdater.updateIfNeeded()
        }

        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
            == PackageManager.PERMISSION_GRANTED
        ) {
            lm.removeUpdates(this)
            lm.requestLocationUpdates(
                LocationManager.GPS_PROVIDER,
                1000L,
                1f,
                this
            )
        }

        return START_STICKY
    }

    private fun buildDriveNotification(): Notification {
        val stopIntent = Intent(this, DrivingLocationService::class.java).apply {
            action = ACTION_STOP
        }
        val stopPending = PendingIntent.getService(
            this, 10, stopIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val openPending = PendingIntent.getActivity(
            this, 11,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, "drive")
            .setContentTitle("SafeNavi 안전운행 중")
            .setContentText("백그라운드에서도 안전운행 정보를 안내합니다.")
            .setSmallIcon(android.R.drawable.ic_menu_mylocation)
            .setContentIntent(openPending)
            .addAction(
                android.R.drawable.ic_menu_close_clear_cancel,
                "안내 종료",
                stopPending
            )
            .setOngoing(true)
            .build()
    }

    override fun onLocationChanged(location: Location) {
        if (location.accuracy > 50) return

        trajectory.add(location)
        val speedKmh = if (location.hasSpeed()) {
            (location.speed * 3.6f).roundToInt()
        } else 0

        val heading = trajectory.heading()
            ?: if (location.hasBearing()) location.bearing.toDouble() else null

        if (heading == null) {
            handler.post { updateOverlay(speedKmh, null, null, false) }
            return
        }

        scope.launch {
            val snapped = roadSnapper.snap(location)
            val currentLat = snapped?.latitude ?: location.latitude
            val currentLon = snapped?.longitude ?: location.longitude

            val r = 2000.0
            val latD = r / 111320.0
            val lonD = r / (111320.0 * cos(Math.toRadians(currentLat)))

            val pts = db.safetyPointDao().findNearby(
                currentLat - latD,
                currentLat + latD,
                currentLon - lonD,
                currentLon + lonD
            )

            val alerts = engine.findAhead(
                currentLat,
                currentLon,
                heading,
                pts
            )

            alerts.forEach { alert ->
                tracker.update(alert)?.let { stage ->
                    voice.announce(alert.point, stage)
                }
            }

            val nearest = alerts.firstOrNull()
            val limit = nearest?.point?.speedLimit
            val distance = nearest?.distanceMeters?.roundToInt()
            val isSpeeding = limit != null && speedKmh > limit

            withContext(Dispatchers.Main) {
                setSpeedingState(isSpeeding, speedKmh, limit, distance)
                updateOverlay(speedKmh, distance, limit, isSpeeding)
            }
        }
    }

    private fun setSpeedingState(
        violation: Boolean,
        speedKmh: Int,
        limit: Int?,
        distance: Int?
    ) {
        if (violation && !speeding) {
            speeding = true
            handler.removeCallbacks(warningTone)
            handler.post(warningTone)
        } else if (!violation && speeding) {
            speeding = false
            handler.removeCallbacks(warningTone)
            toneGenerator?.stopTone()
            getSystemService(NotificationManager::class.java).cancel(2002)
        }

        if (violation && limit != null) {
            val text = buildString {
                append("현재 ")
                append(speedKmh)
                append("km/h · 제한 ")
                append(limit)
                append("km/h")
                distance?.let {
                    append(" · 전방 ")
                    append(it)
                    append("m")
                }
            }

            val notification = NotificationCompat.Builder(this, "speed_warning")
                .setSmallIcon(android.R.drawable.stat_sys_warning)
                .setContentTitle("제한속도 초과")
                .setContentText(text)
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setOnlyAlertOnce(true)
                .setOngoing(true)
                .build()

            getSystemService(NotificationManager::class.java)
                .notify(2002, notification)
        }
    }

    private fun showOverlayIfAllowed() {
        if (!appInBackground) return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M &&
            !Settings.canDrawOverlays(this)
        ) return
        if (overlayView != null) return

        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(20, 14, 20, 14)
            background = GradientDrawable().apply {
                setColor(Color.argb(235, 18, 23, 29))
                cornerRadius = 30f
                setStroke(2, Color.argb(120, 255, 255, 255))
            }
            elevation = 16f
        }

        speedText = TextView(this).apply {
            text = "0 km/h"
            setTextColor(Color.WHITE)
            textSize = 22f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
        }

        distanceText = TextView(this).apply {
            text = "전방 정보 없음"
            setTextColor(Color.LTGRAY)
            textSize = 13f
        }

        limitText = TextView(this).apply {
            text = ""
            setTextColor(Color.rgb(130, 190, 255))
            textSize = 13f
        }

        warningText = TextView(this).apply {
            text = ""
            setTextColor(Color.rgb(255, 90, 90))
            textSize = 14f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
        }

        panel.addView(speedText)
        panel.addView(distanceText)
        panel.addView(limitText)
        panel.addView(warningText)

        panel.setOnClickListener {
            val i = Intent(this, MainActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            }
            startActivity(i)
        }

        val params = WindowManager.LayoutParams(
            dp(150),
            WindowManager.LayoutParams.WRAP_CONTENT,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            else
                @Suppress("DEPRECATION")
                WindowManager.LayoutParams.TYPE_PHONE,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.END
            x = dp(12)
            y = dp(110)
        }

        overlayView = panel
        windowManager.addView(panel, params)
    }

    private fun hideOverlay() {
        overlayView?.let {
            try { windowManager.removeView(it) } catch (_: Exception) {}
        }
        overlayView = null
        speedText = null
        distanceText = null
        limitText = null
        warningText = null
    }

    private fun updateOverlay(
        speedKmh: Int,
        distanceMeters: Int?,
        limit: Int?,
        isSpeeding: Boolean
    ) {
        if (appInBackground) showOverlayIfAllowed()

        speedText?.text = "$speedKmh km/h"
        distanceText?.text = if (distanceMeters != null) {
            "전방 안전구간 ${distanceMeters}m"
        } else {
            "전방 안전정보 없음"
        }
        limitText?.text = if (limit != null) {
            "제한속도 ${limit}km/h"
        } else {
            ""
        }
        warningText?.text = if (isSpeeding) {
            "⚠ 제한속도 초과"
        } else {
            ""
        }
    }

    override fun onDestroy() {
        speeding = false
        handler.removeCallbacks(warningTone)
        toneGenerator?.stopTone()
        toneGenerator?.release()
        hideOverlay()

        try { unregisterReceiver(visibilityReceiver) } catch (_: Exception) {}
        lm.removeUpdates(this)
        voice.shutdown()
        scope.cancel()
        getSystemService(NotificationManager::class.java).cancel(2002)

        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun dp(value: Int): Int =
        (value * resources.displayMetrics.density).roundToInt()
}
