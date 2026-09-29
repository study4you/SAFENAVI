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

data class DownloadProgress(val percent: Int, val bytesPerSecond: Long, val label: String)

class EnforcementDataUpdater(
    private val context: Context,
    private val db: SafetyDatabase
) {
    private val prefs = context.getSharedPreferences("enforcement_data", Context.MODE_PRIVATE)

    companion object {
        private const val BASE =
            "https://raw.githubusercontent.com/study4you/SAFENAVI/main/"
        private const val MANIFEST = "data/enforcement/manifest.json"
        private const val REQUIRED_SCHEMA = 2
        private const val CHECK_INTERVAL = 6 * 60 * 60 * 1000L

        val ALL_REGIONS = linkedMapOf(
            "SEOUL" to "서울",
            "BUSAN" to "부산",
            "DAEGU" to "대구",
            "INCHEON" to "인천",
            "GWANGJU" to "광주",
            "DAEJEON" to "대전",
            "ULSAN" to "울산",
            "SEJONG" to "세종",
            "GYEONGGI" to "경기",
            "GANGWON" to "강원",
            "CHUNGBUK" to "충북",
            "CHUNGNAM" to "충남",
            "JEONBUK" to "전북",
            "JEONNAM" to "전남",
            "GYEONGBUK" to "경북",
            "GYEONGNAM" to "경남",
            "JEJU" to "제주"
        )

        val DEFAULT_REGIONS = setOf("SEOUL", "GYEONGGI", "INCHEON")
    }

    suspend fun pruneLegacyData() = withContext(Dispatchers.IO) {
        if (!prefs.getBoolean("legacy_pruned", false)) {
            db.safetyPointDao().deleteNonEnforcement()
            prefs.edit().putBoolean("legacy_pruned", true).apply()
        }
    }

    fun getSelectedRegions(): Set<String> {
        val saved = prefs.getStringSet("selected_regions", null)
        return saved?.toSet() ?: DEFAULT_REGIONS
    }

    fun saveSelectedRegions(regions: Set<String>) {
        prefs.edit().putStringSet("selected_regions", regions).apply()
    }

    fun getRegionVersion(region: String): String? =
        prefs.getString("version_${region.uppercase()}", null)

    suspend fun updateIfNeeded(force: Boolean = false): DataUpdateResult {
        return updateRegions(getSelectedRegions(), force)
    }

    suspend fun updateRegions(
        selectedRegions: Set<String>,
        force: Boolean = true,
        onProgress: ((DownloadProgress) -> Unit)? = null
    ): DataUpdateResult = withContext(Dispatchers.IO) {
        val normalized = selectedRegions
            .map { it.uppercase() }
            .filter { it in ALL_REGIONS.keys }
            .toSet()

        if (normalized.isEmpty()) {
            return@withContext DataUpdateResult(
                checked = false,
                updatedRegions = emptyList(),
                totalCount = db.safetyPointDao().count(),
                message = "선택된 지역이 없습니다"
            )
        }

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
        if (root.optInt("schema", -1) != REQUIRED_SCHEMA) {
            return@withContext DataUpdateResult(
                checked = true,
                updatedRegions = emptyList(),
                totalCount = db.safetyPointDao().count(),
                message = "지원하지 않는 단속정보 데이터 형식"
            )
        }

        val datasets = root.optJSONArray("datasets")
            ?: return@withContext DataUpdateResult(
                checked = true,
                updatedRegions = emptyList(),
                totalCount = db.safetyPointDao().count(),
                message = "데이터 목록 형식 오류"
            )

        val updated = mutableListOf<String>()
        val failed = mutableListOf<String>()

        for (i in 0 until datasets.length()) {
            val item = datasets.optJSONObject(i) ?: continue
            val region = item.optString("region").uppercase()
            val version = item.optString("version")
            val path = item.optString("path")

            if (region !in normalized) continue
            if (region !in ALL_REGIONS.keys) continue
            if (version.isBlank() || path.isBlank()) continue

            val localVersion = prefs.getString("version_$region", null)
            if (!force && localVersion == version) continue

            onProgress?.invoke(DownloadProgress((i * 100 / datasets.length()).coerceIn(0, 99), 0, ALL_REGIONS[region] ?: region))
            val expectedBytes = item.optLong("bytes", -1L)
            if (path.startsWith("/") || path.contains("..") || !path.startsWith("data/enforcement/")) {
                failed += region
                continue
            }
            if (!path.lowercase().endsWith(".json")) {
                failed += region
                continue
            }
            val dataText = downloadText(BASE + path) { read, total, speed ->
                val knownTotal = if (expectedBytes > 0L) expectedBytes else total
                val filePercent = if (knownTotal > 0) (read * 100 / knownTotal).toInt().coerceIn(0, 100) else 0
                val overall = (((i.toDouble() + filePercent / 100.0) / datasets.length()) * 100).toInt().coerceIn(0, 99)
                onProgress?.invoke(DownloadProgress(overall, speed, ALL_REGIONS[region] ?: region))
            }
            if (dataText == null) {
                failed += region
                continue
            }

            if (expectedBytes > 0L && dataText.toByteArray(Charsets.UTF_8).size.toLong() != expectedBytes) {
                failed += region
                continue
            }
            val datasetRoot = try { JSONObject(dataText) } catch (_: Exception) {
                failed += region
                continue
            }
            val datasetSchema = datasetRoot.optInt("schema", REQUIRED_SCHEMA)
            if (datasetSchema != REQUIRED_SCHEMA ||
                datasetRoot.optString("region").uppercase() != region ||
                datasetRoot.optString("version") != version
            ) {
                failed += region
                continue
            }
            val points = parseDataset(region, dataText)
            val duplicateIds = points.groupingBy { it.id }.eachCount().any { it.value > 1 }
            if (duplicateIds) {
                failed += region
                continue
            }
            val declaredCount = item.optInt("count", -1)
            val declaredSha256 = item.optString("sha256").lowercase()
            // Production datasets must declare count + SHA-256 so a truncated,
            // stale or tampered file can never replace the installed region.
            val actualSha256 = sha256(dataText)
            if (declaredCount < 1 ||
                declaredSha256.length != 64 ||
                actualSha256 != declaredSha256 ||
                points.size != declaredCount
            ) {
                failed += region
                continue
            }
            if (points.isEmpty()) {
                // Never replace a valid installed region with a corrupt/empty download.
                failed += region
                continue
            }

            db.withTransaction {
                db.safetyPointDao().deleteByRegion(region)
                if (points.isNotEmpty()) {
                    db.safetyPointDao().insertAll(points)
                }
            }

            prefs.edit().putString("version_$region", version).apply()
            updated += region
        }

        saveSelectedRegions(normalized)
        prefs.edit().putLong("last_manifest_check", now).apply()

        val total = db.safetyPointDao().count()
        val message = when {
            failed.isNotEmpty() && updated.isNotEmpty() ->
                "일부 업데이트 완료 / 실패: " + failed.joinToString(", ")
            failed.isNotEmpty() ->
                "업데이트 실패: " + failed.joinToString(", ")
            updated.isEmpty() ->
                "선택 지역 단속정보 최신 상태"
            else ->
                "업데이트 완료: " + updated.joinToString(", ")
        }

        onProgress?.invoke(DownloadProgress(100, 0, "완료"))

        DataUpdateResult(
            checked = true,
            updatedRegions = updated,
            totalCount = total,
            message = message
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
            // Reject obviously corrupt coordinates outside the Korean service area.
            if (lat !in 32.0..39.5 || lon !in 123.0..132.5) continue

            val id = p.optLong("id", 0L)
            if (id <= 0L) continue
            val speedLimit = if (p.has("speedLimit") && !p.isNull("speedLimit")) p.optInt("speedLimit") else null
            if (speedLimit != null && speedLimit !in 10..130) continue

            result += SafetyPoint(
                id = id,
                region = region,
                latitude = lat,
                longitude = lon,
                type = type,
                speedLimit = speedLimit,
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

    private fun sha256(text: String): String =
        java.security.MessageDigest.getInstance("SHA-256")
            .digest(text.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }

    private fun downloadText(urlText: String, progress: ((Long, Long, Long) -> Unit)? = null): String? {
        val connection = (URL(urlText).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 8000
            readTimeout = 12000
            setRequestProperty("User-Agent", "SafeNavi/0.07")
            setRequestProperty("Cache-Control", "no-cache")
        }

        return try {
            if (connection.responseCode !in 200..299) return null
            val total = connection.contentLengthLong
            val started = android.os.SystemClock.elapsedRealtime()
            var read = 0L
            val out = java.io.ByteArrayOutputStream()
            connection.inputStream.use { input ->
                val buffer = ByteArray(16 * 1024)
                while (true) {
                    val n = input.read(buffer)
                    if (n < 0) break
                    out.write(buffer, 0, n)
                    read += n
                    val elapsed = (android.os.SystemClock.elapsedRealtime() - started).coerceAtLeast(1L)
                    progress?.invoke(read, total, read * 1000L / elapsed)
                }
            }
            out.toString(Charsets.UTF_8.name())
        } catch (_: Exception) {
            null
        } finally {
            connection.disconnect()
        }
    }
}
