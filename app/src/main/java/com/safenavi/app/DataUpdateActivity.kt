package com.safenavi.app

import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.CheckBox
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.safenavi.app.data.EnforcementDataUpdater
import com.safenavi.app.data.SafetyDatabase
import com.safenavi.app.data.OfflineDataUpdater
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class DataUpdateActivity : AppCompatActivity() {

    private lateinit var updater: EnforcementDataUpdater
    private lateinit var statusText: TextView
    private lateinit var updateButton: Button
    private lateinit var offlineUpdater: OfflineDataUpdater
    private val boxes = linkedMapOf<String, CheckBox>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_data_update)

        updater = EnforcementDataUpdater(this, SafetyDatabase.getInstance(this))
        offlineUpdater = OfflineDataUpdater(this)
        statusText = findViewById(R.id.updateStatus)
        updateButton = findViewById(R.id.updateSelectedButton)

        bindRegions()
        loadSelection()

        findViewById<Button>(R.id.selectAllButton).setOnClickListener {
            boxes.values.forEach { it.isChecked = true }
        }

        findViewById<Button>(R.id.selectDefaultButton).setOnClickListener {
            boxes.forEach { (code, box) ->
                box.isChecked = code in EnforcementDataUpdater.DEFAULT_REGIONS
            }
        }

        updateButton.setOnClickListener {
            val selected = boxes
                .filterValues { it.isChecked }
                .keys
                .toSet()

            updater.saveSelectedRegions(selected)
            updateButton.isEnabled = false
            statusText.text = "선택 지역 업데이트 확인 중..."

            lifecycleScope.launch(Dispatchers.IO) {
                val result = updater.updateRegions(selected, force = true)
                withContext(Dispatchers.Main) { statusText.text = result.message + " · 지도/도로 다운로드 준비" }
                val offline = offlineUpdater.update { progress ->
                    runOnUiThread { statusText.text = result.message + "\n" + progress }
                }
                withContext(Dispatchers.Main) {
                    updateButton.isEnabled = true
                    statusText.text = result.message + " · 저장 " + result.totalCount + "건\n" + offline.message + "\n" + offlineUpdater.status()
                    refreshVersions()
                }
            }
        }

        refreshVersions()
        statusText.text = "업데이트 대기 · " + offlineUpdater.status()
    }

    private fun bindRegions() {
        boxes["SEOUL"] = findViewById(R.id.cbSeoul)
        boxes["BUSAN"] = findViewById(R.id.cbBusan)
        boxes["DAEGU"] = findViewById(R.id.cbDaegu)
        boxes["INCHEON"] = findViewById(R.id.cbIncheon)
        boxes["GWANGJU"] = findViewById(R.id.cbGwangju)
        boxes["DAEJEON"] = findViewById(R.id.cbDaejeon)
        boxes["ULSAN"] = findViewById(R.id.cbUlsan)
        boxes["SEJONG"] = findViewById(R.id.cbSejong)
        boxes["GYEONGGI"] = findViewById(R.id.cbGyeonggi)
        boxes["GANGWON"] = findViewById(R.id.cbGangwon)
        boxes["CHUNGBUK"] = findViewById(R.id.cbChungbuk)
        boxes["CHUNGNAM"] = findViewById(R.id.cbChungnam)
        boxes["JEONBUK"] = findViewById(R.id.cbJeonbuk)
        boxes["JEONNAM"] = findViewById(R.id.cbJeonnam)
        boxes["GYEONGBUK"] = findViewById(R.id.cbGyeongbuk)
        boxes["GYEONGNAM"] = findViewById(R.id.cbGyeongnam)
        boxes["JEJU"] = findViewById(R.id.cbJeju)
    }

    private fun loadSelection() {
        val selected = updater.getSelectedRegions()
        boxes.forEach { (code, box) ->
            box.isChecked = code in selected
        }
    }

    private fun refreshVersions() {
        val installed = boxes.keys.mapNotNull { code ->
            updater.getRegionVersion(code)?.let { v ->
                "${EnforcementDataUpdater.ALL_REGIONS[code]} v$v"
            }
        }

        findViewById<TextView>(R.id.versionStatus).text =
            if (installed.isEmpty()) {
                "저장된 지역 데이터 없음"
            } else {
                "저장 데이터: " + installed.joinToString(" · ")
            }
    }
}
