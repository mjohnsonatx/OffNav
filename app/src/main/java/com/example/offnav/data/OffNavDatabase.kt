package com.example.offnav.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [RouteHistoryEntity::class, ActivityEntity::class, TrackPointEntity::class],
    version = 3,
    exportSchema = true,
)
abstract class OffNavDatabase : RoomDatabase() {

    abstract fun routeHistoryDao(): RouteHistoryDao
    abstract fun activityDao(): ActivityDao

    companion object {

        /** v2 → v3: adds activity recording. Purely additive; route_history untouched. */
        private val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `recorded_activity` (
                        `id` INTEGER NOT NULL PRIMARY KEY AUTOINCREMENT,
                        `uuid` TEXT NOT NULL,
                        `type` TEXT NOT NULL,
                        `status` TEXT NOT NULL,
                        `title` TEXT NOT NULL,
                        `note` TEXT NOT NULL,
                        `startedAt` INTEGER NOT NULL,
                        `endedAt` INTEGER,
                        `elapsedMillis` INTEGER NOT NULL,
                        `activeMillis` INTEGER NOT NULL,
                        `movingMillis` INTEGER NOT NULL,
                        `distanceMeters` REAL NOT NULL,
                        `maxSpeedMps` REAL NOT NULL,
                        `elevationGainMeters` REAL NOT NULL,
                        `elevationLossMeters` REAL NOT NULL,
                        `elevationSource` TEXT NOT NULL,
                        `minLat` REAL NOT NULL DEFAULT 0,
                        `minLon` REAL NOT NULL DEFAULT 0,
                        `maxLat` REAL NOT NULL DEFAULT 0,
                        `maxLon` REAL NOT NULL DEFAULT 0,
                        `pointCount` INTEGER NOT NULL
                    )
                    """.trimIndent()
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_recorded_activity_startedAt` ON `recorded_activity` (`startedAt`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_recorded_activity_status` ON `recorded_activity` (`status`)")

                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `track_point` (
                        `id` INTEGER NOT NULL PRIMARY KEY AUTOINCREMENT,
                        `activityId` INTEGER NOT NULL,
                        `segment` INTEGER NOT NULL,
                        `timestamp` INTEGER NOT NULL,
                        `lat` REAL NOT NULL,
                        `lon` REAL NOT NULL,
                        `altitudeMeters` REAL,
                        `accuracyMeters` REAL NOT NULL,
                        `speedMps` REAL,
                        FOREIGN KEY(`activityId`) REFERENCES `recorded_activity`(`id`)
                            ON UPDATE NO ACTION ON DELETE CASCADE
                    )
                    """.trimIndent()
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_track_point_activityId_timestamp` ON `track_point` (`activityId`, `timestamp`)")
            }
        }

        fun build(context: Context): OffNavDatabase =
            Room.databaseBuilder(
                context.applicationContext,
                OffNavDatabase::class.java,
                "offnav.db",
            )
                .addMigrations(MIGRATION_2_3)
                // Cascade deletes require this; Room does NOT enable it by default on older paths.
                .build()
    }
}