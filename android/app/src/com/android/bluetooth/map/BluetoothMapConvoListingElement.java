/*
 * Copyright (C) 2013 Samsung System LSI
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
package com.android.bluetooth.map;

import android.bluetooth.BluetoothProfile;
import android.bluetooth.BluetoothProtoEnums;
import android.util.Log;

import com.android.bluetooth.BluetoothStatsLog;
import com.android.bluetooth.SignedLongLong;
import com.android.bluetooth.Utils;
import com.android.bluetooth.content_profiles.ContentProfileErrorReportUtils;
import com.android.bluetooth.map.BluetoothMapUtils.TYPE;

import org.xmlpull.v1.XmlPullParser;
import org.xmlpull.v1.XmlPullParserException;
import org.xmlpull.v1.XmlSerializer;

import java.io.IOException;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Objects;

// Next tag value for ContentProfileErrorReportUtils.report(): 2
public class BluetoothMapConvoListingElement
        implements Comparable<BluetoothMapConvoListingElement> {
    private static final String TAG = BluetoothMapConvoListingElement.class.getSimpleName();

    public static final String XML_TAG_CONVERSATION = "conversation";
    private static final String XML_ATT_LAST_ACTIVITY = "last_activity";
    private static final String XML_ATT_NAME = "name";
    private static final String XML_ATT_ID = "id";
    private static final String XML_ATT_READ = "readstatus";
    private static final String XML_ATT_VERSION_COUNTER = "version_counter";
    private static final String XML_ATT_SUMMARY = "summary";

    private SignedLongLong mId = null;
    private String mName = ""; // title of the conversation #REQUIRED, but allowed empty
    private long mLastActivity = -1;
    private boolean mRead = false;
    private boolean mReportRead = false; // TODO: Is this needed? - false means UNKNOWN
    private List<BluetoothMapConvoContactElement> mContacts;
    private long mVersionCounter = -1;
    private int mCursorIndex = 0;
    private TYPE mType = null;
    private String mSummary = null;

    // Used only to keep track of changes to convoListVersionCounter;
    private String mSmsMmsContacts = null;

    public int getCursorIndex() {
        return mCursorIndex;
    }

    public void setCursorIndex(int cursorIndex) {
        this.mCursorIndex = cursorIndex;
        Log.d(TAG, "setCursorIndex: " + cursorIndex);
    }

    public long getVersionCounter() {
        return mVersionCounter;
    }

    public void setVersionCounter(long vcount) {
        Log.d(TAG, "setVersionCounter: " + vcount);
        this.mVersionCounter = vcount;
    }

    public void incrementVersionCounter() {
        mVersionCounter++;
    }

    private void setVersionCounter(String vcount) {
        Log.d(TAG, "setVersionCounter: " + vcount);
        try {
            this.mVersionCounter = Long.parseLong(vcount);
        } catch (NumberFormatException e) {
            ContentProfileErrorReportUtils.report(
                    BluetoothProfile.MAP,
                    BluetoothProtoEnums.BLUETOOTH_MAP_CONVO_LISTING_ELEMENT,
                    BluetoothStatsLog.BLUETOOTH_CONTENT_PROFILE_ERROR_REPORTED__TYPE__EXCEPTION,
                    0);
            Log.w(TAG, "unable to parse XML versionCounter:" + vcount);
            mVersionCounter = -1;
        }
    }

    public String getName() {
        return mName;
    }

    public void setName(String name) {
        Log.d(TAG, "setName: " + name);
        this.mName = name;
    }

    public TYPE getType() {
        return mType;
    }

    public void setType(TYPE type) {
        this.mType = type;
    }

    public List<BluetoothMapConvoContactElement> getContacts() {
        return mContacts;
    }

    public void setContacts(List<BluetoothMapConvoContactElement> contacts) {
        this.mContacts = contacts;
    }

    public void addContact(BluetoothMapConvoContactElement contact) {
        if (mContacts == null) {
            mContacts = new ArrayList<BluetoothMapConvoContactElement>();
        }
        mContacts.add(contact);
    }

    public void removeContact(BluetoothMapConvoContactElement contact) {
        mContacts.remove(contact);
    }

    public void removeContact(int index) {
        mContacts.remove(index);
    }

    public long getLastActivity() {
        return mLastActivity;
    }

    @SuppressWarnings("JavaUtilDate") // TODO: b/365629730 -- prefer Instant or LocalDate
    public String getLastActivityString() {
        SimpleDateFormat format = new SimpleDateFormat("yyyyMMdd'T'HHmmss");
        Date date = new Date(mLastActivity);
        return format.format(date); // Format to YYYYMMDDTHHMMSS local time
    }

    public void setLastActivity(long last) {
        Log.d(TAG, "setLastActivity: " + last);
        this.mLastActivity = last;
    }

    @SuppressWarnings("JavaUtilDate") // TODO: b/365629730 -- prefer Instant or LocalDate
    public void setLastActivity(String lastActivity) throws ParseException {
        // TODO: Encode with time-zone if MCE requests it
        SimpleDateFormat format = new SimpleDateFormat("yyyyMMdd'T'HHmmss");
        Date date = format.parse(lastActivity);
        this.mLastActivity = date.getTime();
    }

    public String getRead() {
        if (!mReportRead) {
            return "UNKNOWN";
        }
        return (mRead ? "READ" : "UNREAD");
    }

    public boolean getReadBool() {
        return mRead;
    }

    public void setRead(boolean read, boolean reportRead) {
        this.mRead = read;
        Log.d(TAG, "setRead: " + read);
        this.mReportRead = reportRead;
    }

    private void setRead(String value) {
        if (value.trim().equalsIgnoreCase("yes")) {
            mRead = true;
        } else {
            mRead = false;
        }
        mReportRead = true;
    }

    /**
     * Set the conversation ID
     *
     * @param type 0 if the thread ID is valid across all message types in the instance - else use
     *     one of the CONVO_ID_xxx types.
     * @param threadId the conversation ID
     */
    public void setConvoId(long type, long threadId) {
        this.mId = new SignedLongLong(threadId, type);
        Log.d(TAG, "setConvoId: " + threadId + " type:" + type);
    }

    public String getConvoId() {
        return mId.toHexString();
    }

    public long getCpConvoId() {
        return mId.leastSignificantBits();
    }

    public void setSummary(String summary) {
        mSummary = summary;
    }

    public String getFullSummary() {
        return mSummary;
    }

    /* Get a valid UTF-8 string of maximum 256 bytes */
    private String getSummary() {
        if (mSummary != null) {
            return BluetoothMapUtils.truncateUtf8StringToString(mSummary, 256);
        }
        return null;
    }

    public String getSmsMmsContacts() {
        return mSmsMmsContacts;
    }

    public void setSmsMmsContacts(String smsMmsContacts) {
        mSmsMmsContacts = smsMmsContacts;
    }

    @Override
    public int compareTo(BluetoothMapConvoListingElement e) {
        if (this.mLastActivity < e.mLastActivity) {
            return 1;
        } else if (this.mLastActivity > e.mLastActivity) {
            return -1;
        } else {
            return 0;
        }
    }

    /* Encode the MapMessageListingElement into the StringBuilder reference.
     * Here we have taken the choice not to report empty attributes, to reduce the
     * amount of data to be transferred over BT. */
    public void encode(XmlSerializer xmlConvoElement)
            throws IllegalArgumentException, IllegalStateException, IOException {

        // construct the XML tag for a single conversation in the convolisting
        xmlConvoElement.startTag(null, XML_TAG_CONVERSATION);
        xmlConvoElement.attribute(null, XML_ATT_ID, mId.toHexString());
        if (mName != null) {
            xmlConvoElement.attribute(
                    null, XML_ATT_NAME, BluetoothMapUtils.stripInvalidChars(mName));
        }
        if (mLastActivity != -1) {
            xmlConvoElement.attribute(null, XML_ATT_LAST_ACTIVITY, getLastActivityString());
        }
        // Even though this is implied, the value "UNKNOWN" kind of indicated it is required.
        if (mReportRead) {
            xmlConvoElement.attribute(null, XML_ATT_READ, getRead());
        }
        if (mVersionCounter != -1) {
            xmlConvoElement.attribute(
                    null, XML_ATT_VERSION_COUNTER, Long.toString(getVersionCounter()));
        }
        if (mSummary != null) {
            xmlConvoElement.attribute(null, XML_ATT_SUMMARY, getSummary());
        }
        if (mContacts != null) {
            for (BluetoothMapConvoContactElement contact : mContacts) {
                contact.encode(xmlConvoElement);
            }
        }
        xmlConvoElement.endTag(null, XML_TAG_CONVERSATION);
    }

    /**
     * Consumes a conversation tag. It is expected that the parser is beyond the start-tag event,
     * with the name "conversation".
     */
    public static BluetoothMapConvoListingElement createFromXml(XmlPullParser parser)
            throws XmlPullParserException, IOException, ParseException {
        BluetoothMapConvoListingElement newElement = new BluetoothMapConvoListingElement();
        int count = parser.getAttributeCount();
        int type;
        for (int i = 0; i < count; i++) {
            String attributeName = parser.getAttributeName(i).trim();
            String attributeValue = parser.getAttributeValue(i);
            if (attributeName.equalsIgnoreCase(XML_ATT_ID)) {
                newElement.mId = SignedLongLong.fromString(attributeValue);
            } else if (attributeName.equalsIgnoreCase(XML_ATT_NAME)) {
                newElement.mName = attributeValue;
            } else if (attributeName.equalsIgnoreCase(XML_ATT_LAST_ACTIVITY)) {
                newElement.setLastActivity(attributeValue);
            } else if (attributeName.equalsIgnoreCase(XML_ATT_READ)) {
                newElement.setRead(attributeValue);
            } else if (attributeName.equalsIgnoreCase(XML_ATT_VERSION_COUNTER)) {
                newElement.setVersionCounter(attributeValue);
            } else if (attributeName.equalsIgnoreCase(XML_ATT_SUMMARY)) {
                newElement.setSummary(attributeValue);
            } else {
                Log.w(TAG, "Unknown XML attribute: " + parser.getAttributeName(i));
            }
        }

        // Now determine if we get an end-tag, or a new start tag for contacts
        while ((type = parser.next()) != XmlPullParser.END_TAG
                && type != XmlPullParser.END_DOCUMENT) {
            // Skip until we get a start tag
            if (parser.getEventType() != XmlPullParser.START_TAG) {
                continue;
            }
            // Skip until we get a convocontact tag
            String name = parser.getName().trim();
            if (name.equalsIgnoreCase(BluetoothMapConvoContactElement.XML_TAG_CONVOCONTACT)) {
                newElement.addContact(BluetoothMapConvoContactElement.createFromXml(parser));
            } else {
                Log.w(TAG, "Unknown XML tag: " + name);
                Utils.skipCurrentTag(parser);
            }
        }
        // As we have extracted all attributes, we should expect an end-tag
        // parser.nextTag(); // consume the end-tag
        // TODO: Is this needed? - we should already be at end-tag, as this is the top condition

        return newElement;
    }

    @Override
    public boolean equals(Object obj) {
        if (this == obj) {
            return true;
        }
        if (!(obj instanceof BluetoothMapConvoListingElement other)) {
            return false;
        }

        if (!Objects.equals(mContacts, other.mContacts)) {
            return false;
        }

        // Skip comparing auto assigned value `mId`. Equals is only used for test

        if (mLastActivity != other.mLastActivity) {
            return false;
        }
        if (!Objects.equals(mName, other.mName)) {
            return false;
        }
        if (mRead != other.mRead) {
            return false;
        }
        return true;
    }

    @Override
    public int hashCode() {
        return Objects.hash(mContacts, mLastActivity, mName, mRead);
    }
}
