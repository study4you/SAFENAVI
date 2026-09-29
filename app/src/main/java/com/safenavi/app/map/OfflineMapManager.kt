package com.safenavi.app.map

import android.app.Application
import android.content.Context
import org.mapsforge.map.android.graphics.AndroidGraphicFactory
import org.osmdroid.mapsforge.MapsForgeTileProvider
import org.osmdroid.mapsforge.MapsForgeTileSource
import org.osmdroid.tileprovider.util.SimpleRegisterReceiver
import org.osmdroid.views.MapView
import java.io.File

class OfflineMapManager(private val context: Context) {
    private var provider: MapsForgeTileProvider? = null
    private var source: MapsForgeTileSource? = null

    fun mapFile(): File = File(context.filesDir, "offline/south-korea.map")
    fun isInstalled(): Boolean = mapFile().let { it.exists() && it.length() > 1024L * 1024L }

    fun attach(map: MapView): Boolean {
        if (!isInstalled()) return false
        return try {
            if (AndroidGraphicFactory.INSTANCE == null) {
                AndroidGraphicFactory.createInstance(context.applicationContext as Application)
            }
            MapsForgeTileSource.createInstance(context.applicationContext as Application)
            detach()
            source = MapsForgeTileSource.createFromFiles(arrayOf(mapFile()), null, "safenavi-offline-v20")
            provider = MapsForgeTileProvider(SimpleRegisterReceiver(context), source, null)
            map.setTileProvider(provider)
            map.setUseDataConnection(false)
            map.invalidate()
            true
        } catch (_: Exception) {
            detach()
            false
        }
    }

    fun detach() {
        try { provider?.detach() } catch (_: Exception) {}
        try { source?.dispose() } catch (_: Exception) {}
        provider = null
        source = null
    }
}
