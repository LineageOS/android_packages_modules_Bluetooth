/*
 * Copyright 2022 The Android Open Source Project
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

package com.android.bluetooth.pbapclient;

import static com.android.bluetooth.TestUtils.MockitoRule;

import static com.google.common.truth.Truth.assertThat;

import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;

import android.accounts.Account;
import android.content.Context;
import android.provider.CallLog;
import android.provider.ContactsContract;
import android.test.mock.MockContentResolver;
import android.util.SparseArray;

import androidx.test.filters.SmallTest;
import androidx.test.runner.AndroidJUnit4;

import com.android.vcard.VCardConstants;
import com.android.vcard.VCardEntry;
import com.android.vcard.VCardProperty;

import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;

@SmallTest
@RunWith(AndroidJUnit4.class)
public class CallLogPullRequestTest {

    @Rule public final MockitoRule mMockitoRule = new MockitoRule();

    private final Account mAccount = mock(Account.class);
    private final HashMap<String, Integer> mCallCounter = new HashMap<>();

    @Mock private Context mMockContext;
    private MockContentResolver mMockContentResolver;
    private FakeContactsProvider mFakeContactsProvider;

    @Before
    public void setUp() {
        mMockContentResolver = new MockContentResolver();
        mFakeContactsProvider = new FakeContactsProvider();
        mMockContentResolver.addProvider(ContactsContract.AUTHORITY, mFakeContactsProvider);
        mMockContentResolver.addProvider(CallLog.AUTHORITY, mFakeContactsProvider);
        doReturn(mMockContentResolver).when(mMockContext).getContentResolver();
    }

    @Test
    public void testToString() {
        final String path = PbapPhonebook.ICH_PATH;
        final CallLogPullRequest request =
                new CallLogPullRequest(mMockContext, path, mCallCounter, mAccount);

        assertThat(request.toString()).isNotEmpty();
    }

    @Test
    public void onPullComplete_whenResultsAreNull() {
        final String path = PbapPhonebook.ICH_PATH;
        final CallLogPullRequest request =
                new CallLogPullRequest(mMockContext, path, mCallCounter, mAccount);
        request.setResults(null);

        request.onPullComplete();

        // No operation has been done.
        assertThat(mCallCounter).isEmpty();
    }

    @Test
    public void onPullComplete_whenPathIsInvalid() {
        final String invalidPath = "invalidPath";
        final CallLogPullRequest request =
                new CallLogPullRequest(mMockContext, invalidPath, mCallCounter, mAccount);
        List<VCardEntry> results = new ArrayList<>();
        request.setResults(results);

        request.onPullComplete();

        // No operation has been done.
        assertThat(mCallCounter).isEmpty();
    }

    @Test
    public void onPullComplete_whenResultsAreEmpty() {
        final String path = PbapPhonebook.ICH_PATH;
        final CallLogPullRequest request =
                new CallLogPullRequest(mMockContext, path, mCallCounter, mAccount);
        List<VCardEntry> results = new ArrayList<>();
        request.setResults(results);

        request.onPullComplete();

        // Call counter should remain same.
        assertThat(mCallCounter).isEmpty();
    }

    @Test
    public void onPullComplete_whenThereIsNoPhoneProperty() {
        final String path = PbapPhonebook.MCH_PATH;
        final CallLogPullRequest request =
                new CallLogPullRequest(mMockContext, path, mCallCounter, mAccount);

        // Add some property which is NOT a phone number
        VCardProperty property = new VCardProperty();
        property.setName(VCardConstants.PROPERTY_NOTE);
        property.setValues("Some random note");

        VCardEntry entry = new VCardEntry();
        entry.addProperty(property);

        List<VCardEntry> results = new ArrayList<>();
        results.add(entry);
        request.setResults(results);

        request.onPullComplete();

        // Call counter should remain same.
        assertThat(mCallCounter).isEmpty();
    }

    @Test
    public void onPullComplete_success() {
        final String path = PbapPhonebook.OCH_PATH;
        final CallLogPullRequest request =
                new CallLogPullRequest(mMockContext, path, mCallCounter, mAccount);
        List<VCardEntry> results = new ArrayList<>();

        final String phoneNum = "tel:0123456789";

        VCardEntry entry1 = new VCardEntry();
        entry1.addProperty(createProperty(VCardConstants.PROPERTY_TEL, phoneNum));
        results.add(entry1);

        VCardEntry entry2 = new VCardEntry();
        entry2.addProperty(createProperty(VCardConstants.PROPERTY_TEL, phoneNum));
        entry2.addProperty(
                createProperty(CallLogPullRequest.TIMESTAMP_PROPERTY, "20220914T143305"));
        results.add(entry2);
        request.setResults(results);

        request.onPullComplete();

        assertThat(mCallCounter).hasSize(1);
        for (String key : mCallCounter.keySet()) {
            assertThat(mCallCounter.get(key)).isEqualTo(2);
            break;
        }
    }

    @Test
    public void updateTimesContacted_cursorIsClosed() {
        final String path = PbapPhonebook.OCH_PATH;
        final CallLogPullRequest request =
                new CallLogPullRequest(mMockContext, path, mCallCounter, mAccount);

        String accountName = "AA:BB:CC:DD:EE:FF";
        mFakeContactsProvider.addAccount(Utils.ACCOUNT_TYPE, accountName);
        mFakeContactsProvider.addContact(
                Utils.ACCOUNT_TYPE,
                accountName,
                false,
                PbapPhonebook.LOCAL_PHONEBOOK_PATH,
                "555-123-4567");
        mCallCounter.put("555-123-4567", 1);

        request.updateTimesContacted();

        SparseArray<FakeContactsProvider.FakeRawContact> rawContacts =
                mFakeContactsProvider.getRawContacts();
        assertThat(rawContacts.size()).isEqualTo(1);
        FakeContactsProvider.FakeRawContact contact = rawContacts.valueAt(0);
        assertThat(contact).isNotNull();
        assertThat(contact.getTimesContacted()).isEqualTo(1);
    }

    private static VCardProperty createProperty(String name, String value) {
        VCardProperty property = new VCardProperty();
        property.setName(name);
        property.setValues(value);
        return property;
    }
}
