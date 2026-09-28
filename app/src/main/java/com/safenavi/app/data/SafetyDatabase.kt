package com.safenavi.app.data
import android.content.Context
import androidx.room.*

@Database(entities=[SafetyPoint::class], version=1, exportSchema=false)
abstract class SafetyDatabase: RoomDatabase() {
    abstract fun safetyPointDao(): SafetyPointDao
    companion object {
        @Volatile private var instance: SafetyDatabase? = null
        fun getInstance(context: Context): SafetyDatabase =
            instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext, SafetyDatabase::class.java, "safenavi.db"
                ).build().also { instance = it }
            }
    }
}
