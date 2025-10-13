/*
 * Copyright (C) 2013 The Android Open Source Project
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

package com.android.bluetooth.map;

import android.content.ContentResolver;
import android.net.Uri;

import com.android.internal.annotations.VisibleForTesting;

/**
 * This class defines the minimum sets of data needed for a client to implement to claim support for
 * the Bluetooth Message Access Profile. Access to three data sets are needed:
 *
 * <ul>
 *   <li>Message data set containing lists of messages.
 *   <li>Account data set containing info on the existing accounts, and whether to expose these
 *       accounts. The content of the account data set is often sensitive information, hence care
 *       must be taken, not to reveal any personal information nor passwords. The accounts in this
 *       data base will be exposed in the settings menu, where the user is able to enable and
 *       disable the EXPOSE_FLAG, and thereby provide access to an account from another device,
 *       without any password protection the e-mail client might provide.
 *   <li>Folder data set with the folder structure for the messages. Each message is linked to an
 *       entry in this data set.
 *   <li>Conversation data set with the thread structure of the messages. Each message is linked to
 *       an entry in this data set.
 * </ul>
 *
 * To enable that the Bluetooth Message Access Server can detect the content provider implementing
 * this interface, the {@code provider} tag for the Bluetooth related content provider must have an
 * intent-filter like the following in the manifest:
 *
 * <pre class="prettyprint">&lt;provider  android:authorities="[PROVIDER AUTHORITY]"
 *             android:exported="true"
 *             android:enabled="true"
 *             android:permission="android.permission.BLUETOOTH_MAP"&gt;
 *   ...
 *      &lt;intent-filter&gt;
 *          &lt;action android:name="android.content.action.BLUETOOTH_MAP_PROVIDER" /&gt;
 *       &lt;/intent-filter&gt;
 *   ...
 *   &lt;/provider&gt;</pre>
 *
 * [PROVIDER AUTHORITY] shall be the providers authority value which implements this contract. Only
 * a single authority shall be used. The android.permission.BLUETOOTH_MAP permission is needed for
 * the provider.
 */
final class BluetoothMapContract {
    /** Constructor - should not be used */
    private BluetoothMapContract() {
        /* class should not be instantiated */
    }

    /**
     * Provider interface that should be used as intent-filter action in the provider section of the
     * manifest file.
     */
    static final String PROVIDER_INTERFACE_EMAIL =
            "android.bluetooth.action.BLUETOOTH_MAP_PROVIDER";

    static final String PROVIDER_INTERFACE_IM =
            "android.bluetooth.action.BLUETOOTH_MAP_IM_PROVIDER";

    /**
     * The Bluetooth Message Access profile allows a remote BT-MAP client to trigger an update of a
     * folder for a specific e-mail account, register for reception of new messages from the server.
     *
     * <p>Additionally the Bluetooth Message Access profile allows a remote BT-MAP client to push a
     * message to a folder - e.g. outbox or draft. The Bluetooth profile implementation will place a
     * new message in one of these existing folders through the content provider.
     *
     * <p>ContentProvider.call() is used for these purposes, and the METHOD_UPDATE_FOLDER method
     * name shall trigger an update of the specified folder for a specified account.
     *
     * <p>This shall be a non blocking call simply starting the update, and the update should both
     * send and receive messages, depending on what makes sense for the specified folder. Bundle
     * extra parameter will carry two INTEGER (long) values: EXTRA_UPDATE_ACCOUNT_ID containing the
     * account_id EXTRA_UPDATE_FOLDER_ID containing the folder_id of the folder to update
     *
     * <p>The status for send complete of messages shall be reported by updating the sent-flag and
     * e.g. for outbox messages, move them to the sent folder in the message table of the content
     * provider and trigger a change notification to any attached content observer.
     */
    static final String METHOD_UPDATE_FOLDER = "UpdateFolder";

    static final String EXTRA_UPDATE_ACCOUNT_ID = "UpdateAccountId";
    static final String EXTRA_UPDATE_FOLDER_ID = "UpdateFolderId";

    /**
     * The Bluetooth Message Access profile allows a remote BT-MAP Client to update the owners
     * presence and chat state
     *
     * <p>ContentProvider.call() is used for these purposes, and the METHOD_SET_OWNER_STATUS method
     * name shall trigger a change in owner/users presence or chat properties for an account or
     * conversation.
     *
     * <p>This shall be a non blocking call simply setting the properties, and the change should be
     * sent to the remote server/users, depending on what property is changed. Bundle extra
     * parameter will carry following values: EXTRA_ACCOUNT_ID containing the account_id
     * EXTRA_PRESENCE_STATE containing the presence state of the owner account EXTRA_PRESENCE_STATUS
     * containing the presence status text from the owner EXTRA_LAST_ACTIVE containing the last
     * activity time stamp of the owner account EXTRA_CHAT_STATE containing the chat state of a
     * specific conversation EXTRA_CONVERSATION_ID containing the conversation that is changed
     */
    static final String METHOD_SET_OWNER_STATUS = "SetOwnerStatus";

    static final String EXTRA_PRESENCE_STATE = "PresenceState";
    static final String EXTRA_PRESENCE_STATUS = "PresenceStatus";
    static final String EXTRA_LAST_ACTIVE = "LastActive";
    static final String EXTRA_CHAT_STATE = "ChatState";
    static final String EXTRA_CONVERSATION_ID = "ConversationId";

    /**
     * These column names are used as last path segment of the URI (getLastPathSegment()). Access to
     * a specific row in the tables is done by using the where-clause, hence support for .../#id if
     * not needed for the Email clients. The URI format for accessing the tables are as follows:
     * content://ProviderAuthority/TABLE_ACCOUNT
     * content://ProviderAuthority/account_id/TABLE_MESSAGE
     * content://ProviderAuthority/account_id/TABLE_FOLDER
     * content://ProviderAuthority/account_id/TABLE_CONVERSATION
     * content://ProviderAuthority/account_id/TABLE_CONVOCONTACT
     */

    /**
     * Build URI representing the given Accounts data-set in a Bluetooth provider. When queried, the
     * direct URI for the account with the given accountID is returned.
     */
    static Uri buildAccountUri(String authority) {
        return new Uri.Builder()
                .scheme(ContentResolver.SCHEME_CONTENT)
                .authority(authority)
                .appendPath(TABLE_ACCOUNT)
                .build();
    }

    /**
     * Build URI representing the given Message data-set in a Bluetooth provider. When queried, the
     * direct URI for the folder with the given accountID is returned.
     */
    static Uri buildFolderUri(String authority, String accountId) {
        return new Uri.Builder()
                .scheme(ContentResolver.SCHEME_CONTENT)
                .authority(authority)
                .appendPath(accountId)
                .appendPath(TABLE_FOLDER)
                .build();
    }

    static final String TABLE_ACCOUNT = "Account";

    static final String TABLE_MESSAGE = "Message";
    @VisibleForTesting static final String TABLE_FOLDER = "Folder";
    static final String TABLE_CONVERSATION = "Conversation";
    static final String TABLE_CONVOCONTACT = "ConvoContact";

    /**
     * Mandatory folders for the Bluetooth message access profile. The email client shall at least
     * implement the following root folders. E.g. as a mapping for them such that the naming will
     * match the underlying matching folder ID's.
     */
    static final String FOLDER_NAME_INBOX = "inbox";

    static final String FOLDER_NAME_SENT = "sent";
    static final String FOLDER_NAME_OUTBOX = "outbox";
    static final String FOLDER_NAME_DRAFT = "draft";
    static final String FOLDER_NAME_DELETED = "deleted";

    /** Folder IDs to be used with Instant Messaging virtual folders */
    static final long FOLDER_ID_OTHER = 0;

    static final long FOLDER_ID_INBOX = 1;
    static final long FOLDER_ID_SENT = 2;
    static final long FOLDER_ID_DRAFT = 3;
    static final long FOLDER_ID_OUTBOX = 4;
    static final long FOLDER_ID_DELETED = 5;

    /**
     * To push RFC2822 encoded messages into a folder and read RFC2822 encoded messages from a
     * folder, the openFile() interface will be used as follows: Open a file descriptor to a
     * message. Two modes supported for read: With and without attachments. One mode exist for write
     * and the actual content will be with or without attachments.
     *
     * <p>mode will be "r" for read and "w" for write, never "rw".
     *
     * <p>URI format: The URI scheme is as follows. For reading messages with attachments:
     * content://ProviderAuthority/account_id/TABLE_MESSAGE/msgId Note: This shall be an offline
     * operation, including only message parts and attachments already downloaded to the device.
     *
     * <p>For reading messages without attachments:
     * content://ProviderAuthority/account_id/TABLE_MESSAGE/msgId/FILE_MSG_NO_ATTACHMENTS Note: This
     * shall be an offline operation, including only message parts already downloaded to the device.
     *
     * <p>For downloading and reading messages with attachments:
     * content://ProviderAuthority/account_id/TABLE_MESSAGE/msgId/FILE_MSG_DOWNLOAD Note: This shall
     * download the message content and all attachments if possible, else throw an IOException.
     *
     * <p>For downloading and reading messages without attachments:
     * content://ProviderAuthority/account_id/TABLE_MESSAGE/msgId/FILE_MSG_DOWNLOAD_NO_ATTACHMENTS
     * Note: This shall download the message content if possible, else throw an IOException.
     *
     * <p>When reading from the file descriptor, the content provider shall return a stream of bytes
     * containing a RFC2822 encoded message, as if the message was send to an email server.
     *
     * <p>When a byte stream is written to the file descriptor, the content provider shall decode
     * the RFC2822 encoded data and insert the message into the TABLE_MESSAGE at the ID supplied in
     * URI - additionally the message content shall be stored in the underlying data base structure
     * as if the message was received from an email server. The Message ID will be created using a
     * insert on the TABLE_MESSAGE prior to calling openFile(). Hence the procedure for inserting a
     * message is: - uri/msgId = insert(uri, value: folderId=xxx) - fd = openFile(uri/msgId) -
     * fd.write (RFC2822 encoded data)
     *
     * <p>The Bluetooth Message Access Client might not know what to put into the From: header nor
     * have a valid time stamp, hence the content provider shall check if the From: and Date:
     * headers have been set by the message written, else it must fill in appropriate values.
     */
    static final String FILE_MSG_NO_ATTACHMENTS = "NO_ATTACHMENTS";

    /**
     * Account Table The columns needed to supply account information. The e-mail client app may
     * choose to expose all e-mails as being from the same account, but it is not recommended, as
     * this would be a violation of the Bluetooth specification. The Bluetooth Message Access
     * settings activity will provide the user the ability to change the FLAG_EXPOSE values for each
     * account in this table. The Bluetooth Message Access service will read the values when
     * Bluetooth is turned on, and again on every notified change through the content observer
     * interface.
     */
    interface AccountColumns {

        /**
         * The unique ID for a row.
         *
         * <p>Type: INTEGER (long)
         */
        String _ID = "_id";

        /**
         * The account name to display to the user on the device when selecting whether or not to
         * share the account over Bluetooth.
         *
         * <p>The account display name should not reveal any sensitive information e.g. email-
         * address, as it will be added to the Bluetooth SDP record, which can be read by any
         * Bluetooth enabled device. (Access to any account content is only provided to
         * authenticated devices). It is recommended that if the email client uses the email address
         * as account name, then the address should be obfuscated (i.e. replace "@" with ".")
         *
         * <p>Type: TEXT read-only
         */
        String ACCOUNT_DISPLAY_NAME = "account_display_name";

        /**
         * Expose this account to other authenticated Bluetooth devices. If the expose flag is set,
         * this account will be listed as an available account to access from another Bluetooth
         * device.
         *
         * <p>This is a read/write flag, that can be set either from within the E-mail client UI or
         * the Bluetooth settings menu.
         *
         * <p>It is recommended to either ask the user whether to expose the account, or set this to
         * "show" as default.
         *
         * <p>This setting shall not be used to enforce whether or not an account should be shared
         * or not if the account is bound by an administrative security policy. In this case the
         * email app should not list the account at all if it is not to be sharable over BT.
         *
         * <p>Type: INTEGER (boolean) hide = 0, show = 1
         */
        String FLAG_EXPOSE = "flag_expose";

        /**
         * The account unique identifier representing this account. For most IM clients this will be
         * the fully qualified user name to which an invite message can be sent, from another use.
         *
         * <p>e.g.: "map_test_user_12345@gmail.com" - for a Hangouts account
         *
         * <p>This value will only be visible to authenticated Bluetooth devices, and will be
         * transmitted using an encrypted link.
         *
         * <p>Type: TEXT read-only
         */
        String ACCOUNT_UCI = "account_uci";

        /**
         * The Bluetooth SIG maintains a list of assigned numbers(text strings) for IM clients. If
         * your client/account has such a string, this is the place to return it. If supported by
         * both devices, the presence of this prefix will make it possible to respond to a message
         * by making a voice-call, using the same account information. (The call will be made using
         * the HandsFree profile)
         * https://www.bluetooth.org/en-us/specification/assigned-numbers/uniform-caller-identifiers
         *
         * <p>e.g.: "hgus" - for Hangouts
         *
         * <p>Type: TEXT read-only
         */
        String ACCOUNT_UCI_PREFIX = "account_uci_PREFIX";
    }

    /**
     * The actual message table containing all messages. Content that must support filtering using
     * WHERE clauses: - To, From, Cc, Bcc, Date, ReadFlag, PriorityFlag, folder_id, account_id
     * Additional content that must be supplied: - Subject, AttachmentFlag, LoadedState,
     * MessageSize, AttachmentSize Content that must support update: - FLAG_READ and FOLDER_ID
     * (FOLDER_ID is used to move a message to deleted) Additional insert of a new message with the
     * following values shall be supported: - FOLDER_ID
     *
     * <p>When doing an insert on this table, the actual content of the message (subject, date etc)
     * written through file-i/o takes precedence over the inserted values and should overwrite them.
     */
    interface MessageColumns extends EmailMessageColumns {

        /**
         * The unique ID for a row.
         *
         * <p>Type: INTEGER (long)
         */
        String _ID = "_id";

        /**
         * The date the message was received as a unix timestamp (milliseconds since 00:00:00 UTC
         * 1/1-1970).
         *
         * <p>Type: INTEGER (long) read-only
         */
        String DATE = "date";

        // TODO REMOVE WHEN Parts Table is in place
        /**
         * Message body. Used by Instant Messaging
         *
         * <p>Type: TEXT read-only.
         */
        String BODY = "body";

        /**
         * Message subject.
         *
         * <p>Type: TEXT read-only.
         */
        String SUBJECT = "subject";

        /**
         * Message Read flag
         *
         * <p>Type: INTEGER (boolean) unread = 0, read = 1 read/write
         */
        String FLAG_READ = "flag_read";

        /**
         * Message Priority flag
         *
         * <p>Type: INTEGER (boolean) normal priority = 0, high priority = 1 read-only
         */
        String FLAG_HIGH_PRIORITY = "high_priority";

        /**
         * Reception state - the amount of the message that have been loaded from the server.
         *
         * <p>Type: TEXT see RECEPTION_STATE_* constants below read-only
         */
        String RECEPTION_STATE = "reception_state";

        /**
         * Delivery state - the amount of the message that have been loaded from the server.
         *
         * <p>Type: TEXT see DELIVERY_STATE_* constants below read-only
         */
        String DELIVERY_STATE = "delivery_state";

        /**
         * To be able to filter messages with attachments, we need this flag.
         *
         * <p>Type: INTEGER (boolean) no attachment = 0, attachment = 1 read-only
         */
        String FLAG_ATTACHMENT = "flag_attachment";

        /**
         * The overall size in bytes of the attachments of the message.
         *
         * <p>Type: INTEGER
         */
        String ATTACHMENT_SIZE = "attachment_size";

        /**
         * The mine type of the attachments for the message.
         *
         * <p>Type: TEXT read-only
         */
        String ATTACHMENT_MINE_TYPES = "attachment_mime_types";

        /**
         * The overall size in bytes of the message including any attachments. This value is
         * informative only and should be the size an email client would display as size for the
         * message.
         *
         * <p>Type: INTEGER read-only
         */
        String MESSAGE_SIZE = "message_size";

        /**
         * Indicates that the message or a part of it is protected by a DRM scheme.
         *
         * <p>Type: INTEGER (boolean) no DRM = 0, DRM protected = 1 read-only
         */
        String FLAG_PROTECTED = "flag_protected";

        /**
         * A comma-delimited list of FROM addresses in RFC2822 format. The list must be compatible
         * with Rfc822Tokenizer.tokenize();
         *
         * <p>Type: TEXT read-only
         */
        String FROM_LIST = "from_list";

        /**
         * A comma-delimited list of TO addresses in RFC2822 format. The list must be compatible
         * with Rfc822Tokenizer.tokenize();
         *
         * <p>Type: TEXT read-only
         */
        String TO_LIST = "to_list";

        /**
         * The unique ID for a row in the folder table in which this message belongs.
         *
         * <p>Type: INTEGER (long) read/write
         */
        String FOLDER_ID = "folder_id";

        /**
         * The unique ID for a row in the account table which owns this message.
         *
         * <p>Type: INTEGER (long) read-only
         */
        String ACCOUNT_ID = "account_id";

        /**
         * The ID identify the thread/conversation a message belongs to. If no thread id is
         * available, set value to "-1"
         *
         * <p>Type: INTEGER (long) read-only
         */
        String THREAD_ID = "thread_id";

        /**
         * The Name of the thread/conversation a message belongs to.
         *
         * <p>Type: TEXT read-only
         */
        String THREAD_NAME = "thread_name";
    }

    private interface EmailMessageColumns {

        /**
         * A comma-delimited list of CC addresses in RFC2822 format. The list must be compatible
         * with Rfc822Tokenizer.tokenize();
         *
         * <p>Type: TEXT read-only
         */
        String CC_LIST = "cc_list";

        /**
         * A comma-delimited list of BCC addresses in RFC2822 format. The list must be compatible
         * with Rfc822Tokenizer.tokenize();
         *
         * <p>Type: TEXT read-only
         */
        String BCC_LIST = "bcc_list";

        /**
         * A comma-delimited list of REPLY-TO addresses in RFC2822 format. The list must be
         * compatible with Rfc822Tokenizer.tokenize();
         *
         * <p>Type: TEXT read-only
         */
        String REPLY_TO_LIST = "reply_to_List";
    }

    /**
     * Indicates that the message, including any attachments, has been received from the server to
     * the device.
     */
    static final String RECEPTION_STATE_COMPLETE = "complete";

    /** Indicates the message is partially received from the email server. */
    @VisibleForTesting static final String RECEPTION_STATE_FRACTIONED = "fractioned";

    /**
     * Message folder structure MAP enforces use of a folder structure with mandatory folders: -
     * inbox, outbox, sent, deleted, draft User defined folders are supported. The folder table must
     * provide filtering (use of WHERE clauses) of the following entries: - account_id (linking the
     * folder to an e-mail account) - parent_id (linking the folders individually) The folder table
     * must have a folder name for each entry, and the mandatory folders MUST exist for each
     * account_id. The folders may be empty. Use the FOLDER_NAME_xxx constants for the mandatory
     * folders. Their names must not be translated into other languages, as the folder browsing is
     * string based, and many Bluetooth Message Clients will use these strings to navigate to the
     * folders.
     */
    interface FolderColumns {

        /**
         * The unique ID for a row.
         *
         * <p>Type: INTEGER (long) read-only
         */
        String _ID = "_id";

        /**
         * The folder display name to present to the user.
         *
         * <p>Type: TEXT read-only
         */
        String NAME = "name";

        /**
         * The _id-key to the account this folder refers to.
         *
         * <p>Type: INTEGER (long) read-only
         */
        String ACCOUNT_ID = "account_id";

        /**
         * The _id-key to the parent folder. -1 for root folders.
         *
         * <p>Type: INTEGER (long) read-only
         */
        String PARENT_FOLDER_ID = "parent_id";
    }

    /**
     * Message conversation structure. Enables use of a conversation structure for messages across
     * folders, further binding contacts to conversations. Content that must be supplied: - Name,
     * LastActivity, ReadStatus, VersionCounter Content that must support update: - READ_STATUS,
     * LAST_ACTIVITY and VERSION_COUNTER (VERSION_COUNTER used to validity of _ID) Additional insert
     * of a new conversation with the following values shall be supported: - FOLDER_ID When querying
     * this table, the cursor returned must contain one row for each contact member in a thread. For
     * filter/search parameters attributes to the URI will be used. The following columns must
     * support filtering: - ConvoContactColumns.NAME - ConversationColumns.THREAD_ID -
     * ConversationColumns.LAST_ACTIVITY - ConversationColumns.READ_STATUS
     */
    interface ConversationColumns extends ConvoContactColumns {
        /**
         * The unique ID for a Thread.
         *
         * <p>Type: INTEGER (long) read-only
         */
        String THREAD_ID = "thread_id";

        /**
         * The name of the conversation, e.g. group name in case of group chat
         *
         * <p>Type: TEXT read-only
         */
        String THREAD_NAME = "thread_name";

        /**
         * The time stamp of the last activity in the conversation as a unix timestamp (milliseconds
         * since 00:00:00 UTC 1/1-1970)
         *
         * <p>Type: INTEGER (long) read-only
         */
        String LAST_THREAD_ACTIVITY = "last_thread_activity";

        /**
         * The status on the conversation, either 'read' or 'unread'
         *
         * <p>Type: INTEGER (boolean) unread = 0, read = 1 read/write
         */
        String READ_STATUS = "read_status";

        /**
         * A counter that keep tack of version of the table content, count up on ID reuse
         *
         * <p>Type: INTEGER (long) read-only
         */
        // TODO: IS THIS NECESSARY - should it be in the database?
        // CB: If we need it, it must be in the database, or initialized with a random value at
        //     BT-ON
        // UPDATE: TODO: Change to the last_activity time stamp (as a long value). This will
        //         provide the information needed for BT clients - currently unused
        String VERSION_COUNTER = "version_counter";

        /**
         * A short description of the latest activity on conversation - typically part of the last
         * message.
         *
         * <p>Type: TEXT read-only
         */
        String SUMMARY = "convo_summary";
    }

    /**
     * MAP enables access to contacts for the conversation The conversation table must provide
     * filtering (using WHERE clauses) of following entries: - convo_id linking contacts to
     * conversations - x_bt_uid linking contacts to PBAP contacts The conversation contact table
     * must have a convo_id and a name for each entry.
     */
    interface ConvoContactColumns extends ChatStatusColumns, PresenceColumns {
        /**
         * The ID of the conversation the contact is part of.
         *
         * <p>Type: INTEGER (long) read-only
         */
        String CONVO_ID = "convo_id";

        /**
         * The name of contact in instant message application
         *
         * <p>Type: TEXT read-only
         */
        String NAME = "name";

        /**
         * The nickname of contact in instant message group chat conversation.
         *
         * <p>Type: TEXT read-only
         */
        String NICKNAME = "nickname";

        /**
         * The unique ID for all Bluetooth contacts available through PBAP.
         *
         * <p>Type: INTEGER (long) read-only
         */
        String X_BT_UID = "x_bt_uid";

        /**
         * The unique ID for the contact within the domain of the interfacing service. (UCI: Unique
         * Call Identity) It is expected that a message send to this ID will reach the recipient
         * regardless through which interface the message is send. For E-mail this will be the
         * e-mail address, for Google+ this will be the e-mail address associated with the contact
         * account. This ID
         *
         * <p>Type: TEXT read-only
         */
        String UCI = "x_bt_uci";
    }

    /** The name of query parameter used to filter on originator */
    static final String FILTER_ORIGINATOR_SUBSTRING = "org_sub_str";

    /**
     * The name of query parameter used to filter on read status. - true - return only threads with
     * all messages marked as read - false - return only threads with one or more unread messages -
     * omitted as query parameter - do not filter on read status
     */
    static final String FILTER_READ_STATUS = "read";

    /**
     * Time in ms since epoch. For conversations this will be for last activity as a unix timestamp
     * (milliseconds since 00:00:00 UTC 1/1-1970)
     */
    static final String FILTER_PERIOD_BEGIN = "t_begin";

    /**
     * Time in ms since epoch. For conversations this will be for last activity as a unix timestamp
     * (milliseconds since 00:00:00 UTC 1/1-1970)
     */
    static final String FILTER_PERIOD_END = "t_end";

    /** Filter for a specific ThreadId */
    static final String FILTER_THREAD_ID = "thread_id";

    interface ChatState {
        int UNKNOWN = 0;
        int INACTIVE = 1;
        int ACTIVE = 2;
        int COMPOSING = 3;
        int PAUSED = 4;
        int GONE = 5;
    }

    /**
     * Instant Messaging contact chat state information MAP enables access to contacts chat state
     * for the instant messaging application The chat state table must provide filtering (use of
     * WHERE clauses) of the following entries: - contact_id (linking chat state to contacts) -
     * thread_id (linking chat state to conversations and messages) The presence table must have a
     * contact_id for each entry.
     */
    interface ChatStatusColumns {
        /**
         * The chat state of contact in conversation, see {@link ChatState}
         *
         * <p>Type: INTEGER read-only
         */
        String CHAT_STATE = "chat_state";

        /**
         * The time stamp of the last time this contact was active in the conversation
         *
         * <p>Type: INTEGER (long) read-only
         */
        String LAST_ACTIVE = "last_active";
    }

    interface PresenceState {
        int UNKNOWN = 0;
        int OFFLINE = 1;
        int ONLINE = 2;
        int AWAY = 3;
        int DO_NOT_DISTURB = 4;
        int BUSY = 5;
        int IN_A_MEETING = 6;
    }

    /**
     * Instant Messaging contact presence information MAP enables access to contacts presences
     * information for the instant messaging application The presence table must provide filtering
     * (use of WHERE clauses) of the following entries: - contact_id (linking contacts to presence)
     * The presence table must have a contact_id for each entry.
     */
    interface PresenceColumns {
        /**
         * The presence state of contact, see {@link PresenceState}
         *
         * <p>Type: INTEGER read-only
         */
        String PRESENCE_STATE = "presence_state";

        /**
         * The priority of contact presence
         *
         * <p>Type: INTEGER read-only
         */
        // TODO: IS THIS NEEDED - not in latest specification
        String PRIORITY = "priority";

        /**
         * The last status text from contact
         *
         * <p>Type: TEXT read-only
         */
        String STATUS_TEXT = "status_text";

        /**
         * The time stamp of the last time the contact was online
         *
         * <p>Type: INTEGER (long) read-only
         */
        String LAST_ONLINE = "last_online";
    }

    /** A projection of all the columns in the Message table */
    static final String[] BT_MESSAGE_PROJECTION =
            new String[] {
                MessageColumns._ID,
                MessageColumns.DATE,
                MessageColumns.SUBJECT,
                // TODO REMOVE WHEN Parts Table is in place
                MessageColumns.BODY,
                MessageColumns.MESSAGE_SIZE,
                MessageColumns.FOLDER_ID,
                MessageColumns.FLAG_READ,
                MessageColumns.FLAG_PROTECTED,
                MessageColumns.FLAG_HIGH_PRIORITY,
                MessageColumns.FLAG_ATTACHMENT,
                MessageColumns.ATTACHMENT_SIZE,
                MessageColumns.FROM_LIST,
                MessageColumns.TO_LIST,
                MessageColumns.CC_LIST,
                MessageColumns.BCC_LIST,
                MessageColumns.REPLY_TO_LIST,
                MessageColumns.RECEPTION_STATE,
                MessageColumns.DELIVERY_STATE,
                MessageColumns.THREAD_ID
            };

    static final String[] BT_INSTANT_MESSAGE_PROJECTION =
            new String[] {
                MessageColumns._ID,
                MessageColumns.DATE,
                MessageColumns.SUBJECT,
                MessageColumns.MESSAGE_SIZE,
                MessageColumns.FOLDER_ID,
                MessageColumns.FLAG_READ,
                MessageColumns.FLAG_PROTECTED,
                MessageColumns.FLAG_HIGH_PRIORITY,
                MessageColumns.FLAG_ATTACHMENT,
                MessageColumns.ATTACHMENT_SIZE,
                MessageColumns.ATTACHMENT_MINE_TYPES,
                MessageColumns.FROM_LIST,
                MessageColumns.TO_LIST,
                MessageColumns.RECEPTION_STATE,
                MessageColumns.DELIVERY_STATE,
                MessageColumns.THREAD_ID,
                MessageColumns.THREAD_NAME
            };

    /** A projection of all the columns in the Account table */
    static final String[] BT_ACCOUNT_PROJECTION =
            new String[] {
                AccountColumns._ID, AccountColumns.ACCOUNT_DISPLAY_NAME, AccountColumns.FLAG_EXPOSE,
            };

    /**
     * A projection of all the columns in the Account table TODO: Is this the way to differentiate
     */
    static final String[] BT_IM_ACCOUNT_PROJECTION =
            new String[] {
                AccountColumns._ID,
                AccountColumns.ACCOUNT_DISPLAY_NAME,
                AccountColumns.FLAG_EXPOSE,
                AccountColumns.ACCOUNT_UCI,
                AccountColumns.ACCOUNT_UCI_PREFIX
            };

    /** A projection of all the columns in the Folder table */
    static final String[] BT_FOLDER_PROJECTION =
            new String[] {
                FolderColumns._ID,
                FolderColumns.NAME,
                FolderColumns.ACCOUNT_ID,
                FolderColumns.PARENT_FOLDER_ID
            };

    /** A projection of all the columns in the Conversation table */
    static final String[] BT_CONVERSATION_PROJECTION =
            new String[] {
                /* Thread information */
                ConversationColumns.THREAD_ID,
                ConversationColumns.THREAD_NAME,
                ConversationColumns.READ_STATUS,
                ConversationColumns.LAST_THREAD_ACTIVITY,
                ConversationColumns.VERSION_COUNTER,
                ConversationColumns.SUMMARY,
                /* Contact information */
                ConversationColumns.UCI,
                ConversationColumns.NAME,
                ConversationColumns.NICKNAME,
                ConversationColumns.CHAT_STATE,
                ConversationColumns.LAST_ACTIVE,
                ConversationColumns.X_BT_UID,
                ConversationColumns.PRESENCE_STATE,
                ConversationColumns.STATUS_TEXT,
                ConversationColumns.PRIORITY
            };

    /** A projection of the Contact Info and Presence columns in the Contact Info in table */
    static final String[] BT_CONTACT_CHATSTATE_PRESENCE_PROJECTION =
            new String[] {
                ConvoContactColumns.UCI,
                ConvoContactColumns.CONVO_ID,
                ConvoContactColumns.NAME,
                ConvoContactColumns.NICKNAME,
                ConvoContactColumns.X_BT_UID,
                ConvoContactColumns.CHAT_STATE,
                ConvoContactColumns.LAST_ACTIVE,
                ConvoContactColumns.PRESENCE_STATE,
                ConvoContactColumns.PRIORITY,
                ConvoContactColumns.STATUS_TEXT,
                ConvoContactColumns.LAST_ONLINE
            };

    /** A projection of the Contact Info the columns in Contacts Info table */
    static final String[] BT_CONTACT_PROJECTION =
            new String[] {
                ConvoContactColumns.UCI,
                ConvoContactColumns.CONVO_ID,
                ConvoContactColumns.X_BT_UID,
                ConvoContactColumns.NAME,
                ConvoContactColumns.NICKNAME
            };
}
