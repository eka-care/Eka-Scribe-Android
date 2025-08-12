package com.eka.voice2rx_sdk.networking

class LRUCache<K, V>(  // Maximum number of items in the cache.
    private val capacity: Int
) :
    LinkedHashMap<K, V>(capacity + 1, 1.0f, true) {
    override fun removeEldestEntry(p0: MutableMap.MutableEntry<K, V>?): Boolean {
        return size > capacity
    }
}