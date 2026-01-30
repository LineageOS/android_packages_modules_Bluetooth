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

package android.bluetooth.pairing.utils

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.util.Log
import com.google.common.truth.Truth.assertThat
import java.time.Duration
import org.hamcrest.Matcher
import org.hamcrest.core.AllOf
import org.mockito.InOrder
import org.mockito.Mock
import org.mockito.Mockito
import org.mockito.Mockito.any
import org.mockito.Mockito.doAnswer
import org.mockito.Mockito.inOrder
import org.mockito.Mockito.timeout
import org.mockito.Mockito.verify
import org.mockito.MockitoAnnotations
import org.mockito.hamcrest.MockitoHamcrest
import org.mockito.kotlin.whenever

private const val TAG = "IntentReceiver"

//
// IntentReceiver helps in managing the Intents received through the Broadcast
// receiver, with specific intent actions registered.
// It uses Builder pattern for instance creation, and also allows setting up
// a custom listener's onReceive().
//
// Use the following way to create an instance of the IntentReceiver.
//       IntentReceiver intentReceiver = new IntentReceiver.Builder(sTargetContext,
//           BluetoothDevice.ACTION_1,
//           BluetoothDevice.ACTION_2)
//           .setIntentListener(--) // optional
//           .setIntentTimeout(--)  // optional
//           .build();
//
// Ordered and unordered verification mechanisms are also provided through public methods.
//
class IntentReceiver private constructor(builder: Builder) {

    /** Interface for listening & processing the received intents */
    fun interface IntentListener {
        /**
         * Callback for receiving intents
         *
         * @param intent Received intent
         */
        fun onReceive(intent: Intent)
    }

    @Mock private lateinit var receiver: BroadcastReceiver

    /** To verify the received intents in-order */
    private val inOrder: InOrder

    private val dqBuilder = ArrayDeque<Builder>()
    private val context = builder.context

    /**
     * Creates an Intent receiver from the builder instance Note: This is a private constructor, so
     * always prepare IntentReceiver's instance through Builder().
     *
     * @param builder Pre-built builder instance
     */
    init {
        MockitoAnnotations.initMocks(this)
        inOrder = inOrder(receiver)
        dqBuilder.addFirst(builder)

        /* Perform other calls required for instantiation */
        setup()
    }

    //
    // Builder class which helps in avoiding overloading constructors (as the class grows)
    // Usage:
    //       new IntentReceiver.Builder(ARGS)
    //       .setterMethods() **Optional calls, as these are default params
    //       .build();
    //
    class Builder(internal val context: Context, vararg intentStrings: String) {
        internal var intentTimeout = Duration.ofSeconds(10)
        internal var intentListener: IntentListener? = null
        internal var intentFilter: IntentFilter

        /**
         * Creates a Builder instance with following required params
         *
         * @param context Context
         * @param intentStrings Array of intents to filter and register
         */
        init {
            if (intentStrings.isEmpty()) {
                throw RuntimeException("IntentReceiver.Builder(): No intents to register")
            }

            intentFilter = prepareIntentFilter(*intentStrings)
        }

        fun setIntentListener(intentListener: IntentListener?) = apply {
            this.intentListener = intentListener
        }

        fun setIntentTimeout(intentTimeout: Duration) = apply { this.intentTimeout = intentTimeout }

        /**
         * Builds and returns the IntentReceiver object with all the passed, and default params
         * supplied to Builder().
         */
        fun build() = IntentReceiver(this)

        private fun prepareIntentFilter(vararg intentStrings: String) =
            IntentFilter().apply { intentStrings.forEach { addAction(it) } }
    }

    /**
     * Verifies if the intent is received in order
     *
     * @param matchers Matchers
     */
    fun verifyReceivedOrdered(vararg matchers: Matcher<Intent>) {
        inOrder
            .verify(receiver, timeout(dqBuilder.first().intentTimeout.toMillis()))
            .onReceive(any(Context::class.java), MockitoHamcrest.argThat(AllOf.allOf(*matchers)))
    }

    /**
     * Verifies if requested number of intents are received (unordered)
     *
     * @param num Number of intents
     * @param matchers Matchers
     */
    fun verifyReceived(num: Int, vararg matchers: Matcher<Intent>) {
        verify(receiver, timeout(dqBuilder.first().intentTimeout.toMillis()).times(num))
            .onReceive(any(Context::class.java), MockitoHamcrest.argThat(AllOf.allOf(*matchers)))
    }

    /**
     * Verifies if the intent is received (unordered)
     *
     * @param matchers Matchers
     */
    fun verifyReceived(vararg matchers: Matcher<Intent>) {
        verifyReceived(1, *matchers)
    }

    /**
     * This function will make sure that the instance is properly cleared based on the registered
     * actions. Note: This function MUST be called before returning from the caller function, as
     * this either unregisters the latest registered actions, or free resources.
     */
    fun close() {
        Log.d(TAG, "close(): ${dqBuilder.size}")

        /* More than 1 Builders are present in deque */
        if (dqBuilder.size > 1) {
            rollbackBuilder()
        } else {
            // Only 1 builder remaining, safely close this instance.
            verifyNoMoreInteractions()
            teardown()
        }
    }

    /** Helper functions are added below, usually private */

    /** Registers the listener for the received intents, and perform a custom logic as required */
    private fun setupListener() {
        doAnswer { inv ->
                Log.d(TAG, "onReceive(): intent=${inv.arguments.contentToString()}")

                val listener = dqBuilder.firstOrNull()?.intentListener ?: return@doAnswer null
                val intent = inv.getArgument<Intent>(1)

                /* Custom `onReceive` will be provided by the caller */
                listener.onReceive(intent)
                null
            }
            .whenever(receiver)
            .onReceive(any(), any())
    }

    /**
     * Registers the latest intent filter which is at the deque.peekFirst() Note: The dqBuilder must
     * not be empty here.
     */
    private fun registerReceiver() {
        /* ArrayDeque should not be empty at all while registering a receiver */
        assertThat(dqBuilder.isEmpty()).isFalse()

        val intentFilter = dqBuilder.first().intentFilter
        Log.d(
            TAG,
            "registerReceiver(): Registering for intents: ${actionsFromIntentFilter(intentFilter)}",
        )
        context.registerReceiver(receiver, intentFilter)
    }

    //
    // Unregisters the receiver from the list of active receivers.
    // Also, we can now re-use the same receiver, or register a new
    //  receiver with the same or different intent filter, the old
    //  registration is no longer valid.
    //  Source: Intents and intent filters (Android Developers)
    //
    private fun unregisterReceiver() {
        Log.d(TAG, "unregisterReceiver()")
        context.unregisterReceiver(receiver)
    }

    /** Verifies that no more intents are received */
    private fun verifyNoMoreInteractions() {
        Log.d(TAG, "verifyNoMoreInteractions()")
        Mockito.verifyNoMoreInteractions(receiver)
    }

    //
    // Registers the new actions passed as argument.
    // 1. Unregister the Builder.
    // 2. Pops a new Builder to roll-back to the old one.
    // 3. Registers the old Builder.
    //
    private fun rollbackBuilder() {
        assertThat(dqBuilder.isEmpty()).isFalse()

        teardown()

        /* Restores the previous Builder, and discard the latest */
        dqBuilder.removeFirst()
        setup()
    }

    /**
     * Helper function to get the actions from the IntentFilter
     *
     * @param intentFilter IntentFilter instance
     *
     * This is a helper function to get the actions from the IntentFilter, and return as a String.
     */
    private fun actionsFromIntentFilter(intentFilter: IntentFilter): String {
        val allIntentActions = StringBuilder()
        intentFilter.actionsIterator().forEach { allIntentActions.append(it).append(", ") }
        return allIntentActions.toString()
    }

    /**
     * Helper function to perform the setup for the IntentReceiver instance
     *
     * This is a helper function to perform the setup for the IntentReceiver instance, which
     * includes setting up the listener, and registering the receiver, etc.
     */
    private fun setup() {
        setupListener()
        registerReceiver()
    }

    private fun teardown() {
        unregisterReceiver()
    }

    /**
     * Updates the current builder with the new builder instance.
     *
     * @param builder New builder instance
     */
    private fun updateBuilder(builder: Builder) {
        teardown()
        // Keep the new builder at the top of the deque
        dqBuilder.addFirst(builder)

        // calls all required setup functions based on the new builder
        setup()
    }

    companion object {
        /**
         * Registers the new builder instance with the parent IntentReceiver instance
         *
         * @param parentIntentReceiver Parent IntentReceiver instance
         * @param builder New builder instance
         *
         * Note: This is a helper function to be used in testStep functions where properties are
         * updated in the new builder instance, and then pushed to the parent instance.
         */
        fun update(parentIntentReceiver: IntentReceiver?, builder: Builder): IntentReceiver {
            if (parentIntentReceiver == null) return builder.build()

            parentIntentReceiver.updateBuilder(builder)
            return parentIntentReceiver
        }
    }
}
