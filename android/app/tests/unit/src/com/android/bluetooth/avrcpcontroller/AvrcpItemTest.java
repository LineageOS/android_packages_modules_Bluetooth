/*
 * Copyright 2019 The Android Open Source Project
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

import static com.android.bluetooth.TestUtils.getTestDevice;

import static com.google.common.truth.Truth.assertThat;

import android.bluetooth.BluetoothDevice;
import android.net.Uri;
import android.support.v4.media.MediaBrowserCompat.MediaItem;
import android.support.v4.media.MediaDescriptionCompat;
import android.support.v4.media.MediaMetadataCompat;

import androidx.test.runner.AndroidJUnit4;

import com.google.common.testing.EqualsTester;

import org.junit.Test;
import org.junit.runner.RunWith;

/** A test suite for the AvrcpItem class. */
@RunWith(AndroidJUnit4.class)
public final class AvrcpItemTest {

    private final BluetoothDevice mDevice = getTestDevice(97);
    private static final String UUID = "AVRCP-ITEM-TEST-UUID";

    // Attribute ID Values from AVRCP Specification
    private static final int MEDIA_ATTRIBUTE_TITLE = 0x01;
    private static final int MEDIA_ATTRIBUTE_ARTIST_NAME = 0x02;
    private static final int MEDIA_ATTRIBUTE_ALBUM_NAME = 0x03;
    private static final int MEDIA_ATTRIBUTE_TRACK_NUMBER = 0x04;
    private static final int MEDIA_ATTRIBUTE_TOTAL_TRACK_NUMBER = 0x05;
    private static final int MEDIA_ATTRIBUTE_GENRE = 0x06;
    private static final int MEDIA_ATTRIBUTE_PLAYING_TIME = 0x07;
    private static final int MEDIA_ATTRIBUTE_COVER_ART_HANDLE = 0x08;

    @Test
    public void buildAvrcpItem() {
        String title = "Aaaaargh";
        String artist = "Bluetooth";
        String album = "The Best Protocol";
        long trackNumber = 1;
        long totalTracks = 12;
        String genre = "Viking Metal";
        long playingTime = 301;
        String artHandle = "0000001";
        Uri uri = Uri.parse("content://somewhere");

        AvrcpItem.Builder builder = new AvrcpItem.Builder();
        builder.setItemType(AvrcpItem.TYPE_MEDIA);
        builder.setType(AvrcpItem.MEDIA_AUDIO);
        builder.setDevice(mDevice);
        builder.setPlayable(true);
        builder.setUid(0);
        builder.setUuid(UUID);
        builder.setTitle(title);
        builder.setArtistName(artist);
        builder.setAlbumName(album);
        builder.setTrackNumber(trackNumber);
        builder.setTotalNumberOfTracks(totalTracks);
        builder.setGenre(genre);
        builder.setPlayingTime(playingTime);
        builder.setCoverArtHandle(artHandle);
        builder.setCoverArtLocation(uri);

        AvrcpItem item = builder.build();

        assertThat(item.getDevice()).isEqualTo(mDevice);
        assertThat(item.isPlayable()).isTrue();
        assertThat(item.isBrowsable()).isFalse();
        assertThat(item.getUid()).isEqualTo(0);
        assertThat(item.getUuid()).isEqualTo(UUID);
        assertThat(item.getDisplayableName()).isNull();
        assertThat(item.getTitle()).isEqualTo(title);
        assertThat(item.getArtistName()).isEqualTo(artist);
        assertThat(item.getAlbumName()).isEqualTo(album);
        assertThat(item.getTrackNumber()).isEqualTo(trackNumber);
        assertThat(item.getTotalNumberOfTracks()).isEqualTo(totalTracks);
        assertThat(item.getCoverArtHandle()).isEqualTo(artHandle);
        assertThat(item.getCoverArtLocation()).isEqualTo(uri);
    }

    @Test
    public void buildAvrcpItemFromAvrcpAttributes() {
        String title = "Aaaaargh";
        String artist = "Bluetooth";
        String album = "The Best Protocol";
        String trackNumber = "1";
        String totalTracks = "12";
        String genre = "Viking Metal";
        String playingTime = "301";
        String artHandle = "0000001";

        int[] attrIds =
                new int[] {
                    MEDIA_ATTRIBUTE_TITLE,
                    MEDIA_ATTRIBUTE_ARTIST_NAME,
                    MEDIA_ATTRIBUTE_ALBUM_NAME,
                    MEDIA_ATTRIBUTE_TRACK_NUMBER,
                    MEDIA_ATTRIBUTE_TOTAL_TRACK_NUMBER,
                    MEDIA_ATTRIBUTE_GENRE,
                    MEDIA_ATTRIBUTE_PLAYING_TIME,
                    MEDIA_ATTRIBUTE_COVER_ART_HANDLE
                };

        String[] attrMap =
                new String[] {
                    title, artist, album, trackNumber, totalTracks, genre, playingTime, artHandle
                };

        AvrcpItem.Builder builder = new AvrcpItem.Builder();
        builder.fromAvrcpAttributeArray(attrIds, attrMap);
        AvrcpItem item = builder.build();

        assertThat(item.getDevice()).isNull();
        assertThat(item.isPlayable()).isFalse();
        assertThat(item.isBrowsable()).isFalse();
        assertThat(item.getUid()).isEqualTo(0);
        assertThat(item.getUuid()).isNull();
        assertThat(item.getDisplayableName()).isNull();
        assertThat(item.getTitle()).isEqualTo(title);
        assertThat(item.getArtistName()).isEqualTo(artist);
        assertThat(item.getAlbumName()).isEqualTo(album);
        assertThat(item.getTrackNumber()).isEqualTo(1);
        assertThat(item.getTotalNumberOfTracks()).isEqualTo(12);
        assertThat(item.getCoverArtHandle()).isEqualTo(artHandle);
        assertThat(item.getCoverArtLocation()).isNull();
    }

    @Test
    public void buildAvrcpItemFromAvrcpAttributesWithBadIds_badIdsIgnored() {
        String title = "Aaaaargh";
        String artist = "Bluetooth";
        String album = "The Best Protocol";
        String trackNumber = "1";
        String totalTracks = "12";
        String genre = "Viking Metal";
        String playingTime = "301";
        String artHandle = "0000001";

        int[] attrIds =
                new int[] {
                    MEDIA_ATTRIBUTE_TITLE,
                    MEDIA_ATTRIBUTE_ARTIST_NAME,
                    MEDIA_ATTRIBUTE_ALBUM_NAME,
                    MEDIA_ATTRIBUTE_TRACK_NUMBER,
                    MEDIA_ATTRIBUTE_TOTAL_TRACK_NUMBER,
                    MEDIA_ATTRIBUTE_GENRE,
                    MEDIA_ATTRIBUTE_PLAYING_TIME,
                    MEDIA_ATTRIBUTE_COVER_ART_HANDLE,
                    75,
                    76,
                    77,
                    78
                };

        String[] attrMap =
                new String[] {
                    title,
                    artist,
                    album,
                    trackNumber,
                    totalTracks,
                    genre,
                    playingTime,
                    artHandle,
                    "ignore me",
                    "ignore me",
                    "ignore me",
                    "ignore me"
                };

        AvrcpItem.Builder builder = new AvrcpItem.Builder();
        builder.fromAvrcpAttributeArray(attrIds, attrMap);
        AvrcpItem item = builder.build();

        assertThat(item.getDevice()).isNull();
        assertThat(item.isPlayable()).isFalse();
        assertThat(item.isBrowsable()).isFalse();
        assertThat(item.getUid()).isEqualTo(0);
        assertThat(item.getUuid()).isNull();
        assertThat(item.getDisplayableName()).isNull();
        assertThat(item.getTitle()).isEqualTo(title);
        assertThat(item.getArtistName()).isEqualTo(artist);
        assertThat(item.getAlbumName()).isEqualTo(album);
        assertThat(item.getTrackNumber()).isEqualTo(1);
        assertThat(item.getTotalNumberOfTracks()).isEqualTo(12);
        assertThat(item.getCoverArtHandle()).isEqualTo(artHandle);
        assertThat(item.getCoverArtLocation()).isNull();
    }

    @Test
    public void buildAvrcpItemFromAvrcpAttributes_imageHandleTooShort() {
        String title = "Aaaaargh";
        String artist = "Bluetooth";
        String album = "The Best Protocol";
        String trackNumber = "1";
        String totalTracks = "12";
        String genre = "Viking Metal";
        String playingTime = "301";
        String artHandle = "000001"; // length 6 and not 7

        int[] attrIds =
                new int[] {
                    MEDIA_ATTRIBUTE_TITLE,
                    MEDIA_ATTRIBUTE_ARTIST_NAME,
                    MEDIA_ATTRIBUTE_ALBUM_NAME,
                    MEDIA_ATTRIBUTE_TRACK_NUMBER,
                    MEDIA_ATTRIBUTE_TOTAL_TRACK_NUMBER,
                    MEDIA_ATTRIBUTE_GENRE,
                    MEDIA_ATTRIBUTE_PLAYING_TIME,
                    MEDIA_ATTRIBUTE_COVER_ART_HANDLE
                };

        String[] attrMap =
                new String[] {
                    title, artist, album, trackNumber, totalTracks, genre, playingTime, artHandle
                };

        AvrcpItem.Builder builder = new AvrcpItem.Builder();
        builder.fromAvrcpAttributeArray(attrIds, attrMap);
        AvrcpItem item = builder.build();

        assertThat(item.getDevice()).isNull();
        assertThat(item.isPlayable()).isFalse();
        assertThat(item.isBrowsable()).isFalse();
        assertThat(item.getUid()).isEqualTo(0);
        assertThat(item.getUuid()).isNull();
        assertThat(item.getDisplayableName()).isNull();
        assertThat(item.getTitle()).isEqualTo(title);
        assertThat(item.getArtistName()).isEqualTo(artist);
        assertThat(item.getAlbumName()).isEqualTo(album);
        assertThat(item.getTrackNumber()).isEqualTo(1);
        assertThat(item.getTotalNumberOfTracks()).isEqualTo(12);
        assertThat(item.getCoverArtHandle()).isNull();
        assertThat(item.getCoverArtLocation()).isNull();
    }

    @Test
    public void buildAvrcpItemFromAvrcpAttributes_imageHandleEmpty() {
        String title = "Aaaaargh";
        String artist = "Bluetooth";
        String album = "The Best Protocol";
        String trackNumber = "1";
        String totalTracks = "12";
        String genre = "Viking Metal";
        String playingTime = "301";
        String artHandle = "";

        int[] attrIds =
                new int[] {
                    MEDIA_ATTRIBUTE_TITLE,
                    MEDIA_ATTRIBUTE_ARTIST_NAME,
                    MEDIA_ATTRIBUTE_ALBUM_NAME,
                    MEDIA_ATTRIBUTE_TRACK_NUMBER,
                    MEDIA_ATTRIBUTE_TOTAL_TRACK_NUMBER,
                    MEDIA_ATTRIBUTE_GENRE,
                    MEDIA_ATTRIBUTE_PLAYING_TIME,
                    MEDIA_ATTRIBUTE_COVER_ART_HANDLE
                };

        String[] attrMap =
                new String[] {
                    title, artist, album, trackNumber, totalTracks, genre, playingTime, artHandle
                };

        AvrcpItem.Builder builder = new AvrcpItem.Builder();
        builder.fromAvrcpAttributeArray(attrIds, attrMap);
        AvrcpItem item = builder.build();

        assertThat(item.getDevice()).isNull();
        assertThat(item.isPlayable()).isFalse();
        assertThat(item.isBrowsable()).isFalse();
        assertThat(item.getUid()).isEqualTo(0);
        assertThat(item.getUuid()).isNull();
        assertThat(item.getDisplayableName()).isNull();
        assertThat(item.getTitle()).isEqualTo(title);
        assertThat(item.getArtistName()).isEqualTo(artist);
        assertThat(item.getAlbumName()).isEqualTo(album);
        assertThat(item.getTrackNumber()).isEqualTo(1);
        assertThat(item.getTotalNumberOfTracks()).isEqualTo(12);
        assertThat(item.getCoverArtHandle()).isNull();
        assertThat(item.getCoverArtLocation()).isNull();
    }

    @Test
    public void buildAvrcpItemFromAvrcpAttributes_imageHandleNull() {
        String title = "Aaaaargh";
        String artist = "Bluetooth";
        String album = "The Best Protocol";
        String trackNumber = "1";
        String totalTracks = "12";
        String genre = "Viking Metal";
        String playingTime = "301";
        String artHandle = null;

        int[] attrIds =
                new int[] {
                    MEDIA_ATTRIBUTE_TITLE,
                    MEDIA_ATTRIBUTE_ARTIST_NAME,
                    MEDIA_ATTRIBUTE_ALBUM_NAME,
                    MEDIA_ATTRIBUTE_TRACK_NUMBER,
                    MEDIA_ATTRIBUTE_TOTAL_TRACK_NUMBER,
                    MEDIA_ATTRIBUTE_GENRE,
                    MEDIA_ATTRIBUTE_PLAYING_TIME,
                    MEDIA_ATTRIBUTE_COVER_ART_HANDLE
                };

        String[] attrMap =
                new String[] {
                    title, artist, album, trackNumber, totalTracks, genre, playingTime, artHandle
                };

        AvrcpItem.Builder builder = new AvrcpItem.Builder();
        builder.fromAvrcpAttributeArray(attrIds, attrMap);
        AvrcpItem item = builder.build();

        assertThat(item.getDevice()).isNull();
        assertThat(item.isPlayable()).isFalse();
        assertThat(item.isBrowsable()).isFalse();
        assertThat(item.getUid()).isEqualTo(0);
        assertThat(item.getUuid()).isNull();
        assertThat(item.getDisplayableName()).isNull();
        assertThat(item.getTitle()).isEqualTo(title);
        assertThat(item.getArtistName()).isEqualTo(artist);
        assertThat(item.getAlbumName()).isEqualTo(album);
        assertThat(item.getTrackNumber()).isEqualTo(1);
        assertThat(item.getTotalNumberOfTracks()).isEqualTo(12);
        assertThat(item.getCoverArtHandle()).isNull();
        assertThat(item.getCoverArtLocation()).isNull();
    }

    @Test
    public void buildAvrcpItemFromAvrcpAttributes_imageHandleNotDigits() {
        String title = "Aaaaargh";
        String artist = "Bluetooth";
        String album = "The Best Protocol";
        String trackNumber = "1";
        String totalTracks = "12";
        String genre = "Viking Metal";
        String playingTime = "301";
        String artHandle = "123abcd";

        int[] attrIds =
                new int[] {
                    MEDIA_ATTRIBUTE_TITLE,
                    MEDIA_ATTRIBUTE_ARTIST_NAME,
                    MEDIA_ATTRIBUTE_ALBUM_NAME,
                    MEDIA_ATTRIBUTE_TRACK_NUMBER,
                    MEDIA_ATTRIBUTE_TOTAL_TRACK_NUMBER,
                    MEDIA_ATTRIBUTE_GENRE,
                    MEDIA_ATTRIBUTE_PLAYING_TIME,
                    MEDIA_ATTRIBUTE_COVER_ART_HANDLE
                };

        String[] attrMap =
                new String[] {
                    title, artist, album, trackNumber, totalTracks, genre, playingTime, artHandle
                };

        AvrcpItem.Builder builder = new AvrcpItem.Builder();
        builder.fromAvrcpAttributeArray(attrIds, attrMap);
        AvrcpItem item = builder.build();

        assertThat(item.getDevice()).isNull();
        assertThat(item.isPlayable()).isFalse();
        assertThat(item.isBrowsable()).isFalse();
        assertThat(item.getUid()).isEqualTo(0);
        assertThat(item.getUuid()).isNull();
        assertThat(item.getDisplayableName()).isNull();
        assertThat(item.getTitle()).isEqualTo(title);
        assertThat(item.getArtistName()).isEqualTo(artist);
        assertThat(item.getAlbumName()).isEqualTo(album);
        assertThat(item.getTrackNumber()).isEqualTo(1);
        assertThat(item.getTotalNumberOfTracks()).isEqualTo(12);
        assertThat(item.getCoverArtHandle()).isNull();
        assertThat(item.getCoverArtLocation()).isNull();
    }

    @Test
    public void updateCoverArtLocation() {
        Uri uri = Uri.parse("content://somewhere");
        Uri uri2 = Uri.parse("content://somewhereelse");

        AvrcpItem.Builder builder = new AvrcpItem.Builder();
        builder.setCoverArtLocation(uri);

        AvrcpItem item = builder.build();
        assertThat(item.getCoverArtLocation()).isEqualTo(uri);

        item.setCoverArtLocation(uri2);
        assertThat(item.getCoverArtLocation()).isEqualTo(uri2);
    }

    @Test
    public void avrcpMediaItem_toMediaMetadata() {
        String title = "Aaaaargh";
        String artist = "Bluetooth";
        String album = "The Best Protocol";
        long trackNumber = 1;
        long totalTracks = 12;
        String genre = "Viking Metal";
        long playingTime = 301;
        String artHandle = "0000001";
        Uri uri = Uri.parse("content://somewhere");

        AvrcpItem.Builder builder = new AvrcpItem.Builder();
        builder.setItemType(AvrcpItem.TYPE_MEDIA);
        builder.setType(AvrcpItem.MEDIA_AUDIO);
        builder.setDevice(mDevice);
        builder.setPlayable(true);
        builder.setUid(0);
        builder.setUuid(UUID);
        builder.setDisplayableName(title);
        builder.setTitle(title);
        builder.setArtistName(artist);
        builder.setAlbumName(album);
        builder.setTrackNumber(trackNumber);
        builder.setTotalNumberOfTracks(totalTracks);
        builder.setGenre(genre);
        builder.setPlayingTime(playingTime);
        builder.setCoverArtHandle(artHandle);
        builder.setCoverArtLocation(uri);

        AvrcpItem item = builder.build();
        MediaMetadataCompat metadata = item.toMediaMetadata();

        assertThat(metadata.getString(MediaMetadataCompat.METADATA_KEY_MEDIA_ID)).isEqualTo(UUID);
        assertThat(metadata.getString(MediaMetadataCompat.METADATA_KEY_DISPLAY_TITLE))
                .isEqualTo(title);
        assertThat(metadata.getString(MediaMetadataCompat.METADATA_KEY_TITLE)).isEqualTo(title);
        assertThat(metadata.getString(MediaMetadataCompat.METADATA_KEY_ARTIST)).isEqualTo(artist);
        assertThat(metadata.getString(MediaMetadataCompat.METADATA_KEY_ALBUM)).isEqualTo(album);
        assertThat(metadata.getLong(MediaMetadataCompat.METADATA_KEY_TRACK_NUMBER))
                .isEqualTo(trackNumber);
        assertThat(metadata.getLong(MediaMetadataCompat.METADATA_KEY_NUM_TRACKS))
                .isEqualTo(totalTracks);
        assertThat(metadata.getString(MediaMetadataCompat.METADATA_KEY_GENRE)).isEqualTo(genre);
        assertThat(metadata.getLong(MediaMetadataCompat.METADATA_KEY_DURATION))
                .isEqualTo(playingTime);
        assertThat(Uri.parse(metadata.getString(MediaMetadataCompat.METADATA_KEY_DISPLAY_ICON_URI)))
                .isEqualTo(uri);
        assertThat(Uri.parse(metadata.getString(MediaMetadataCompat.METADATA_KEY_ART_URI)))
                .isEqualTo(uri);
        assertThat(Uri.parse(metadata.getString(MediaMetadataCompat.METADATA_KEY_ALBUM_ART_URI)))
                .isEqualTo(uri);
        assertThat(metadata.getBitmap(MediaMetadataCompat.METADATA_KEY_DISPLAY_ICON)).isNull();
        assertThat(metadata.getBitmap(MediaMetadataCompat.METADATA_KEY_ART)).isNull();
        assertThat(metadata.getBitmap(MediaMetadataCompat.METADATA_KEY_ALBUM_ART)).isNull();
        assertThat(metadata.containsKey(MediaMetadataCompat.METADATA_KEY_BT_FOLDER_TYPE)).isFalse();
    }

    @Test
    public void avrcpFolderItem_toMediaMetadata() {
        String title = "Bluetooth Playlist";
        String artist = "Many";
        long totalTracks = 12;
        String genre = "Viking Metal";
        long playingTime = 301;
        String artHandle = "0000001";
        Uri uri = Uri.parse("content://somewhere");
        int type = AvrcpItem.FOLDER_TITLES;

        AvrcpItem.Builder builder = new AvrcpItem.Builder();
        builder.setItemType(AvrcpItem.TYPE_FOLDER);
        builder.setType(type);
        builder.setDevice(mDevice);
        builder.setUuid(UUID);
        builder.setDisplayableName(title);
        builder.setTitle(title);
        builder.setArtistName(artist);
        builder.setTotalNumberOfTracks(totalTracks);
        builder.setGenre(genre);
        builder.setPlayingTime(playingTime);
        builder.setCoverArtHandle(artHandle);
        builder.setCoverArtLocation(uri);

        AvrcpItem item = builder.build();
        MediaMetadataCompat metadata = item.toMediaMetadata();

        assertThat(metadata.getString(MediaMetadataCompat.METADATA_KEY_MEDIA_ID)).isEqualTo(UUID);
        assertThat(metadata.getString(MediaMetadataCompat.METADATA_KEY_DISPLAY_TITLE))
                .isEqualTo(title);
        assertThat(metadata.getString(MediaMetadataCompat.METADATA_KEY_TITLE)).isEqualTo(title);
        assertThat(metadata.getString(MediaMetadataCompat.METADATA_KEY_ARTIST)).isEqualTo(artist);
        assertThat(metadata.getString(MediaMetadataCompat.METADATA_KEY_ALBUM)).isNull();
        assertThat(metadata.getLong(MediaMetadataCompat.METADATA_KEY_NUM_TRACKS))
                .isEqualTo(totalTracks);
        assertThat(metadata.getString(MediaMetadataCompat.METADATA_KEY_GENRE)).isEqualTo(genre);
        assertThat(metadata.getLong(MediaMetadataCompat.METADATA_KEY_DURATION))
                .isEqualTo(playingTime);
        assertThat(Uri.parse(metadata.getString(MediaMetadataCompat.METADATA_KEY_DISPLAY_ICON_URI)))
                .isEqualTo(uri);
        assertThat(Uri.parse(metadata.getString(MediaMetadataCompat.METADATA_KEY_ART_URI)))
                .isEqualTo(uri);
        assertThat(Uri.parse(metadata.getString(MediaMetadataCompat.METADATA_KEY_ALBUM_ART_URI)))
                .isEqualTo(uri);
        assertThat(metadata.getBitmap(MediaMetadataCompat.METADATA_KEY_DISPLAY_ICON)).isNull();
        assertThat(metadata.getBitmap(MediaMetadataCompat.METADATA_KEY_ART)).isNull();
        assertThat(metadata.getBitmap(MediaMetadataCompat.METADATA_KEY_ALBUM_ART)).isNull();
        assertThat(metadata.getLong(MediaMetadataCompat.METADATA_KEY_BT_FOLDER_TYPE))
                .isEqualTo(type);
    }

    @Test
    public void avrcpItemNoDisplayName_toMediaItem() {
        String title = "Aaaaargh";
        Uri uri = Uri.parse("content://somewhere");

        AvrcpItem.Builder builder = new AvrcpItem.Builder();
        builder.setPlayable(true);
        builder.setUuid(UUID);
        builder.setTitle(title);
        builder.setCoverArtLocation(uri);

        AvrcpItem item = builder.build();
        MediaItem mediaItem = item.toMediaItem();
        MediaDescriptionCompat desc = mediaItem.getDescription();

        assertThat(mediaItem.isPlayable()).isTrue();
        assertThat(mediaItem.isBrowsable()).isFalse();
        assertThat(mediaItem.getMediaId()).isEqualTo(UUID);

        assertThat(desc.getMediaId()).isEqualTo(UUID);
        assertThat(desc.getMediaUri()).isNull();
        assertThat(desc.getTitle().toString()).isEqualTo(title);
        assertThat(desc.getSubtitle()).isNull();
        assertThat(desc.getIconUri()).isEqualTo(uri);
        assertThat(desc.getIconBitmap()).isNull();
    }

    @Test
    public void avrcpItemWithDisplayName_toMediaItem() {
        String title = "Aaaaargh";
        String displayName = "A Different Type of Aaaaargh";
        Uri uri = Uri.parse("content://somewhere");

        AvrcpItem.Builder builder = new AvrcpItem.Builder();
        builder.setPlayable(true);
        builder.setUuid(UUID);
        builder.setDisplayableName(displayName);
        builder.setTitle(title);
        builder.setCoverArtLocation(uri);

        AvrcpItem item = builder.build();
        MediaItem mediaItem = item.toMediaItem();
        MediaDescriptionCompat desc = mediaItem.getDescription();

        assertThat(mediaItem.isPlayable()).isTrue();
        assertThat(mediaItem.isBrowsable()).isFalse();
        assertThat(mediaItem.getMediaId()).isEqualTo(UUID);

        assertThat(desc.getMediaId()).isEqualTo(UUID);
        assertThat(desc.getMediaUri()).isNull();
        assertThat(desc.getTitle().toString()).isEqualTo(displayName);
        assertThat(desc.getSubtitle()).isNull();
        assertThat(desc.getIconUri()).isEqualTo(uri);
        assertThat(desc.getIconBitmap()).isNull();
    }

    @Test
    public void avrcpItemBrowsable_toMediaItem() {
        String title = "Aaaaargh";
        Uri uri = Uri.parse("content://somewhere");

        AvrcpItem.Builder builder = new AvrcpItem.Builder();
        builder.setBrowsable(true);
        builder.setUuid(UUID);
        builder.setTitle(title);
        builder.setCoverArtLocation(uri);

        AvrcpItem item = builder.build();
        MediaItem mediaItem = item.toMediaItem();
        MediaDescriptionCompat desc = mediaItem.getDescription();

        assertThat(mediaItem.isPlayable()).isFalse();
        assertThat(mediaItem.isBrowsable()).isTrue();
        assertThat(mediaItem.getMediaId()).isEqualTo(UUID);

        assertThat(desc.getMediaId()).isEqualTo(UUID);
        assertThat(desc.getMediaUri()).isNull();
        assertThat(desc.getTitle().toString()).isEqualTo(title);
        assertThat(desc.getSubtitle()).isNull();
        assertThat(desc.getIconUri()).isEqualTo(uri);
        assertThat(desc.getIconBitmap()).isNull();
    }

    @Test
    public void equals() {
        AvrcpItem item = new AvrcpItem.Builder().build();
        AvrcpItem itemEqual = new AvrcpItem.Builder().build();

        String notAvrcpItem = "notAvrcpItem";

        new EqualsTester()
                .addEqualityGroup(item, item, itemEqual)
                .addEqualityGroup(notAvrcpItem)
                .testEquals();
    }
}
