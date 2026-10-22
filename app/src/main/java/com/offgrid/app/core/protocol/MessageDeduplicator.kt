package com.offgrid.app.core.protocol

import java.util.Collections
import java.util.LinkedHashMap

class MessageDeduplicator(private val capacity: Int = 1000) {
    private val seenMessageIds = Collections.synchronizedMap(
        object : LinkedHashMap<String, Long>(capacity, 0.75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Long>?): Boolean {
                return size > capacity
            }
        }
    )

    fun isDuplicate(messageId: String): Boolean {
        return seenMessageIds.containsKey(messageId)
    }

    /**
     * Records the message ID. Returns true if it was new, or false if it was already seen.
     */
    fun recordMessage(messageId: String): Boolean {
        synchronized(seenMessageIds) {
            if (seenMessageIds.containsKey(messageId)) {
                return false
            }
            seenMessageIds[messageId] = System.currentTimeMillis()
            return true
        }
    }

    fun clear() {
        seenMessageIds.clear()
    }
}
