package com.safenavi.app

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.widget.Button
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.safenavi.app.location.DrivingLocationService

class MainActivity : AppCompatActivity() {
    private lateinit var status: TextView
    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) startDrive() else status.text = "위치 권한이 필요합니다."
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        status = findViewById(R.id.status)
        findViewById<Button>(R.id.startButton).setOnClickListener {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
                == PackageManager.PERMISSION_GRANTED) startDrive()
            else permissionLauncher.launch(Manifest.permission.ACCESS_FINE_LOCATION)
        }
        findViewById<Button>(R.id.stopButton).setOnClickListener {
            stopService(Intent(this, DrivingLocationService::class.java))
            status.text = "안전운행 종료"
        }
    }
    private fun startDrive() {
        ContextCompat.startForegroundService(this, Intent(this, DrivingLocationService::class.java))
        status.text = "안전운행 중"
    }
}
