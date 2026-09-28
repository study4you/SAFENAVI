package com.safenavi.app.voice
import android.content.Context
import android.speech.tts.TextToSpeech
import com.safenavi.app.data.SafetyPoint
import com.safenavi.app.safety.AlertStage
import java.util.Locale
import android.speech.tts.Voice
import android.media.AudioAttributes
import java.util.ArrayDeque

class VoiceGuide(context:Context):TextToSpeech.OnInitListener {
    private val tts=TextToSpeech(context.applicationContext,this)
    private var ready=false
    private val pending=ArrayDeque<Pair<SafetyPoint,AlertStage>>()
    override fun onInit(status:Int){
        if(status==TextToSpeech.SUCCESS){
            val languageResult=tts.setLanguage(Locale.KOREAN)
            if(languageResult==TextToSpeech.LANG_MISSING_DATA || languageResult==TextToSpeech.LANG_NOT_SUPPORTED){
                ready=false
                return
            }
            tts.setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build()
            )
            val current=tts.voice
            val voices=tts.voices?.filter {
                it.locale.language==Locale.KOREAN.language && !it.isNetworkConnectionRequired
            }?.sortedWith(compareByDescending<Voice>{it.quality}.thenBy{it.name}).orEmpty()
            (voices.firstOrNull { it.name != current?.name } ?: voices.firstOrNull())?.let { tts.voice=it }
            tts.setSpeechRate(0.96f)
            tts.setPitch(1.0f)
            ready=true
            while(pending.isNotEmpty()){
                val (point,stage)=pending.removeFirst()
                speak(point,stage)
            }
        }
    }
    fun announce(p:SafetyPoint,s:AlertStage){
        if(s==AlertStage.PASSED||s==AlertStage.NONE)return
        if(!ready){
            if(pending.none { it.first.id==p.id && it.second==s }) pending.addLast(p to s)
            return
        }
        speak(p,s)
    }
    private fun speak(p:SafetyPoint,s:AlertStage){
        val d=when(s){AlertStage.M700->"700미터 앞";AlertStage.M300->"300미터 앞";else->"100미터 앞"}
        val type=when(p.type){"SPEED"->"속도 준수 구간";"SIGNAL_SPEED"->"신호 및 속도 준수 구간";"SECTION"->"구간 속도 준수 구간";else->"안전운행 구간"}
        val msg=if(p.speedLimit!=null)"$d, 제한속도 ${p.speedLimit}킬로미터, ${type}입니다." else "$d, ${type}입니다."
        tts.speak(msg,TextToSpeech.QUEUE_ADD,null,"${p.id}_${s.name}")
    }
    fun shutdown(){tts.stop();tts.shutdown()}
}
