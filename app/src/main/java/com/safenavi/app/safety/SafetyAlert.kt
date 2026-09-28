package com.safenavi.app.safety
import com.safenavi.app.data.SafetyPoint
data class SafetyAlert(val point:SafetyPoint,val distanceMeters:Double)
enum class AlertStage { NONE, M700, M300, M100, PASSED }
