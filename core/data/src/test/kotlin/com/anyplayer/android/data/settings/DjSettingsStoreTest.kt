package com.anyplayer.android.data.settings

import android.content.SharedPreferences
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

class DjSettingsStoreTest {
    @Test fun storedIdsAreTrimmedAndBlankMeansCatalogDefaultLikeLegacy() = runTest {
        val prefs = MemoryPreferences(mutableMapOf("selected_dj_voice_id" to "  voice-1 \n", "selected_dj_model_id" to "   "))
        val store = SecureDjSettingsStore { prefs }
        val loaded = store.awaitLoaded()
        assertEquals("voice-1", loaded.selectedVoiceId)
        assertNull(loaded.selectedModelId)
    }

    @Test fun blankOrNullIdsRemoveTheKeyInsteadOfStoringIt() = runTest {
        val prefs = MemoryPreferences(mutableMapOf("selected_dj_voice_id" to "voice", "selected_dj_model_id" to "model"))
        val store = SecureDjSettingsStore { prefs }
        store.update { it.copy(selectedVoiceId = " ", selectedModelId = " model-2 ") }
        assertFalse(prefs.contains("selected_dj_voice_id"))
        assertEquals("model-2", prefs.getString("selected_dj_model_id", null))
        assertNull(store.settings.value.selectedVoiceId)
        assertEquals("model-2", store.settings.value.selectedModelId)
        store.update { it.copy(selectedModelId = null) }
        assertFalse(prefs.contains("selected_dj_model_id"))
    }
}

/** Map-backed SharedPreferences; edits are applied on commit/apply. */
private class MemoryPreferences(private val values: MutableMap<String, Any?> = mutableMapOf()) : SharedPreferences {
    override fun getAll(): MutableMap<String, *> = values.toMutableMap()
    override fun getString(key: String, defValue: String?) = values[key] as String? ?: defValue
    @Suppress("UNCHECKED_CAST")
    override fun getStringSet(key: String, defValues: MutableSet<String>?) = values[key] as MutableSet<String>? ?: defValues
    override fun getInt(key: String, defValue: Int) = values[key] as Int? ?: defValue
    override fun getLong(key: String, defValue: Long) = values[key] as Long? ?: defValue
    override fun getFloat(key: String, defValue: Float) = values[key] as Float? ?: defValue
    override fun getBoolean(key: String, defValue: Boolean) = values[key] as Boolean? ?: defValue
    override fun contains(key: String) = values.containsKey(key)
    override fun registerOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener) = Unit
    override fun unregisterOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener) = Unit
    override fun edit(): SharedPreferences.Editor = object : SharedPreferences.Editor {
        private val changes = mutableMapOf<String, Any?>()
        private val removals = mutableSetOf<String>()
        private var clear = false
        private fun put(key: String, value: Any?) = apply { if (value == null) removals += key else changes[key] = value }
        override fun putString(key: String, value: String?) = put(key, value)
        override fun putStringSet(key: String, values: MutableSet<String>?) = put(key, values)
        override fun putInt(key: String, value: Int) = put(key, value)
        override fun putLong(key: String, value: Long) = put(key, value)
        override fun putFloat(key: String, value: Float) = put(key, value)
        override fun putBoolean(key: String, value: Boolean) = put(key, value)
        override fun remove(key: String) = apply { removals += key }
        override fun clear() = apply { clear = true }
        override fun commit(): Boolean {
            if (clear) values.clear()
            removals.forEach(values::remove)
            values.putAll(changes)
            return true
        }
        override fun apply() { commit() }
    }
}
