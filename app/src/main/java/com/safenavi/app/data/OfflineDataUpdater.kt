package com.safenavi.app.data

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.zip.ZipInputStream

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
        val graphDir=File(dir,"south-korea-graph")
        fun mb(f:File)=if(f.exists()) "%.1fMB".format(f.length()/1048576.0) else "없음"
        val installed=graphDir.exists() && graphDir.walkTopDown().any { it.isFile }
        val graphInfo=File(graphDir,"safenavi-graph-info.txt")
        val graphInfoLines=if(graphInfo.exists()) graphInfo.readLines() else emptyList()
        val graphType=graphInfoLines.firstOrNull{it.startsWith("type=")}?.substringAfter("=")
        val graphVersion=graphInfoLines.firstOrNull{
            it.startsWith("graph.version") || it.startsWith("datareader.import.date")
        }?.substringAfter("=")
        return "오프라인 지도 "+mb(map)+" · 도로 그래프 "+mb(graph)+
            (if(installed) " · 설치됨"+(graphType?.let{" · $it"}?:"")+(graphVersion?.let{" · $it"}?:"") else "")
    }

    suspend fun update(onProgress:(String)->Unit):OfflineDataResult=withContext(Dispatchers.IO){
        try {
            download(MAP_URL,File(dir,"south-korea.map"),"지도",onProgress)
            val graphZip=File(dir,"south-korea-graph.zip")
            download(GRAPH_URL,graphZip,"도로",onProgress)
            installGraph(graphZip,File(dir,"south-korea-graph"),onProgress)
            prefs.edit().putLong("updated_at",System.currentTimeMillis()).apply()
            OfflineDataResult(true,"지도/도로 데이터 업데이트 완료")
        } catch(e:Exception) {
            OfflineDataResult(false,"지도/도로 업데이트 실패: "+(e.message?:"연결 오류"))
        }
    }

    private fun installGraph(zip:File,targetDir:File,onProgress:(String)->Unit){
        val staging=File(dir,"south-korea-graph.installing")
        if(staging.exists()) staging.deleteRecursively()
        staging.mkdirs()
        val root=staging.canonicalFile
        var count=0
        ZipInputStream(zip.inputStream().buffered()).use { zin ->
            while(true){
                val entry=zin.nextEntry ?: break
                val out=File(staging,entry.name).canonicalFile
                if(out.path != root.path && !out.path.startsWith(root.path+File.separator)) {
                    error("잘못된 도로 그래프 경로")
                }
                if(entry.isDirectory) out.mkdirs() else {
                    out.parentFile?.mkdirs()
                    out.outputStream().buffered().use { output -> zin.copyTo(output,128*1024) }
                    count++
                    if(count%25==0) onProgress("도로 그래프 설치 "+count+"개 파일")
                }
                zin.closeEntry()
            }
        }
        if(count==0) error("도로 그래프 압축파일이 비어 있음")
        val files=staging.walkTopDown().filter{it.isFile}.toList()
        val names=files.map{it.name.lowercase()}
        val properties=files.firstOrNull{it.name.equals("properties",true)}
        val propertyLines=properties?.takeIf{it.length() in 1..262144}?.readLines().orEmpty()
        val type=when {
            properties!=null && names.any{it.contains("edges")} -> "GraphHopper"
            names.any{it.endsWith(".gh")} -> "GraphHopper"
            else -> "unknown"
        }
        val graphVersion=propertyLines.firstOrNull{
            it.startsWith("graph.version") || it.startsWith("datareader.import.date") ||
            it.startsWith("graph.encoded_values")
        } ?: "version=unknown"
        File(staging,"safenavi-graph-info.txt").writeText(
            "type=$type\n$graphVersion\nfiles=$count\n"+
            propertyLines.take(80).joinToString("\n")+"\n--files--\n"+
            names.take(80).joinToString("\n")
        )
        if(targetDir.exists()) targetDir.deleteRecursively()
        if(!staging.renameTo(targetDir)) error("도로 그래프 설치 실패")
        onProgress("도로 그래프 설치 완료 · "+count+"개 파일 · "+type)
    }

    private fun download(url:String,target:File,label:String,onProgress:(String)->Unit){
        val tmp=File(target.absolutePath+".part")
        val c=(URL(url).openConnection() as HttpURLConnection).apply{
            connectTimeout=15000;readTimeout=30000
            setRequestProperty("User-Agent","SafeNavi/21")
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
