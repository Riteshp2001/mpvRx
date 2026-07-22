package app.gyrolet.mpvrx.domain.syncplay

import android.content.Context

data class SyncplayCredentials(
  val host: String = DEFAULT_HOST,
  val port: Int = DEFAULT_PORT,
  val username: String = DEFAULT_USERNAME,
  val room: String = DEFAULT_ROOM,
  val password: String = "",
) {
  val isValid: Boolean
    get() = host.isNotBlank() && port in 1..65535 && username.isNotBlank() && room.isNotBlank()

  companion object {
    const val DEFAULT_HOST = "syncplay.pl"
    const val DEFAULT_PORT = 8999
    const val DEFAULT_USERNAME = "MpvRxUser"
    const val DEFAULT_ROOM = "TestRoom"
  }
}

class SyncplayCredentialsStore(context: Context) {
  private val preferences = context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)

  fun load(): SyncplayCredentials =
    SyncplayCredentials(
      host = preferences.getString(KEY_HOST, SyncplayCredentials.DEFAULT_HOST)
        ?: SyncplayCredentials.DEFAULT_HOST,
      port = preferences.getInt(KEY_PORT, SyncplayCredentials.DEFAULT_PORT),
      username = preferences.getString(KEY_USERNAME, SyncplayCredentials.DEFAULT_USERNAME)
        ?: SyncplayCredentials.DEFAULT_USERNAME,
      room = preferences.getString(KEY_ROOM, SyncplayCredentials.DEFAULT_ROOM)
        ?: SyncplayCredentials.DEFAULT_ROOM,
      password = preferences.getString(KEY_PASSWORD, "").orEmpty(),
    )

  fun save(credentials: SyncplayCredentials) {
    preferences.edit()
      .putString(KEY_HOST, credentials.host)
      .putInt(KEY_PORT, credentials.port)
      .putString(KEY_USERNAME, credentials.username)
      .putString(KEY_ROOM, credentials.room)
      .putString(KEY_PASSWORD, credentials.password)
      .apply()
  }

  var reconnectRequested: Boolean
    get() = preferences.getBoolean(KEY_RECONNECT_REQUESTED, false)
    set(value) {
      preferences.edit().putBoolean(KEY_RECONNECT_REQUESTED, value).apply()
    }

  private companion object {
    const val PREFERENCES_NAME = "syncplay_connection"
    const val KEY_HOST = "host"
    const val KEY_PORT = "port"
    const val KEY_USERNAME = "username"
    const val KEY_ROOM = "room"
    const val KEY_PASSWORD = "password"
    const val KEY_RECONNECT_REQUESTED = "reconnect_requested"
  }
}
