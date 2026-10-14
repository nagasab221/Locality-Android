package com.locality.mobile.data

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

private val Context.appPrefsDataStore by preferencesDataStore(name = "app_prefs")

/**
 * App-level preferences: download behavior and smart-downloads. Kept separate
 * from the server config store so clearing the server never wipes these.
 */
class SettingsStore(private val context: Context) {
    private val wifiOnlyKey = booleanPreferencesKey("downloadWifiOnly")
    private val smartEnabledKey = booleanPreferencesKey("smartDownloads")
    private val smartBudgetKey = intPreferencesKey("smartBudgetGb")
    private val smartBundlesKey = stringSetPreferencesKey("smartBundleIds")

    /** Restrict transfers to unmetered (Wi-Fi) networks. Default on, like most apps. */
    val wifiOnly: Flow<Boolean> = context.appPrefsDataStore.data.map { it[wifiOnlyKey] ?: true }

    /** Auto-download a rotating selection based on what's been watched. */
    val smartEnabled: Flow<Boolean> = context.appPrefsDataStore.data.map { it[smartEnabledKey] ?: false }

    /** Disk budget (GB) smart downloads may fill before evicting old picks. */
    val smartBudgetGb: Flow<Int> = context.appPrefsDataStore.data.map { it[smartBudgetKey] ?: 5 }

    /** Bundle ids that Smart Downloads created, so eviction never touches the
     *  user's own manual downloads. Stored here to avoid a Room schema change
     *  (the DB uses destructive migration — a new column would wipe downloads). */
    val smartBundleIds: Flow<Set<String>> = context.appPrefsDataStore.data.map { it[smartBundlesKey] ?: emptySet() }

    suspend fun setWifiOnly(v: Boolean) = context.appPrefsDataStore.edit { it[wifiOnlyKey] = v }.let { }
    suspend fun setSmartEnabled(v: Boolean) = context.appPrefsDataStore.edit { it[smartEnabledKey] = v }.let { }
    suspend fun setSmartBudgetGb(v: Int) = context.appPrefsDataStore.edit { it[smartBudgetKey] = v.coerceIn(1, 200) }.let { }
    suspend fun setSmartBundleIds(ids: Set<String>) = context.appPrefsDataStore.edit { it[smartBundlesKey] = ids }.let { }

    suspend fun snapshotSmartBundleIds(): Set<String> = smartBundleIds.first()
}
