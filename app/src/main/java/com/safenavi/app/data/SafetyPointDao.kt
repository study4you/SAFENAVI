package com.safenavi.app.data
import androidx.room.*

@Dao
interface SafetyPointDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(points: List<SafetyPoint>)
    @Query("""SELECT * FROM safety_points
        WHERE latitude BETWEEN :minLat AND :maxLat
        AND longitude BETWEEN :minLon AND :maxLon""")
    suspend fun findNearby(minLat: Double, maxLat: Double, minLon: Double, maxLon: Double): List<SafetyPoint>
    @Query("SELECT COUNT(*) FROM safety_points")
    suspend fun count(): Int
}
