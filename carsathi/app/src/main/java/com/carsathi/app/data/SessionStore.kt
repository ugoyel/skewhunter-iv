package com.carsathi.app.data

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

private val Context.dataStore by preferencesDataStore(name = "carsathi_prefs")

/** Persists the signed-in member and a few app-level flags. */
class SessionStore(context: Context, scope: CoroutineScope) {
    private val store = context.applicationContext.dataStore
    private val keyUser = longPreferencesKey("user_id")
    private val keySeededUntil = longPreferencesKey("seeded_until")
    private val keyAskedNotif = booleanPreferencesKey("asked_notifications")

    private val userIdFlow: Flow<Long?> = store.data.map { it[keyUser] }

    private val _userState = MutableStateFlow<Long?>(null)

    /** null until DataStore has been read, then -1 (signed out) or the member id. */
    val userState: StateFlow<Long?> = _userState

    init {
        scope.launch { _userState.value = store.data.first()[keyUser] ?: -1L }
    }

    val userId: Long get() = userState.value ?: -1L

    suspend fun currentUserId(): Long? = userIdFlow.first()

    suspend fun setUser(id: Long?) {
        store.edit { if (id == null) it.remove(keyUser) else it[keyUser] = id }
        _userState.value = id ?: -1L
    }

    suspend fun seededUntil(): Long = store.data.first()[keySeededUntil] ?: 0L

    suspend fun setSeededUntil(day: Long) {
        store.edit { it[keySeededUntil] = day }
    }

    suspend fun askedNotifications(): Boolean = store.data.first()[keyAskedNotif] ?: false

    suspend fun setAskedNotifications() {
        store.edit { it[keyAskedNotif] = true }
    }
}
