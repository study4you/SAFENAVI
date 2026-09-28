package com.safenavi.app.safety
import kotlin.math.*

object GeoCalculator {
    private const val R = 6371000.0
    fun distanceMeters(aLat:Double,aLon:Double,bLat:Double,bLon:Double):Double {
        val p1=Math.toRadians(aLat); val p2=Math.toRadians(bLat)
        val dp=Math.toRadians(bLat-aLat); val dl=Math.toRadians(bLon-aLon)
        val a=sin(dp/2).pow(2)+cos(p1)*cos(p2)*sin(dl/2).pow(2)
        return R*2*atan2(sqrt(a),sqrt(1-a))
    }
    fun bearing(aLat:Double,aLon:Double,bLat:Double,bLon:Double):Double {
        val p1=Math.toRadians(aLat); val p2=Math.toRadians(bLat); val dl=Math.toRadians(bLon-aLon)
        val y=sin(dl)*cos(p2)
        val x=cos(p1)*sin(p2)-sin(p1)*cos(p2)*cos(dl)
        return (Math.toDegrees(atan2(y,x))+360)%360
    }
    fun angleDifference(a:Double,b:Double):Double {
        val d=abs(a-b)%360
        return if(d>180) 360-d else d
    }
}
