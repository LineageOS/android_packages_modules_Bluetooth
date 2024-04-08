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

import static org.junit.Assert.assertThrows;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

/** Test for Bluetooth LE {@link ScanSettings}. */
@RunWith(JUnit4.class)
public class ScanSettingsTest {

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
}
