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

    @Query("DELETE FROM safety_points WHERE region = :region")
    suspend fun deleteByRegion(region: String)

    @Query("DELETE FROM safety_points WHERE type NOT IN ('SPEED','SIGNAL_SPEED','SECTION')")
    suspend fun deleteNonEnforcement()

    @Query("DELETE FROM safety_points")
    suspend fun deleteAll()
}
