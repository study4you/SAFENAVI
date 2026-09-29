package com.safenavi.app.data

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

data class OfflineDataResult(val ok:Boolean,val message:String)

class OfflineDataUpdater(private val context: Context) {
    companion object {
        private const val MAP_URL = "https://download.mapsforge.org/maps/v5/asia/south-korea.map"
        private const val GRAPH_URL = "https://download.mapsforge.org/graphs/asia/south-korea.zip"
    }
    private val prefs=context.getSharedPreferences("offline_drive_data",Context.MODE_PRIVATE)
    private val dir=File(context.filesDir,"offline").apply{mkdirs()}

    fun status():String {
        val map=File(dir,"south-korea.map")
        val graph=File(dir,"south-korea-graph.zip")
        fun mb(f:File)=if(f.exists()) "%.1fMB".format(f.length()/1048576.0) else "없음"
        return "오프라인 지도 "+mb(map)+" · 도로 그래프 "+mb(graph)
    }

    suspend fun update(onProgress:(String)->Unit):OfflineDataResult=withContext(Dispatchers.IO){
        try {
            download(MAP_URL,File(dir,"south-korea.map"),"지도",onProgress)
            download(GRAPH_URL,File(dir,"south-korea-graph.zip"),"도로",onProgress)
            prefs.edit().putLong("updated_at",System.currentTimeMillis()).apply()
            OfflineDataResult(true,"지도/도로 데이터 업데이트 완료")
        } catch(e:Exception) {
            OfflineDataResult(false,"지도/도로 업데이트 실패: "+(e.message?:"연결 오류"))
        }
    }

    private fun download(url:String,target:File,label:String,onProgress:(String)->Unit){
        val tmp=File(target.absolutePath+".part")
        val c=(URL(url).openConnection() as HttpURLConnection).apply{
            connectTimeout=15000;readTimeout=30000
            setRequestProperty("User-Agent","SafeNavi/19")
        }
        try {
            if(c.responseCode !in 200..299) error("HTTP "+c.responseCode)
            val total=c.contentLengthLong
            c.inputStream.use{input->tmp.outputStream().use{out->
                val buf=ByteArray(128*1024);var done=0L;var last=-1
                while(true){val n=input.read(buf);if(n<0)break;out.write(buf,0,n);done+=n
                    if(total>0){val pct=(done*100/total).toInt();if(pct!=last&&pct%2==0){last=pct;onProgress(label+" 다운로드 "+pct+"%")}}
                }
            }}
            if(tmp.length()<=0) error(label+" 파일이 비어 있음")
            if(target.exists()) target.delete()
            if(!tmp.renameTo(target)) error(label+" 파일 저장 실패")
        } finally { c.disconnect(); if(tmp.exists()&&!target.exists())tmp.delete() }
    }
}
