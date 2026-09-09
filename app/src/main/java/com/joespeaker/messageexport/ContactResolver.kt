package com.joespeaker.messageexport

import android.content.ContentResolver
import android.net.Uri
import android.provider.ContactsContract

/** Resolves phone numbers to contact display names, caching lookups for the run. */
class ContactResolver(private val resolver: ContentResolver) {

    // Nullable values mean MutableMap.getOrPut can't be used directly - it treats a cached
    // null (unresolved contact) as a cache miss and would re-query every time.
    private val cache = HashMap<String, String?>()

    fun lookupName(address: String): String? {
        if (cache.containsKey(address)) return cache[address]
        val name = queryDisplayName(address)
        cache[address] = name
        return name
    }

    /** Given a possibly comma-separated address list, returns a matching comma-separated
     * name list, or null if none of the addresses resolved to a contact. */
    fun lookupNames(addresses: List<String>): String? {
        val resolved = addresses.map { lookupName(it) }
        if (resolved.all { it == null }) return null
        return resolved.mapIndexed { i, name -> name ?: addresses[i] }.joinToString(", ")
    }

    private fun queryDisplayName(address: String): String? {
        if (address.isBlank()) return null
        val uri: Uri = Uri.withAppendedPath(
            ContactsContract.PhoneLookup.CONTENT_FILTER_URI,
            Uri.encode(address)
        )
        val projection = arrayOf(ContactsContract.PhoneLookup.DISPLAY_NAME)
        resolver.query(uri, projection, null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) {
                val idx = cursor.getColumnIndex(ContactsContract.PhoneLookup.DISPLAY_NAME)
                if (idx >= 0) return cursor.getString(idx)
            }
        }
        return null
    }
}
