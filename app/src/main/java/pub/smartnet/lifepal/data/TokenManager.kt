package pub.smartnet.lifepal.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.first

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "session")

class TokenManager(private val context: Context) {

    companion object {
        private val ACCESS_TOKEN_KEY = stringPreferencesKey("access_token")
        private val REFRESH_TOKEN_KEY = stringPreferencesKey("refresh_token")
        private val SERVER_ADDRESS_KEY = stringPreferencesKey("server_address")
        private val USERNAME_KEY = stringPreferencesKey("username")
    }

    suspend fun saveTokens(accessToken: String, refreshToken: String) {
        context.dataStore.edit {
            it[ACCESS_TOKEN_KEY] = accessToken
            it[REFRESH_TOKEN_KEY] = refreshToken
        }
    }

    suspend fun getAccessToken(): String? {
        val preferences = context.dataStore.data.first()
        return preferences[ACCESS_TOKEN_KEY]
    }

    suspend fun getRefreshToken(): String? {
        val preferences = context.dataStore.data.first()
        return preferences[REFRESH_TOKEN_KEY]
    }

    suspend fun saveServerAddress(address: String) {
        context.dataStore.edit {
            it[SERVER_ADDRESS_KEY] = address
        }
    }

    suspend fun getServerAddress(): String? {
        val preferences = context.dataStore.data.first()
        return preferences[SERVER_ADDRESS_KEY]
    }

    suspend fun saveUsername(username: String) {
        context.dataStore.edit {
            it[USERNAME_KEY] = username
        }
    }

    suspend fun getUsername(): String? {
        val preferences = context.dataStore.data.first()
        return preferences[USERNAME_KEY]
    }
    
    suspend fun clearSessionData() {
        context.dataStore.edit {
            it.remove(ACCESS_TOKEN_KEY)
            it.remove(REFRESH_TOKEN_KEY)
            it.remove(USERNAME_KEY)
        }
    }

    suspend fun clearAll() {
        context.dataStore.edit {
            it.clear()
        }
    }
}
