package com.safenavi.app.location
import android.Manifest
import android.app.*
import android.content.Context
import android.content.pm.PackageManager
import android.location.*
import android.os.IBinder
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationCompat
import com.safenavi.app.data.SafetyDatabase
import com.safenavi.app.data.SafetyDataSync
import com.safenavi.app.safety.*
import com.safenavi.app.voice.VoiceGuide
import kotlinx.coroutines.*

class DrivingLocationService:Service(),LocationListener {
    private lateinit var lm:LocationManager; private lateinit var db:SafetyDatabase; private lateinit var voice:VoiceGuide; private lateinit var safetySync:SafetyDataSync
    private val engine=SafetyEngine(); private val tracker=AlertTracker(); private val trajectory=TrajectoryTracker()
    private val scope=CoroutineScope(SupervisorJob()+Dispatchers.IO)
    override fun onCreate(){super.onCreate();db=SafetyDatabase.getInstance(this);voice=VoiceGuide(this);safetySync=SafetyDataSync(this,db)
        lm=getSystemService(Context.LOCATION_SERVICE) as LocationManager
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel("drive","안전운행",NotificationManager.IMPORTANCE_LOW))}
    override fun onStartCommand(i:android.content.Intent?,f:Int,id:Int):Int{
        startForeground(1001,NotificationCompat.Builder(this,"drive").setContentTitle("SafeNavi 안전운행 중")
            .setContentText("GPS 기반 안전정보를 확인합니다.").setSmallIcon(android.R.drawable.ic_menu_mylocation).setOngoing(true).build())
        if(ActivityCompat.checkSelfPermission(this,Manifest.permission.ACCESS_FINE_LOCATION)==PackageManager.PERMISSION_GRANTED)
            lm.requestLocationUpdates(LocationManager.GPS_PROVIDER,1000L,2f,this)
        return START_STICKY
    }
    override fun onLocationChanged(l:Location){
        if(l.accuracy>40)return; trajectory.add(l)
        val h=trajectory.heading() ?: if(l.hasBearing())l.bearing.toDouble() else return
        scope.launch {
            safetySync.syncNearbyIfNeeded(l.latitude,l.longitude)
            val r=2000.0; val latD=r/111320.0
            val lonD=r/(111320.0*kotlin.math.cos(Math.toRadians(l.latitude)))
            val pts=db.safetyPointDao().findNearby(l.latitude-latD,l.latitude+latD,l.longitude-lonD,l.longitude+lonD)
            engine.findAhead(l.latitude,l.longitude,h,pts).forEach { a -> tracker.update(a)?.let { voice.announce(a.point,it) } }
        }
    }
    override fun onDestroy(){lm.removeUpdates(this);voice.shutdown();scope.cancel();super.onDestroy()}
    override fun onBind(i:android.content.Intent?):IBinder?=null
}
