package com.safenavi.app.safety
import com.safenavi.app.data.SafetyPoint

class SafetyEngine {
    fun findAhead(lat:Double,lon:Double,heading:Double,points:List<SafetyPoint>,currentRoadName:String?=null):List<SafetyAlert> =
        points.mapNotNull { p ->
            val d=GeoCalculator.distanceMeters(lat,lon,p.latitude,p.longitude)
            if(d>2000) return@mapNotNull null
            val b=GeoCalculator.bearing(lat,lon,p.latitude,p.longitude)
            val angle=GeoCalculator.angleDifference(heading,b)
            if(angle>18) return@mapNotNull null
            val lateral=d * kotlin.math.sin(Math.toRadians(angle))
            if(lateral>30.0) return@mapNotNull null
            p.direction?.let { if(GeoCalculator.angleDifference(heading,it)>20) return@mapNotNull null }
            if(currentRoadName!=null) {
                if(p.roadName==null || !p.roadName.equals(currentRoadName,true)) return@mapNotNull null
            }
            SafetyAlert(p,d)
        }.sortedBy { it.distanceMeters }
}
