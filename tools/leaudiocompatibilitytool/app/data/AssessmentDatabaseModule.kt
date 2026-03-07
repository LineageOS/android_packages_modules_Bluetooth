/*
 * Copyright (C) 2026 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
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

import android.bluetooth.tools.leaudiocompatibilitytool.app.data.assessment.AssessmentDao
import android.content.Context
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob

@Module
@InstallIn(SingletonComponent::class)
internal object AssessmentDatabaseModule {
    @Provides
    @Singleton
    fun provideAssessmentDatabase(@ApplicationContext context: Context): AssessmentDatabase {
        val applicationScope = CoroutineScope(SupervisorJob())
        return AssessmentDatabase.getDatabase(context, applicationScope)
    }

    @Provides
    @Singleton
    fun provideAssessmentDao(database: AssessmentDatabase): AssessmentDao {
        return database.assessmentDao()
    }
}
