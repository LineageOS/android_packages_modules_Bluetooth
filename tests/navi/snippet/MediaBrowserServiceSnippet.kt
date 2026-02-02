/*
 * Copyright 2025 The Android Open Source Project
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

package com.google.android.bluetooth.snippet

import android.app.Activity
import android.content.Intent
import android.util.Log
import androidx.media3.common.MediaItem
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.LibraryResult
import androidx.media3.session.MediaLibraryService
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSession.ControllerInfo
import androidx.test.platform.app.InstrumentationRegistry
import com.google.android.mobly.snippet.Snippet
import com.google.android.mobly.snippet.rpc.AsyncRpc
import com.google.android.mobly.snippet.rpc.Rpc
import com.google.android.mobly.snippet.rpc.RunOnUiThread
import com.google.common.collect.ImmutableList
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout

/** Snippet for MediaBrowserService. */
class MediaBrowserServiceSnippet : Snippet {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    internal var mediaLibrarySession: MediaLibraryService.MediaLibrarySession? = null

    private val mediaTreeNodes = mutableMapOf<String, Utils.MediaNode>()

    /**
     * Android Bluetooth identify media player apps by querying for activies receiving
     * android.intent.action.VIEW(audio), so we need to provide a placeholder activity here.
     */
    class EmptyActivity : Activity() {}

    init {
        instance = this
    }

    /** Implementation of [MediaLibraryService] to provide media library session. */
    class MediaLibraryServiceImpl : MediaLibraryService() {

        override fun onCreate() {
            super.onCreate()
            Log.d(TAG, "onCreate")
            instance.value = this
        }

        override fun onDestroy() {
            super.onDestroy()
            Log.d(TAG, "onDestroy")
            instance.value = null
        }

        override fun onGetSession(
            controllerInfo: ControllerInfo
        ): MediaLibraryService.MediaLibrarySession? {
            Log.d(TAG, "onGetSession")
            return MediaBrowserServiceSnippet.instance?.mediaLibrarySession
        }

        companion object {
            private const val TAG = "MediaLibraryServiceImpl"
            internal val instance = MutableStateFlow<MediaLibraryServiceImpl?>(null)
        }
    }

    private fun cacheMediaTreeNodes(mediaTreeRoot: Utils.MediaNode) {
        mediaTreeNodes[mediaTreeRoot.item.mediaId] = mediaTreeRoot
        for (child in mediaTreeRoot.children) {
            cacheMediaTreeNodes(child)
        }
    }

    @AsyncRpc(description = "Register media library session")
    fun registerMediaLibrarySession(callbackId: String, mediaTreeRoot: Utils.MediaNode) {
        context.startService(Intent(context, MediaLibraryServiceImpl::class.java))
        // Wait for MediaLibraryServiceImpl to be initiated.
        val mediaLibraryService =
            runBlocking {
                withTimeout(10.seconds) { MediaLibraryServiceImpl.instance.first { it != null } }
            } ?: throw IllegalStateException("MediaLibraryServiceImpl is not initiated")

        mediaTreeNodes.clear()
        cacheMediaTreeNodes(mediaTreeRoot)

        mediaLibrarySession =
            MediaLibraryService.MediaLibrarySession.Builder(
                    mediaLibraryService,
                    ExoPlayer.Builder(context).build(),
                    object : MediaLibraryService.MediaLibrarySession.Callback {
                        override fun onGetLibraryRoot(
                            session: MediaLibraryService.MediaLibrarySession,
                            browser: MediaSession.ControllerInfo,
                            params: MediaLibraryService.LibraryParams?,
                        ): ListenableFuture<LibraryResult<MediaItem>> {
                            Log.d(TAG, "onGetLibraryRoot")
                            return Futures.immediateFuture(
                                LibraryResult.ofItem(mediaTreeRoot.item, null)
                            )
                        }

                        override fun onGetChildren(
                            session: MediaLibraryService.MediaLibrarySession,
                            browser: MediaSession.ControllerInfo,
                            parentId: String,
                            page: Int,
                            pageSize: Int,
                            params: MediaLibraryService.LibraryParams?,
                        ): ListenableFuture<LibraryResult<ImmutableList<MediaItem>>> {
                            Log.d(TAG, "onGetChildren $parentId")
                            val node = mediaTreeNodes[parentId]
                            val children = node?.children ?: listOf()
                            return Futures.immediateFuture(
                                LibraryResult.ofItemList(children.map { it.item }, null)
                            )
                        }

                        override fun onConnect(
                            session: MediaSession,
                            controller: MediaSession.ControllerInfo,
                        ): MediaSession.ConnectionResult {
                            Log.d(TAG, "onConnect")
                            return super.onConnect(session, controller)
                        }

                        override fun onAddMediaItems(
                            session: MediaSession,
                            controller: MediaSession.ControllerInfo,
                            mediaItems: List<MediaItem>,
                        ): ListenableFuture<List<MediaItem>> {
                            for (mediaItem in mediaItems) {
                                Utils.postSnippetEvent(
                                    callbackId,
                                    SnippetConstants.MEDIA_ITEM_ADDED,
                                ) {
                                    putString(SnippetConstants.FIELD_ID, mediaItem.mediaId)
                                }
                            }
                            return super.onAddMediaItems(session, controller, mediaItems)
                        }
                    },
                )
                .setId(callbackId)
                .build()
    }

    @RunOnUiThread
    @Rpc(description = "Release media library session")
    fun unregisterMediaLibrarySession(callbackId: String) {
        mediaLibrarySession?.run {
            player.release()
            release()
        }
        mediaLibrarySession = null
        mediaTreeNodes.clear()
    }

    private companion object {
        const val TAG = "MediaBrowserServiceSnippet"
        var instance: MediaBrowserServiceSnippet? = null
    }
}
