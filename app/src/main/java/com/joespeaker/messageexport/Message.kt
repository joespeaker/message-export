package com.joespeaker.messageexport

import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

enum class Direction { SENT, RECEIVED }

enum class MessageType { SMS, MMS }

data class Message(
    val threadId: Long,
    val timestampMillis: Long,
    val direction: Direction,
    val address: String,
    val contactName: String?,
    val body: String,
    val messageType: MessageType
)

private val isoFormat = ThreadLocal.withInitial {
    SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US).apply {
        timeZone = TimeZone.getTimeZone("UTC")
    }
}

fun List<Message>.toJsonArray(): JSONArray {
    val array = JSONArray()
    for (message in this) {
        val obj = JSONObject()
        obj.put("thread_id", message.threadId)
        obj.put("date", isoFormat.get()!!.format(Date(message.timestampMillis)))
        obj.put("direction", if (message.direction == Direction.SENT) "sent" else "received")
        obj.put("address", message.address)
        obj.put("contact_name", message.contactName ?: JSONObject.NULL)
        obj.put("body", message.body)
        obj.put("message_type", if (message.messageType == MessageType.SMS) "sms" else "mms")
        array.put(obj)
    }
    return array
}
