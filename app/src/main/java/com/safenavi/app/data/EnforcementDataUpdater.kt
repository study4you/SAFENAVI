package com.safenavi.app.data

import android.content.Context
import androidx.room.withTransaction
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

data class DataUpdateResult(
    val checked: Boolean,
    val updatedRegions: List<String>,
    val totalCount: Int,
    val message: String
)

class EnforcementDataUpdater(
    context: Context,
    private val db: SafetyDatabase
) {
    private val prefs = context.getSharedPreferences("enforcement_data", Context.MODE_PRIVATE)

    companion object {
        private const val BASE =
            "https://raw.githubusercontent.com/study4you/SAFENAVI/main/"
        private const val MANIFEST = "data/enforcement/manifest.json"
        private const val CHECK_INTERVAL = 6 * 60 * 60 * 1000L
    }

    suspend fun pruneLegacyData() = withContext(Dispatchers.IO) {
        if (!prefs.getBoolean("legacy_pruned", false)) {
            db.safetyPointDao().deleteNonEnforcement()
            prefs.edit().putBoolean("legacy_pruned", true).apply()
        }
    }

    suspend fun updateIfNeeded(force: Boolean = false): DataUpdateResult =
        withContext(Dispatchers.IO) {
            val now = System.currentTimeMillis()
            val lastCheck = prefs.getLong("last_manifest_check", 0L)
            if (!force && now - lastCheck < CHECK_INTERVAL) {
                return@withContext DataUpdateResult(
                    checked = false,
                    updatedRegions = emptyList(),
                    totalCount = db.safetyPointDao().count(),
                    message = "최근 데이터 확인 완료"
                )
            }

            val manifestText = downloadText(BASE + MANIFEST)
                ?: return@withContext DataUpdateResult(
                    checked = true,
                    updatedRegions = emptyList(),
                    totalCount = db.safetyPointDao().count(),
                    message = "데이터 서버 연결 실패"
                )

            val root = JSONObject(manifestText)
            val datasets = root.optJSONArray("datasets")
                ?: return@withContext DataUpdateResult(
                    checked = true,
                    updatedRegions = emptyList(),
                    totalCount = db.safetyPointDao().count(),
                    message = "데이터 목록 형식 오류"
                )

            val updated = mutableListOf<String>()

            for (i in 0 until datasets.length()) {
                val item = datasets.optJSONObject(i) ?: continue
                val region = item.optString("region").uppercase()
                val version = item.optString("version")
                val path = item.optString("path")

                if (region !in setOf("SEOUL", "GYEONGGI", "INCHEON")) continue
                if (version.isBlank() || path.isBlank()) continue

                val localVersion = prefs.getString("version_$region", null)
                if (!force && localVersion == version) continue

                val dataText = downloadText(BASE + path) ?: continue
                val points = parseDataset(region, dataText)

                db.withTransaction {
                    db.safetyPointDao().deleteByRegion(region)
                    if (points.isNotEmpty()) {
                        db.safetyPointDao().insertAll(points)
                    }
                }

                prefs.edit().putString("version_$region", version).apply()
                updated += region
            }

            prefs.edit().putLong("last_manifest_check", now).apply()

            val total = db.safetyPointDao().count()
            DataUpdateResult(
                checked = true,
                updatedRegions = updated,
                totalCount = total,
                message = if (updated.isEmpty()) {
                    "단속정보 최신 상태"
                } else {
                    "단속정보 업데이트: " + updated.joinToString(", ")
                }
            )
        }

    private fun parseDataset(region: String, json: String): List<SafetyPoint> {
        val root = JSONObject(json)
        val points = root.optJSONArray("points") ?: return emptyList()
        val result = ArrayList<SafetyPoint>(points.length())

        for (i in 0 until points.length()) {
            val p = points.optJSONObject(i) ?: continue
            val type = p.optString("type").uppercase()
            if (type !in setOf("SPEED", "SIGNAL_SPEED", "SECTION")) continue

            val lat = p.optDouble("latitude", Double.NaN)
            val lon = p.optDouble("longitude", Double.NaN)
            if (!lat.isFinite() || !lon.isFinite()) continue

            result += SafetyPoint(
                id = p.optLong("id"),
                region = region,
                latitude = lat,
                longitude = lon,
                type = type,
                speedLimit = if (p.has("speedLimit") && !p.isNull("speedLimit")) p.optInt("speedLimit") else null,
                roadName = p.optString("roadName").takeIf { it.isNotBlank() },
                locationName = p.optString("locationName").takeIf { it.isNotBlank() },
                direction = if (p.has("direction") && !p.isNull("direction")) p.optDouble("direction") else null,
                sectionType = p.optString("sectionType").takeIf { it.isNotBlank() },
                sectionLength = if (p.has("sectionLength") && !p.isNull("sectionLength")) p.optDouble("sectionLength") else null,
                protectedArea = false,
                dataDate = p.optString("dataDate").takeIf { it.isNotBlank() }
            )
        }
        return result
    }

    private fun downloadText(urlText: String): String? {
        val connection = (URL(urlText).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 8000
            readTimeout = 12000
            setRequestProperty("User-Agent", "SafeNavi/0.06")
            setRequestProperty("Cache-Control", "no-cache")
        }

        return try {
            if (connection.responseCode !in 200..299) return null
            connection.inputStream.bufferedReader().use { it.readText() }
        } catch (_: Exception) {
            null
        } finally {
            connection.disconnect()
        }
    }
}
