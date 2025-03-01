/*
 * Copyright (C) 2015 Samsung System LSI
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

import android.util.Log;

import com.android.bluetooth.SignedLongLong;

import org.xmlpull.v1.XmlPullParser;
import org.xmlpull.v1.XmlPullParserException;
import org.xmlpull.v1.XmlSerializer;

import java.io.IOException;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Objects;

// Next tag value for ContentProfileErrorReportUtils.report(): 1
public class BluetoothMapConvoContactElement
        implements Comparable<BluetoothMapConvoContactElement> {
    private static final String TAG = BluetoothMapConvoContactElement.class.getSimpleName();

    public static final long CONTACT_ID_TYPE_SMS_MMS = 1;
    public static final long CONTACT_ID_TYPE_EMAIL = 2;
    public static final long CONTACT_ID_TYPE_IM = 3;

    private static final String XML_ATT_PRIORITY = "priority";
    private static final String XML_ATT_PRESENCE_STATUS = "presence_status";
    private static final String XML_ATT_PRESENCE_AVAILABILITY = "presence_availability";
    private static final String XML_ATT_X_BT_UID = "x_bt_uid";
    private static final String XML_ATT_LAST_ACTIVITY = "last_activity";
    private static final String XML_ATT_CHAT_STATE = "chat_state";
    private static final String XML_ATT_NAME = "name";
    private static final String XML_ATT_DISPLAY_NAME = "display_name";
    private static final String XML_ATT_UCI = "x_bt_uci";
    protected static final String XML_TAG_CONVOCONTACT = "convocontact";

    private String mUci = null;
    private String mName = null;
    private String mDisplayName = null;
    private String mPresenceStatus = null;
    private int mPresenceAvailability = -1;
    private int mPriority = -1;
    private long mLastActivity = -1;
    private SignedLongLong mBtUid = null;
    private int mChatState = -1;

    public static BluetoothMapConvoContactElement createFromMapContact(
            MapContact contact, String address) {
        BluetoothMapConvoContactElement newElement = new BluetoothMapConvoContactElement();
        newElement.mUci = address;
        // TODO: For now we use the ID as BT-UID
        newElement.mBtUid = new SignedLongLong(contact.getId(), 0);
        newElement.mDisplayName = contact.getName();
        return newElement;
    }

    public BluetoothMapConvoContactElement(
            String uci,
            String name,
            String displayName,
            String presenceStatus,
            int presenceAvailability,
            long lastActivity,
            int chatState,
            int priority,
            String btUid) {
        this.mUci = uci;
        this.mName = name;
        this.mDisplayName = displayName;
        this.mPresenceStatus = presenceStatus;
        this.mPresenceAvailability = presenceAvailability;
        this.mLastActivity = lastActivity;
        this.mChatState = chatState;
        this.mPresenceStatus = presenceStatus;
        this.mPriority = priority;
        if (btUid != null) {
            this.mBtUid = SignedLongLong.fromString(btUid);
        }
    }

    public BluetoothMapConvoContactElement() {
        // TODO Auto-generated constructor stub
    }

    public String getPresenceStatus() {
        return mPresenceStatus;
    }

    public String getDisplayName() {
        return mDisplayName;
    }

    public void setDisplayName(String displayName) {
        this.mDisplayName = displayName;
    }

    public void setPresenceStatus(String presenceStatus) {
        this.mPresenceStatus = presenceStatus;
    }

    public int getPresenceAvailability() {
        return mPresenceAvailability;
    }

    public void setPresenceAvailability(int presenceAvailability) {
        this.mPresenceAvailability = presenceAvailability;
    }

    public int getPriority() {
        return mPriority;
    }

    public void setPriority(int priority) {
        this.mPriority = priority;
    }

    public String getName() {
        return mName;
    }

    public void setName(String name) {
        this.mName = name;
    }

    public String getBtUid() {
        return mBtUid.toHexString();
    }

    public void setBtUid(SignedLongLong btUid) {
        this.mBtUid = btUid;
    }

    public int getChatState() {
        return mChatState;
    }

    public void setChatState(int chatState) {
        this.mChatState = chatState;
    }

    public void setChatState(String chatState) {
        this.mChatState = Integer.valueOf(chatState);
    }

    @SuppressWarnings("JavaUtilDate") // TODO: b/365629730 -- prefer Instant or LocalDate
    public String getLastActivityString() {
        SimpleDateFormat format = new SimpleDateFormat("yyyyMMdd'T'HHmmss");
        Date date = new Date(mLastActivity);
        return format.format(date); // Format to YYYYMMDDTHHMMSS local time
    }

    public void setLastActivity(long dateTime) {
        this.mLastActivity = dateTime;
    }

    @SuppressWarnings("JavaUtilDate") // TODO: b/365629730 -- prefer Instant or LocalDate
    public void setLastActivity(String lastActivity) throws ParseException {
        SimpleDateFormat format = new SimpleDateFormat("yyyyMMdd'T'HHmmss");
        Date date = format.parse(lastActivity);
        this.mLastActivity = date.getTime();
    }

    public void setContactId(String uci) {
        this.mUci = uci;
    }

    public String getContactId() {
        return mUci;
    }

    @Override
    public int compareTo(BluetoothMapConvoContactElement e) {
        if (this.mLastActivity < e.mLastActivity) {
            return 1;
        } else if (this.mLastActivity > e.mLastActivity) {
            return -1;
        } else {
            return 0;
        }
    }

    /* Encode the MapConvoContactElement into the StringBuilder reference.
     * Here we have taken the choice not to report empty attributes, to reduce the
     * amount of data to be transfered over BT. */
    public void encode(XmlSerializer xmlConvoElement)
            throws IllegalArgumentException, IllegalStateException, IOException {
        // construct the XML tag for a single contact in the convolisting element.
        xmlConvoElement.startTag(null, XML_TAG_CONVOCONTACT);
        if (mUci != null) {
            xmlConvoElement.attribute(null, XML_ATT_UCI, mUci);
        }
        if (mDisplayName != null) {
            xmlConvoElement.attribute(
                    null, XML_ATT_DISPLAY_NAME, BluetoothMapUtils.stripInvalidChars(mDisplayName));
        }
        if (mName != null) {
            xmlConvoElement.attribute(
                    null, XML_ATT_NAME, BluetoothMapUtils.stripInvalidChars(mName));
        }
        if (mChatState != -1) {
            xmlConvoElement.attribute(null, XML_ATT_CHAT_STATE, String.valueOf(mChatState));
        }
        if (mLastActivity != -1) {
            xmlConvoElement.attribute(null, XML_ATT_LAST_ACTIVITY, this.getLastActivityString());
        }
        if (mBtUid != null) {
            xmlConvoElement.attribute(null, XML_ATT_X_BT_UID, mBtUid.toHexString());
        }
        if (mPresenceAvailability != -1) {
            xmlConvoElement.attribute(
                    null, XML_ATT_PRESENCE_AVAILABILITY, String.valueOf(mPresenceAvailability));
        }
        if (mPresenceStatus != null) {
            xmlConvoElement.attribute(null, XML_ATT_PRESENCE_STATUS, mPresenceStatus);
        }
        if (mPriority != -1) {
            xmlConvoElement.attribute(null, XML_ATT_PRIORITY, String.valueOf(mPriority));
        }

        xmlConvoElement.endTag(null, XML_TAG_CONVOCONTACT);
    }

    /**
     * Call this function to create a BluetoothMapConvoContactElement. Will consume the end-tag.
     *
     * @param parser must point into XML_TAG_CONVERSATION tag, hence attributes can be read.
     */
    public static BluetoothMapConvoContactElement createFromXml(XmlPullParser parser)
            throws ParseException, XmlPullParserException, IOException {
        int count = parser.getAttributeCount();
        BluetoothMapConvoContactElement newElement;
        if (count < 1) {
            throw new IllegalArgumentException(
                    XML_TAG_CONVOCONTACT + " is not decorated with attributes");
        }
        newElement = new BluetoothMapConvoContactElement();
        for (int i = 0; i < count; i++) {
            String attributeName = parser.getAttributeName(i).trim();
            String attributeValue = parser.getAttributeValue(i);
            if (attributeName.equalsIgnoreCase(XML_ATT_UCI)) {
                newElement.mUci = attributeValue;
            } else if (attributeName.equalsIgnoreCase(XML_ATT_NAME)) {
                newElement.mName = attributeValue;
            } else if (attributeName.equalsIgnoreCase(XML_ATT_DISPLAY_NAME)) {
                newElement.mDisplayName = attributeValue;
            } else if (attributeName.equalsIgnoreCase(XML_ATT_CHAT_STATE)) {
                newElement.setChatState(attributeValue);
            } else if (attributeName.equalsIgnoreCase(XML_ATT_LAST_ACTIVITY)) {
                newElement.setLastActivity(attributeValue);
            } else if (attributeName.equalsIgnoreCase(XML_ATT_X_BT_UID)) {
                newElement.setBtUid(SignedLongLong.fromString(attributeValue));
            } else if (attributeName.equalsIgnoreCase(XML_ATT_PRESENCE_AVAILABILITY)) {
                newElement.mPresenceAvailability = Integer.parseInt(attributeValue);
            } else if (attributeName.equalsIgnoreCase(XML_ATT_PRESENCE_STATUS)) {
                newElement.setPresenceStatus(attributeValue);
            } else if (attributeName.equalsIgnoreCase(XML_ATT_PRIORITY)) {
                newElement.setPriority(Integer.parseInt(attributeValue));
            } else {
                Log.w(TAG, "Unknown XML attribute: " + parser.getAttributeName(i));
            }
        }
        parser.nextTag(); // Consume the end-tag
        return newElement;
    }

    @Override
    public boolean equals(Object obj) {
        if (this == obj) {
            return true;
        }
        if (!(obj instanceof BluetoothMapConvoContactElement other)) {
            return false;
        }

        // Skip comparing auto assigned value `mBtUid`. Equals is only used for test

        if (mChatState != other.mChatState) {
            return false;
        }
        if (!Objects.equals(mDisplayName, other.mDisplayName)) {
            return false;
        }

        // Skip comparing auto assigned value `mId`. Equals is only used for test

        if (mLastActivity != other.mLastActivity) {
            return false;
        }
        if (!Objects.equals(mName, other.mName)) {
            return false;
        }
        if (mPresenceAvailability != other.mPresenceAvailability) {
            return false;
        }
        if (!Objects.equals(mPresenceStatus, other.mPresenceStatus)) {
            return false;
        }
        if (mPriority != other.mPriority) {
            return false;
        }
        return true;
    }

    @Override
    public int hashCode() {
        return Objects.hash(
                mChatState,
                mDisplayName,
                mLastActivity,
                mName,
                mPresenceAvailability,
                mPresenceStatus,
                mPriority);
    }
}
