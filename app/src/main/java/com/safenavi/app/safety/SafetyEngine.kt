package com.safenavi.app.safety
import com.safenavi.app.data.SafetyPoint

class SafetyEngine {
    fun findAhead(lat:Double,lon:Double,heading:Double,points:List<SafetyPoint>,currentRoadName:String?=null):List<SafetyAlert> =
        points.mapNotNull { p ->
            val d=GeoCalculator.distanceMeters(lat,lon,p.latitude,p.longitude)
            if(d>2000) return@mapNotNull null
            val b=GeoCalculator.bearing(lat,lon,p.latitude,p.longitude)
            if(GeoCalculator.angleDifference(heading,b)>28) return@mapNotNull null
            p.direction?.let { if(GeoCalculator.angleDifference(heading,it)>28) return@mapNotNull null }
            if(currentRoadName!=null && p.roadName!=null && !p.roadName.equals(currentRoadName,true)) return@mapNotNull null
            SafetyAlert(p,d)
        }.sortedBy { it.distanceMeters }
}
