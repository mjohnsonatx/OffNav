package com.example.offnav.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(entities = [RouteHistoryEntity::class], version = 2, exportSchema = true)
abstract class OffNavDatabase : RoomDatabase() {

    abstract fun routeHistoryDao(): RouteHistoryDao

    companion object {
        fun build(context: Context): OffNavDatabase =
            Room.databaseBuilder(
                context.applicationContext,
                OffNavDatabase::class.java,
                "offnav.db"
            )
                // Room already does all IO on its own executor; never allow main-thread queries.
                .fallbackToDestructiveMigration()   // replace with real migrations before shipping
                .build()
    }
}