/*
 * Copyright (C) 2019 The Android Open Source Project
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

package com.android.bluetooth.avrcpcontroller;

import static java.util.Objects.requireNonNull;

import android.util.Log;

import java.util.Date;
import java.util.Objects;

/**
 * Represents BIP attachment metadata arriving from a GetImageProperties request.
 *
 * <p>Content type is the only spec-required field.
 *
 * <p>Examples: <attachment content-type="text/plain" name="ABCD1234.txt" size="5120"/> <attachment
 * content-type="audio/basic" name="ABCD1234.wav" size="102400"/>
 */
public class BipAttachmentFormat {
    private static final String TAG =
            AvrcpControllerUtils.TAG_PREFIX_AVRCP_CONTROLLER
                    + BipAttachmentFormat.class.getSimpleName();

    /**
     * MIME content type of the image attachment, i.e. "text/plain"
     *
     * <p>This is required by the specification
     */
    private final String mContentType;

    /** MIME character set of the image attachment, i.e. "ISO-8859-1" */
    private final String mCharset;

    /**
     * File name of the image attachment
     *
     * <p>This is required by the specification
     */
    private final String mName;

    /** Size of the image attachment in bytes */
    private final int mSize;

    /** Date the image attachment was created */
    private final BipDateTime mCreated;

    /** Date the image attachment was last modified */
    private final BipDateTime mModified;

    public BipAttachmentFormat(
            String contentType,
            String charset,
            String name,
            String size,
            String created,
            String modified) {
        if (contentType == null) {
            throw new ParseException("ContentType is required and must be valid");
        }
        if (name == null) {
            throw new ParseException("Name is required and must be valid");
        }

        mContentType = contentType;
        mName = name;
        mCharset = charset;
        mSize = parseInt(size);

        BipDateTime bipCreated = null;
        try {
            bipCreated = new BipDateTime(created);
        } catch (ParseException e) {
            bipCreated = null;
        }
        mCreated = bipCreated;

        BipDateTime bipModified = null;
        try {
            bipModified = new BipDateTime(modified);
        } catch (ParseException e) {
            bipModified = null;
        }
        mModified = bipModified;
    }

    public BipAttachmentFormat(
            String contentType,
            String charset,
            String name,
            int size,
            Date created,
            Date modified) {
        mContentType = requireNonNull(contentType);
        mName = requireNonNull(name);
        mCharset = charset;
        mSize = size;
        mCreated = created != null ? new BipDateTime(created) : null;
        mModified = modified != null ? new BipDateTime(modified) : null;
    }

    private static int parseInt(String s) {
        if (s == null) return -1;
        try {
            return Integer.parseInt(s);
        } catch (NumberFormatException e) {
            Log.e(TAG, "Invalid number format for '" + s + "'");
        }
        return -1;
    }

    public String getContentType() {
        return mContentType;
    }

    public String getName() {
        return mName;
    }

    public String getCharset() {
        return mCharset;
    }

    public int getSize() {
        return mSize;
    }

    public BipDateTime getCreatedDate() {
        return mCreated;
    }

    public BipDateTime getModifiedDate() {
        return mModified;
    }

    @Override
    public boolean equals(Object o) {
        if (o == this) {
            return true;
        }
        if (!(o instanceof BipAttachmentFormat a)) {
            return false;
        }

        return a.getContentType().equals(getContentType())
                && a.getName().equals(getName())
                && Objects.equals(a.getCharset(), getCharset())
                && a.getSize() == getSize()
                && Objects.equals(a.getCreatedDate(), getCreatedDate())
                && Objects.equals(a.getModifiedDate(), getModifiedDate());
    }

    @Override
    public int hashCode() {
        return Objects.hash(
                getContentType(),
                getName(),
                getCharset(),
                getSize(),
                getCreatedDate(),
                getModifiedDate());
    }

    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder();
        sb.append("<attachment");
        sb.append(" content-type=\"").append(mContentType).append("\"");
        if (mCharset != null) sb.append(" charset=\"").append(mCharset).append("\"");
        sb.append(" name=\"").append(mName).append("\"");
        if (mSize > -1) sb.append(" size=\"").append(mSize).append("\"");
        if (mCreated != null) sb.append(" created=\"").append(mCreated.toString()).append("\"");
        if (mModified != null) sb.append(" modified=\"").append(mModified.toString()).append("\"");
        sb.append(" />");
        return sb.toString();
    }
}
