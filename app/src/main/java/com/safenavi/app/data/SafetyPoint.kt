package com.safenavi.app.data
import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "safety_points")
data class SafetyPoint(
    @PrimaryKey val id: Long,
    val region: String,
    val latitude: Double,
    val longitude: Double,
    val type: String,
    val speedLimit: Int?,
    val roadName: String?,
    val locationName: String?,
    val direction: Double?,
    val sectionType: String?,
    val sectionLength: Double?,
    val protectedArea: Boolean,
    val dataDate: String?
)
