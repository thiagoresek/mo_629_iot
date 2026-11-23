package com.example.farolestrela

import android.content.Context
import android.util.Log

object CapRepository {
    private var messageMap: Map<String, CapMessage> = emptyMap()
    private var isInitialized = false

    fun init(context: Context) {
        if (isInitialized && messageMap.isNotEmpty()) return
        try {
            Log.i("StarIoTDebug", "CapRepository: Attempting to load cap_messages.xml from assets...")
            val inputStream = context.assets.open("cap_messages.xml")
            val messages = CapXmlParser.parse(inputStream)
            messageMap = messages.associateBy { it.identifier }
            isInitialized = true
            Log.i("StarIoTDebug", "CapRepository: Successfully loaded ${messages.size} CAP messages from assets. Keys: ${messageMap.keys}")
        } catch (e: Exception) {
            Log.e("StarIoTDebug", "CapRepository: FAILED to load CAP messages: ${e.message}", e)
        }
    }

    fun getMessageById(identifier: String): CapMessage? {
        if (!isInitialized || messageMap.isEmpty()) {
            Log.w("StarIoTDebug", "CapRepository: getMessageById called before initialization or map is empty. Re-initializing...")
            // Try to re-init if context is available, but since we don't have context here, ensure init was called
        }
        Log.i("StarIoTDebug", "CapRepository: Looking up message by ID: '$identifier'. Available keys: ${messageMap.keys}")
        val trimmed = identifier.trim()
        if (trimmed.isEmpty()) return null

        // 1. Exact match
        messageMap[trimmed]?.let { 
            Log.i("StarIoTDebug", "CapRepository: Found exact match for '$trimmed': ${it.headline}")
            return it 
        }

        // 2. Case-insensitive match
        messageMap.entries.firstOrNull { it.key.trim().equals(trimmed, ignoreCase = true) }?.value?.let { 
            Log.i("StarIoTDebug", "CapRepository: Found case-insensitive match for '$trimmed': ${it.headline}")
            return it 
        }

        // 3. Numeric match (handles "1" vs "0001")
        val numericId = trimmed.toIntOrNull()
        if (numericId != null) {
            messageMap.entries.firstOrNull { it.key.trim().toIntOrNull() == numericId }?.value?.let { 
                Log.i("StarIoTDebug", "CapRepository: Found numeric match for '$trimmed' (numeric: $numericId): ${it.headline}")
                return it 
            }
        }

        Log.w("StarIoTDebug", "CapRepository: No matching message found for ID '$trimmed'")
        return null
    }
}
