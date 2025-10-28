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

package android.bluetooth.pairing.utils;

import static com.google.common.truth.Truth.assertThat;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;

import static java.util.Objects.requireNonNull;

import android.annotation.NonNull;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.util.Log;

import org.hamcrest.Matcher;
import org.hamcrest.core.AllOf;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.MockitoAnnotations;
import org.mockito.hamcrest.MockitoHamcrest;

import java.time.Duration;
import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.Deque;
import java.util.Iterator;

//
// IntentReceiver helps in managing the Intents received through the Broadcast
//  receiver, with specific intent actions registered.
//  It uses Builder pattern for instance creation, and also allows setting up
//  a custom listener's onReceive().
//
// Use the following way to create an instance of the IntentReceiver.
//      IntentReceiver intentReceiver = new IntentReceiver.Builder(sTargetContext,
//          BluetoothDevice.ACTION_1,
//          BluetoothDevice.ACTION_2)
//          .setIntentListener(--) // optional
//          .setIntentTimeout(--)  // optional
//          .build();
//
// Ordered and unordered verification mechanisms are also provided through public methods.
//
public class IntentReceiver {
    private static final String TAG = IntentReceiver.class.getSimpleName();

    /** Interface for listening & processing the received intents */
    public interface IntentListener {
        /**
         * Callback for receiving intents
         *
         * @param intent Received intent
         */
        void onReceive(Intent intent);
    }

    @Mock private BroadcastReceiver mReceiver;

    /** To verify the received intents in-order */
    private final InOrder mInOrder;

    private final Deque<Builder> mDqBuilder;
    private final Context mContext;

    /**
     * Creates an Intent receiver from the builder instance Note: This is a private constructor, so
     * always prepare IntentReceiver's instance through Builder().
     *
     * @param builder Pre-built builder instance
     */
    private IntentReceiver(Builder builder) {
        MockitoAnnotations.initMocks(this);
        mInOrder = inOrder(mReceiver);
        mDqBuilder = new ArrayDeque<>();
        mDqBuilder.addFirst(builder);

        // Context will remain same for all the builders in the deque
        mContext = builder.mContext;

        /* Perform other calls required for instantiation */
        setup();
    }

    /** Private constructor to avoid creation of IntentReceiver instance directly */
    private IntentReceiver() {
        mInOrder = null;
        mContext = null;
        mDqBuilder = null;
    }

    //
    // Builder class which helps in avoiding overloading constructors (as the class grows)
    // Usage:
    //      new IntentReceiver.Builder(ARGS)
    //      .setterMethods() **Optional calls, as these are default params
    //      .build();
    //
    public static class Builder {
        private final Context mContext;

        private Duration mIntentTimeout;
        private IntentListener mIntentListener;
        private String[] mIntentStrings;

        // Intermediate variable, prepared from mIntentStrings
        private IntentFilter mIntentFilter;

        /**
         * Private default constructor to avoid creation of Builder default instance directly as we
         * need some instance variables to be initiated with user defined values.
         */
        private Builder() {
            mContext = null;
        }

        /**
         * Creates a Builder instance with following required params
         *
         * @param context Context
         * @param intentStrings Array of intents to filter and register
         */
        public Builder(@NonNull Context context, String... intentStrings) {
            mContext = context;
            mIntentStrings =
                    requireNonNull(
                            intentStrings,
                            "IntentReceiver.Builder(): Intent string cannot be null");

            if (mIntentStrings.length == 0) {
                throw new RuntimeException("IntentReceiver.Builder(): No intents to register");
            }

            mIntentFilter = Builder.prepareIntentFilter(mIntentStrings);

            /* Default values for remaining vars */
            mIntentTimeout = Duration.ofSeconds(10);
            mIntentListener = null;
        }

        public Builder setIntentListener(IntentListener intentListener) {
            mIntentListener = intentListener;
            return this;
        }

        public Builder setIntentTimeout(Duration intentTimeout) {
            mIntentTimeout = intentTimeout;
            return this;
        }

        /**
         * Builds and returns the IntentReceiver object with all the passed, and default params
         * supplied to Builder().
         */
        public IntentReceiver build() {
            return new IntentReceiver(this);
        }

        public static IntentFilter prepareIntentFilter(String... intentStrings) {
            IntentFilter intentFilter = new IntentFilter();
            for (String intentString : intentStrings) {
                intentFilter.addAction(intentString);
            }

            return intentFilter;
        }
    }

    /**
     * Verifies if the intent is received in order
     *
     * @param matchers Matchers
     */
    public void verifyReceivedOrdered(Matcher<Intent>... matchers) {
        mInOrder.verify(mReceiver, timeout(mDqBuilder.peekFirst().mIntentTimeout.toMillis()))
                .onReceive(any(Context.class), MockitoHamcrest.argThat(AllOf.allOf(matchers)));
    }

    /**
     * Verifies if requested number of intents are received (unordered)
     *
     * @param num Number of intents
     * @param matchers Matchers
     */
    public void verifyReceived(int num, Matcher<Intent>... matchers) {
        verify(mReceiver, timeout(mDqBuilder.peekFirst().mIntentTimeout.toMillis()).times(num))
                .onReceive(any(Context.class), MockitoHamcrest.argThat(AllOf.allOf(matchers)));
    }

    /**
     * Verifies if the intent is received (unordered)
     *
     * @param matchers Matchers
     */
    public void verifyReceived(Matcher<Intent>... matchers) {
        verifyReceived(1, matchers);
    }

    /**
     * This function will make sure that the instance is properly cleared based on the registered
     * actions. Note: This function MUST be called before returning from the caller function, as
     * this either unregisters the latest registered actions, or free resources.
     */
    public void close() {
        Log.d(TAG, "close(): " + mDqBuilder.size());

        /* More than 1 Builders are present in deque */
        if (mDqBuilder.size() > 1) {
            rollbackBuilder();
        } else {
            // Only 1 builder remaining, safely close this instance.
            verifyNoMoreInteractions();
            teardown();
        }
    }

    /**
     * Registers the new builder instance with the parent IntentReceiver instance
     *
     * @param parentIntentReceiver Parent IntentReceiver instance
     * @param builder New builder instance
     *     <p>Note: This is a helper function to be used in testStep functions where properties are
     *     updated in the new builder instance, and then pushed to the parent instance.
     */
    public static IntentReceiver update(IntentReceiver parentIntentReceiver, Builder builder) {
        if (parentIntentReceiver == null) return builder.build();

        parentIntentReceiver.updateBuilder(builder);
        return parentIntentReceiver;
    }

    /** Helper functions are added below, usually private */

    /** Registers the listener for the received intents, and perform a custom logic as required */
    private void setupListener() {
        doAnswer(
                        inv -> {
                            Log.d(
                                    TAG,
                                    "onReceive(): intent=" + Arrays.toString(inv.getArguments()));

                            if (mDqBuilder.peekFirst().mIntentListener == null) return null;

                            Intent intent = inv.getArgument(1);

                            /* Custom `onReceive` will be provided by the caller */
                            mDqBuilder.peekFirst().mIntentListener.onReceive(intent);
                            return null;
                        })
                .when(mReceiver)
                .onReceive(any(), any());
    }

    /**
     * Registers the latest intent filter which is at the deque.peekFirst() Note: The mDqBuilder
     * must not be empty here.
     */
    private void registerReceiver() {
        IntentFilter intentFilter;
        /* ArrayDeque should not be empty at all while registering a receiver */
        assertThat(mDqBuilder.isEmpty()).isFalse();

        intentFilter = (IntentFilter) mDqBuilder.peekFirst().mIntentFilter;
        Log.d(
                TAG,
                "registerReceiver(): Registering for intents: "
                        + getActionsFromIntentFilter(intentFilter));
        mContext.registerReceiver(mReceiver, intentFilter);
    }

    //
    // Unregisters the receiver from the list of active receivers.
    // Also, we can now re-use the same receiver, or register a new
    //  receiver with the same or different intent filter, the old
    //  registration is no longer valid.
    //  Source: Intents and intent filters (Android Developers)
    //
    private void unregisterReceiver() {
        Log.d(TAG, "unregisterReceiver()");
        mContext.unregisterReceiver(mReceiver);
    }

    /** Verifies that no more intents are received */
    private void verifyNoMoreInteractions() {
        Log.d(TAG, "verifyNoMoreInteractions()");
        Mockito.verifyNoMoreInteractions(mReceiver);
    }

    //
    // Registers the new actions passed as argument.
    // 1. Unregister the Builder.
    // 2. Pops a new Builder to roll-back to the old one.
    // 3. Registers the old Builder.
    //
    private void rollbackBuilder() {
        assertThat(mDqBuilder.isEmpty()).isFalse();

        teardown();

        /* Restores the previous Builder, and discard the latest */
        mDqBuilder.removeFirst();
        setup();
    }

    /**
     * Helper function to get the actions from the IntentFilter
     *
     * @param intentFilter IntentFilter instance
     *     <p>This is a helper function to get the actions from the IntentFilter, and return as a
     *     String.
     */
    private static String getActionsFromIntentFilter(IntentFilter intentFilter) {
        Iterator<String> iterator = intentFilter.actionsIterator();
        StringBuilder allIntentActions = new StringBuilder();
        while (iterator.hasNext()) {
            allIntentActions.append(iterator.next() + ", ");
        }

        return allIntentActions.toString();
    }

    /**
     * Helper function to perform the setup for the IntentReceiver instance
     *
     * <p>This is a helper function to perform the setup for the IntentReceiver instance, which
     * includes setting up the listener, and registering the receiver, etc.
     */
    private void setup() {
        setupListener();
        registerReceiver();
    }

    private void teardown() {
        unregisterReceiver();
    }

    /**
     * Updates the current builder with the new builder instance.
     *
     * @param builder New builder instance
     */
    private void updateBuilder(Builder builder) {
        teardown();
        // Keep the new builder at the top of the deque
        mDqBuilder.addFirst(builder);

        // calls all required setup functions based on the new builder
        setup();
    }
}
