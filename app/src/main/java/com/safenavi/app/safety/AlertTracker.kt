package com.safenavi.app.safety
private data class State(var stage:AlertStage=AlertStage.NONE,var last:Double=Double.MAX_VALUE,
    var approach:Int=0,var min:Double=Double.MAX_VALUE,var away:Int=0)

class AlertTracker {
    private val states=mutableMapOf<Long,State>()
    fun update(a:SafetyAlert):AlertStage? {
        val s=states.getOrPut(a.point.id){State()}; val d=a.distanceMeters
        if(s.stage==AlertStage.PASSED) return null
        if(d<s.last-3) s.approach++ else if(d>s.last+10) s.approach=0
        if(d<s.min){s.min=d;s.away=0}else if(d>s.min+15)s.away++
        s.last=d
        if(s.min<120 && s.away>=2){s.stage=AlertStage.PASSED;return AlertStage.PASSED}
        if(s.approach<2)return null
        val n=when { d<=100->AlertStage.M100; d<=300->AlertStage.M300; d<=700->AlertStage.M700; else->AlertStage.NONE }
        val rank=mapOf(AlertStage.NONE to 0,AlertStage.M700 to 1,AlertStage.M300 to 2,AlertStage.M100 to 3,AlertStage.PASSED to 4)
        if(rank.getValue(n)<=rank.getValue(s.stage))return null
        s.stage=n; return n
    }
}
