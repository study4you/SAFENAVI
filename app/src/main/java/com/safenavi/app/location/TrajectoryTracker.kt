package com.safenavi.app.location
import android.location.Location
import com.safenavi.app.safety.GeoCalculator
import java.util.ArrayDeque

class TrajectoryTracker {
    private val points=ArrayDeque<Location>()
    fun add(l:Location){
        if(l.accuracy>40)return
        val last=points.lastOrNull()
        if(last!=null && last.distanceTo(l)<3)return
        points.addLast(Location(l)); while(points.size>10)points.removeFirst()
    }
    fun heading():Double? {
        if(points.size<3)return null
        val list=points.toList(); val a=list[list.size-3]; val b=list.last()
        if(a.distanceTo(b)<8)return null
        return GeoCalculator.bearing(a.latitude,a.longitude,b.latitude,b.longitude)
    }
}
