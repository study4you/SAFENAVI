package com.safenavi.app.voice
import android.content.Context
import android.speech.tts.TextToSpeech
import com.safenavi.app.data.SafetyPoint
import com.safenavi.app.safety.AlertStage
import java.util.Locale

class VoiceGuide(context:Context):TextToSpeech.OnInitListener {
    private val tts=TextToSpeech(context.applicationContext,this); private var ready=false
    override fun onInit(status:Int){ if(status==TextToSpeech.SUCCESS){tts.language=Locale.KOREAN;ready=true;tts.setSpeechRate(1.05f)}}
    fun announce(p:SafetyPoint,s:AlertStage){
        if(!ready||s==AlertStage.PASSED||s==AlertStage.NONE)return
        val d=when(s){AlertStage.M700->"700미터 앞";AlertStage.M300->"300미터 앞";else->"100미터 앞"}
        val type=when(p.type){"SPEED"->"과속 안전운행 구간";"SIGNAL_SPEED"->"신호 과속 안전운행 구간";"SECTION"->"구간 단속 구간";else->"안전운행 구간"}
        val msg=if(p.speedLimit!=null)"$d, 제한속도 ${p.speedLimit}킬로미터, $type입니다." else "$d, $type입니다."
        tts.speak(msg,TextToSpeech.QUEUE_FLUSH,null,"${p.id}_${s.name}")
    }
    fun shutdown(){tts.stop();tts.shutdown()}
}
