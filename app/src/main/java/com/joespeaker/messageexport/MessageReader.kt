package com.joespeaker.messageexport

import android.content.ContentResolver
import android.net.Uri
import android.provider.Telephony
import android.util.Log

/**
 * Reads all SMS and MMS messages from the on-device providers and merges them into a single
 * timestamp-sorted list. Read-only: never touches the send/receive path.
 */
class MessageReader(private val resolver: ContentResolver) {

    private val contacts = ContactResolver(resolver)

    fun readAll(): List<Message> {
        val messages = ArrayList<Message>()
        messages.addAll(readSms())
        messages.addAll(readMms())
        messages.sortBy { it.timestampMillis }
        return messages
    }

    private fun readSms(): List<Message> {
        val result = ArrayList<Message>()
        val projection = arrayOf(
            Telephony.Sms.THREAD_ID,
            Telephony.Sms.ADDRESS,
            Telephony.Sms.DATE,
            Telephony.Sms.TYPE,
            Telephony.Sms.BODY
        )
        // Only inbox (received) and sent messages - drafts/outbox/failed/queued are not
        // real conversation history.
        val selection = "${Telephony.Sms.TYPE} = ? OR ${Telephony.Sms.TYPE} = ?"
        val selectionArgs = arrayOf(
            Telephony.Sms.MESSAGE_TYPE_INBOX.toString(),
            Telephony.Sms.MESSAGE_TYPE_SENT.toString()
        )

        resolver.query(Telephony.Sms.CONTENT_URI, projection, selection, selectionArgs, null)
            ?.use { cursor ->
                val threadIdIdx = cursor.getColumnIndex(Telephony.Sms.THREAD_ID)
                val addressIdx = cursor.getColumnIndex(Telephony.Sms.ADDRESS)
                val dateIdx = cursor.getColumnIndex(Telephony.Sms.DATE)
                val typeIdx = cursor.getColumnIndex(Telephony.Sms.TYPE)
                val bodyIdx = cursor.getColumnIndex(Telephony.Sms.BODY)

                while (cursor.moveToNext()) {
                    val address = cursor.getString(addressIdx) ?: continue
                    val body = cursor.getString(bodyIdx) ?: ""
                    val type = cursor.getInt(typeIdx)
                    val direction = if (type == Telephony.Sms.MESSAGE_TYPE_SENT) {
                        Direction.SENT
                    } else {
                        Direction.RECEIVED
                    }
                    result.add(
                        Message(
                            threadId = cursor.getLong(threadIdIdx),
                            timestampMillis = cursor.getLong(dateIdx),
                            direction = direction,
                            address = address,
                            contactName = contacts.lookupName(address),
                            body = body,
                            messageType = MessageType.SMS
                        )
                    )
                }
            }
        return result
    }

    private fun readMms(): List<Message> {
        val result = ArrayList<Message>()
        val projection = arrayOf(
            Telephony.Mms._ID,
            Telephony.Mms.THREAD_ID,
            Telephony.Mms.DATE,
            Telephony.Mms.MESSAGE_BOX
        )
        val selection = "${Telephony.Mms.MESSAGE_BOX} = ? OR ${Telephony.Mms.MESSAGE_BOX} = ?"
        val selectionArgs = arrayOf(
            Telephony.Mms.MESSAGE_BOX_INBOX.toString(),
            Telephony.Mms.MESSAGE_BOX_SENT.toString()
        )

        resolver.query(Telephony.Mms.CONTENT_URI, projection, selection, selectionArgs, null)
            ?.use { cursor ->
                val idIdx = cursor.getColumnIndex(Telephony.Mms._ID)
                val threadIdIdx = cursor.getColumnIndex(Telephony.Mms.THREAD_ID)
                val dateIdx = cursor.getColumnIndex(Telephony.Mms.DATE)
                val boxIdx = cursor.getColumnIndex(Telephony.Mms.MESSAGE_BOX)

                while (cursor.moveToNext()) {
                    val msgId = cursor.getLong(idIdx)
                    val text = readMmsText(msgId) ?: continue // media-only MMS: skip
                    val box = cursor.getInt(boxIdx)
                    val direction = if (box == Telephony.Mms.MESSAGE_BOX_SENT) {
                        Direction.SENT
                    } else {
                        Direction.RECEIVED
                    }
                    val addrType = if (direction == Direction.SENT) ADDR_TYPE_TO else ADDR_TYPE_FROM
                    val addresses = readMmsAddresses(msgId, addrType)
                    if (addresses.isEmpty()) continue
                    val addressJoined = addresses.joinToString(",")

                    // Mms.DATE is stored in seconds since epoch, unlike Sms.DATE (millis).
                    val timestampMillis = cursor.getLong(dateIdx) * 1000L

                    result.add(
                        Message(
                            threadId = cursor.getLong(threadIdIdx),
                            timestampMillis = timestampMillis,
                            direction = direction,
                            address = addressJoined,
                            contactName = contacts.lookupNames(addresses),
                            body = text,
                            messageType = MessageType.MMS
                        )
                    )
                }
            }
        return result
    }

    private fun readMmsText(msgId: Long): String? {
        val uri = Uri.parse("content://mms/part")
        val projection = arrayOf(MMS_PART_COL_TEXT, MMS_PART_COL_CONTENT_TYPE)
        val selection = "$MMS_PART_COL_MSG_ID = ? AND $MMS_PART_COL_CONTENT_TYPE = ?"
        val selectionArgs = arrayOf(msgId.toString(), "text/plain")

        val builder = StringBuilder()
        try {
            resolver.query(uri, projection, selection, selectionArgs, null)?.use { cursor ->
                val textIdx = cursor.getColumnIndex(MMS_PART_COL_TEXT)
                while (cursor.moveToNext()) {
                    val text = if (textIdx >= 0) cursor.getString(textIdx) else null
                    if (!text.isNullOrEmpty()) {
                        if (builder.isNotEmpty()) builder.append("\n")
                        builder.append(text)
                    }
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed reading MMS parts for message $msgId", e)
            return null
        }
        return if (builder.isEmpty()) null else builder.toString()
    }

    private fun readMmsAddresses(msgId: Long, addrType: Int): List<String> {
        val uri = Uri.parse("content://mms/$msgId/addr")
        val projection = arrayOf(MMS_ADDR_COL_ADDRESS, MMS_ADDR_COL_TYPE)
        val addresses = ArrayList<String>()
        try {
            resolver.query(uri, projection, null, null, null)?.use { cursor ->
                val addressIdx = cursor.getColumnIndex(MMS_ADDR_COL_ADDRESS)
                val typeIdx = cursor.getColumnIndex(MMS_ADDR_COL_TYPE)
                while (cursor.moveToNext()) {
                    val type = if (typeIdx >= 0) cursor.getInt(typeIdx) else -1
                    if (type == addrType) {
                        val address = if (addressIdx >= 0) cursor.getString(addressIdx) else null
                        if (!address.isNullOrBlank() && address != "insert-address-token") {
                            addresses.add(address)
                        }
                    }
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed reading MMS addresses for message $msgId", e)
        }
        return addresses
    }

    companion object {
        private const val TAG = "MessageReader"

        // content://mms/part column names (not exposed as SDK constants).
        private const val MMS_PART_COL_TEXT = "text"
        private const val MMS_PART_COL_CONTENT_TYPE = "ct"
        private const val MMS_PART_COL_MSG_ID = "mid"

        // content://mms/{id}/addr column names.
        private const val MMS_ADDR_COL_ADDRESS = "address"
        private const val MMS_ADDR_COL_TYPE = "type"

        // PDU header address type constants (PduHeaders.FROM / PduHeaders.TO), not public SDK API.
        private const val ADDR_TYPE_FROM = 137
        private const val ADDR_TYPE_TO = 151
    }
}
