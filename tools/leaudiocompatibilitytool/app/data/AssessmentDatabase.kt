/*
 * Copyright (C) 2026 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package android.bluetooth.tools.leaudiocompatibilitytool.app.data

import android.bluetooth.tools.leaudiocompatibilitytool.app.data.assessment.Assessment
import android.bluetooth.tools.leaudiocompatibilitytool.app.data.assessment.AssessmentDao
import android.bluetooth.tools.leaudiocompatibilitytool.app.data.assessment.Run
import android.bluetooth.tools.leaudiocompatibilitytool.app.data.assessment.SelectedTestItem
import android.bluetooth.tools.leaudiocompatibilitytool.app.data.assessment.TestItem
import android.bluetooth.tools.leaudiocompatibilitytool.app.data.assessment.TestItemUiModel
import android.bluetooth.tools.leaudiocompatibilitytool.app.data.converter.InstantConverter
import android.bluetooth.tools.leaudiocompatibilitytool.app.data.device.Device
import android.bluetooth.tools.leaudiocompatibilitytool.app.data.device.SelectedDevice
import android.bluetooth.tools.leaudiocompatibilitytool.app.data.logInfo.LogRecord
import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import androidx.sqlite.db.SupportSQLiteDatabase
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

@Database(
    entities =
        [
            Assessment::class,
            TestItem::class,
            Run::class,
            Device::class,
            SelectedDevice::class,
            SelectedTestItem::class,
            LogRecord::class,
        ],
    version = AssessmentDatabaseConsts.DatabaseSettings.DB_VERSION,
)
@TypeConverters(InstantConverter::class)
abstract class AssessmentDatabase : RoomDatabase() {
    abstract fun assessmentDao(): AssessmentDao

    /** Callback class for inserting initial test items into the database. */
    private class SetUpCallback(private val scope: CoroutineScope) : RoomDatabase.Callback() {
        override fun onCreate(db: SupportSQLiteDatabase) {
            super.onCreate(db)
            databaseInstance?.let { database ->
                scope.launch { populateInitialData(database.assessmentDao()) }
            }
        }

        /**
         * Populates the initial data into the database. This method first checks if the test item
         * table has already contained data to prevent duplicate insertion. If not, it inserts all
         * the test items' ids.
         */
        private suspend fun populateInitialData(assessmentDao: AssessmentDao) {
            if (assessmentDao.getTestItemCount() == 0) {
                assessmentDao.insertTestItems(
                    TestItemUiModel.LIST.map { TestItem(testItemId = it.testItemId) }
                )
            }
        }
    }

    companion object {
        @Volatile private var databaseInstance: AssessmentDatabase? = null

        /**
         * Gets the database instance.
         *
         * @param context The context of the application.
         * @param scope The coroutine scope to run the database operations.
         * @return The database instance.
         */
        fun getDatabase(context: Context, scope: CoroutineScope): AssessmentDatabase {
            return databaseInstance
                ?: synchronized(this) {
                    val instance =
                        Room.databaseBuilder(
                                context,
                                AssessmentDatabase::class.java,
                                AssessmentDatabaseConsts.DatabaseSettings.DB_NAME,
                            )
                            .addCallback(SetUpCallback(scope))
                            .build()
                    databaseInstance = instance
                    instance
                }
        }
    }
}

/** Constants for the assessment database. */
object AssessmentDatabaseConsts {
    object DatabaseSettings {
        const val DB_NAME = "assessment_database"
        const val DB_VERSION = 1
    }
}
