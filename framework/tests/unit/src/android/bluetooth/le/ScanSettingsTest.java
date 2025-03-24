/*
 * Copyright (C) 2014 The Android Open Source Project
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

package android.bluetooth.le;

import static android.bluetooth.le.ScanSettings.CHANGE_DEFAULT_TRACKABLE_ADV_NUMBER;

import static com.google.common.truth.Truth.assertThat;

import static org.junit.Assert.assertThrows;
import static org.junit.Assume.assumeTrue;

import android.app.compat.CompatChanges;
import android.platform.test.annotations.DisableFlags;
import android.platform.test.annotations.EnableFlags;
import android.platform.test.flag.junit.CheckFlagsRule;
import android.platform.test.flag.junit.DeviceFlagsValueProvider;

import com.android.bluetooth.flags.Flags;

import libcore.junit.util.compat.CoreCompatChangeRule;
import libcore.junit.util.compat.CoreCompatChangeRule.DisableCompatChanges;
import libcore.junit.util.compat.CoreCompatChangeRule.EnableCompatChanges;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TestRule;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

/** Test cases for {@link ScanSettings}. */
@RunWith(JUnit4.class)
public class ScanSettingsTest {
    @Rule
    public final CheckFlagsRule mCheckFlagsRule = DeviceFlagsValueProvider.createCheckFlagsRule();

    @Rule public TestRule mCompatChangeRule = new CoreCompatChangeRule();

    @Test
    public void testCallbackType() {
        ScanSettings.Builder builder = new ScanSettings.Builder();
        builder.setCallbackType(ScanSettings.CALLBACK_TYPE_ALL_MATCHES);
        builder.setCallbackType(ScanSettings.CALLBACK_TYPE_FIRST_MATCH);
        builder.setCallbackType(ScanSettings.CALLBACK_TYPE_MATCH_LOST);
        builder.setCallbackType(ScanSettings.CALLBACK_TYPE_ALL_MATCHES_AUTO_BATCH);
        builder.setCallbackType(
                ScanSettings.CALLBACK_TYPE_FIRST_MATCH | ScanSettings.CALLBACK_TYPE_MATCH_LOST);

        assertThrows(
                IllegalArgumentException.class,
                () ->
                        builder.setCallbackType(
                                ScanSettings.CALLBACK_TYPE_ALL_MATCHES
                                        | ScanSettings.CALLBACK_TYPE_MATCH_LOST));

        assertThrows(
                IllegalArgumentException.class,
                () ->
                        builder.setCallbackType(
                                ScanSettings.CALLBACK_TYPE_ALL_MATCHES
                                        | ScanSettings.CALLBACK_TYPE_FIRST_MATCH));

        assertThrows(
                IllegalArgumentException.class,
                () ->
                        builder.setCallbackType(
                                ScanSettings.CALLBACK_TYPE_ALL_MATCHES
                                        | ScanSettings.CALLBACK_TYPE_ALL_MATCHES_AUTO_BATCH));

        assertThrows(
                IllegalArgumentException.class,
                () ->
                        builder.setCallbackType(
                                ScanSettings.CALLBACK_TYPE_ALL_MATCHES
                                        | ScanSettings.CALLBACK_TYPE_FIRST_MATCH
                                        | ScanSettings.CALLBACK_TYPE_MATCH_LOST));

        assertThrows(
                IllegalArgumentException.class,
                () ->
                        builder.setCallbackType(
                                ScanSettings.CALLBACK_TYPE_ALL_MATCHES_AUTO_BATCH
                                        | ScanSettings.CALLBACK_TYPE_MATCH_LOST));

        assertThrows(
                IllegalArgumentException.class,
                () ->
                        builder.setCallbackType(
                                ScanSettings.CALLBACK_TYPE_ALL_MATCHES_AUTO_BATCH
                                        | ScanSettings.CALLBACK_TYPE_FIRST_MATCH));

        assertThrows(
                IllegalArgumentException.class,
                () ->
                        builder.setCallbackType(
                                ScanSettings.CALLBACK_TYPE_ALL_MATCHES_AUTO_BATCH
                                        | ScanSettings.CALLBACK_TYPE_FIRST_MATCH
                                        | ScanSettings.CALLBACK_TYPE_MATCH_LOST));

        assertThrows(
                IllegalArgumentException.class,
                () ->
                        builder.setCallbackType(
                                ScanSettings.CALLBACK_TYPE_ALL_MATCHES
                                        | ScanSettings.CALLBACK_TYPE_ALL_MATCHES_AUTO_BATCH
                                        | ScanSettings.CALLBACK_TYPE_FIRST_MATCH
                                        | ScanSettings.CALLBACK_TYPE_MATCH_LOST));

        assertThrows(
                IllegalArgumentException.class,
                () ->
                        builder.setCallbackType(ScanSettings.CALLBACK_TYPE_ALL_MATCHES_AUTO_BATCH)
                                .setReportDelay(0)
                                .build());
    }

    @Test
    @DisableCompatChanges(CHANGE_DEFAULT_TRACKABLE_ADV_NUMBER)
    @DisableFlags(Flags.FLAG_CHANGE_DEFAULT_TRACKABLE_ADV_NUMBER)
    public void builderInitialize_changeDefaultDisabled() {
        ScanSettings settings = new ScanSettings.Builder().build();

        assertThat(settings.getNumOfMatches()).isEqualTo(ScanSettings.MATCH_NUM_MAX_ADVERTISEMENT);
    }

    @Test
    @EnableCompatChanges(CHANGE_DEFAULT_TRACKABLE_ADV_NUMBER)
    @EnableFlags(Flags.FLAG_CHANGE_DEFAULT_TRACKABLE_ADV_NUMBER)
    public void builderInitialize_changeDefaultEnabled() {
        // Added assumption since in a few branches, changeID doesn't seem to be enabled
        assumeTrue(Flags.changeDefaultTrackableAdvNumber());
        assumeTrue(CompatChanges.isChangeEnabled(CHANGE_DEFAULT_TRACKABLE_ADV_NUMBER));

        ScanSettings settings = new ScanSettings.Builder().build();

        assertThat(settings.getNumOfMatches()).isEqualTo(ScanSettings.MATCH_NUM_FEW_ADVERTISEMENT);
    }
}
