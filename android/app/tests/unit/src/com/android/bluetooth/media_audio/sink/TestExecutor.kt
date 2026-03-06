/*
 * Copyright (C) 2025 The Android Open Source Project
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

package com.android.bluetooth.media_audio.sink

import java.util.ArrayDeque
import java.util.ArrayList
import java.util.concurrent.Callable
import java.util.concurrent.ExecutorService
import java.util.concurrent.Future
import java.util.concurrent.FutureTask
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.TimeUnit

/** A controllable [ExecutorService] for testing. */
class TestExecutor() : ExecutorService {
    private val tasks = ArrayDeque<Runnable>()
    private val taskLock = Any()
    private var dispatching = false
    private var autoDispatch = false
    private var isShutdown = false
    private var isTerminated = false

    // ---------------------------------------------------------------------------------------------
    // Control Methods (For Tests)
    // ---------------------------------------------------------------------------------------------

    /**
     * Executes all tasks currently in the queue, and any added, until the queue is empty
     *
     * @return The number of tasks executed.
     */
    fun dispatchAll(): Int {
        var count = 0
        while (dispatchNext()) {
            count++
        }
        return count
    }

    /**
     * Executes the next task in the queue.
     *
     * @return true if a task was executed, false if the queue was empty.
     */
    fun dispatchNext(): Boolean {
        if (tasks.isEmpty()) {
            return false
        }

        val task = tasks.removeFirst()
        task.run()
        return true
    }

    /**
     * Begins automatic processing of pending tasks
     *
     * This will first synchronously drain the pending queue. Then, any item added to the executor
     * will be handled in a block fashion, immediately after being added.
     */
    fun startAutoDispatch() {
        synchronized(taskLock) {
            autoDispatch = true
            dispatchAll()
        }
    }

    /**
     * Stops automatic processing of pending tasks. Processing will stop as soon as the current task
     * is complete. Some tasks may remain pending
     */
    fun stopAutoDispatch() {
        synchronized(taskLock) { autoDispatch = false }
    }

    /** Returns the number of tasks currently waiting to be executed. */
    fun pendingTaskCount(): Int {
        return tasks.size
    }

    // ---------------------------------------------------------------------------------------------
    // ExecutorService Implementation
    // ---------------------------------------------------------------------------------------------

    override fun execute(command: Runnable) {
        if (isShutdown) {
            throw RejectedExecutionException("TestExecutor is already shutdown")
        }

        tasks.add(command)

        synchronized(taskLock) {
            if (autoDispatch) {
                dispatchAll()
            }
        }
    }

    override fun <T : Any?> submit(task: Callable<T>): Future<T> {
        val ftask = FutureTask(task)
        execute(ftask)
        return ftask
    }

    override fun submit(task: Runnable): Future<*> {
        // This bypasses the static call to Executors.callable(), which can cause errors if you use
        // static mockito to mock and inject this TestExecutor via the various static functions
        // available on the Executors.java object.
        val ftask =
            FutureTask<Void> {
                task.run()
                null
            }
        execute(ftask)
        return ftask
    }

    override fun shutdown() {
        isShutdown = true
        for (r in tasks) {
            r.run()
        }
        checkTermination()
    }

    override fun shutdownNow(): List<Runnable> {
        isShutdown = true
        val remaining = ArrayList<Runnable>(tasks)
        tasks.clear()
        checkTermination()
        return remaining
    }

    override fun isShutdown(): Boolean = isShutdown

    override fun isTerminated(): Boolean = isTerminated

    override fun awaitTermination(timeout: Long, unit: TimeUnit): Boolean {
        shutdown()
        return isTerminated
    }

    private fun checkTermination() {
        if (isShutdown && pendingTaskCount() == 0) {
            isTerminated = true
        }
    }

    // ---------------------------------------------------------------------------------------------
    // Unsupported Operations
    // ---------------------------------------------------------------------------------------------

    override fun <T : Any?> submit(task: Runnable, result: T): Future<T> {
        throw UnsupportedOperationException(
            "submit(Runnable, result) not implemented in TestExecutor"
        )
    }

    override fun <T : Any?> invokeAll(
        tasks: MutableCollection<out Callable<T>>
    ): MutableList<Future<T>> {
        throw UnsupportedOperationException("invokeAll not implemented in TestExecutor")
    }

    override fun <T : Any?> invokeAll(
        tasks: MutableCollection<out Callable<T>>,
        timeout: Long,
        unit: TimeUnit,
    ): MutableList<Future<T>> {
        throw UnsupportedOperationException("invokeAll not implemented in TestExecutor")
    }

    override fun <T : Any?> invokeAny(tasks: MutableCollection<out Callable<T>>): T {
        throw UnsupportedOperationException("invokeAny not implemented in TestExecutor")
    }

    override fun <T : Any?> invokeAny(
        tasks: MutableCollection<out Callable<T>>,
        timeout: Long,
        unit: TimeUnit,
    ): T {
        throw UnsupportedOperationException("invokeAny not implemented in TestExecutor")
    }
}
