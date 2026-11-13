package dev.spainclaro.tanoshi.settings

import android.content.Context
import android.content.SharedPreferences

/**
 * Small persisted app configuration. The Tanoshi server's own configuration
 * lives in `config.yml` (see [dev.spainclaro.tanoshi.server.ConfigManager]);
 * these values control the Android wrapper: which port to seed/edit, whether
 * the server is exposed on the LAN, and one-shot UI bookkeeping.
 */
object AppSettings {

    private const val PREFS_NAME = "tanoshi_settings"
    private const val KEY_PORT = "port"
    private const val KEY_EXPOSE_ON_LAN = "expose_on_lan"
    private const val KEY_ADMIN_CREATED = "admin_created"
    private const val KEY_NOTIFICATIONS_REQUESTED = "notifications_requested"
    private const val KEY_BATTERY_PROMPT_SHOWN = "battery_prompt_shown"
    private const val KEY_SERVER_SHOULD_RUN = "server_should_run"

    const val DEFAULT_PORT = 8080

    private lateinit var prefs: SharedPreferences

    fun init(context: Context) {
        prefs = context.applicationContext
            .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }

    private fun requirePrefs(): SharedPreferences {
        check(::prefs.isInitialized) { "AppSettings.init(context) was not called" }
        return prefs
    }

    /** Port the user wants the server on. Kept in sync with config.yml. */
    var port: Int
        get() = requirePrefs().getInt(KEY_PORT, DEFAULT_PORT)
        set(value) = requirePrefs().edit().putInt(KEY_PORT, value).apply()

    /** Bind the server to 0.0.0.0 instead of 127.0.0.1. */
    var exposeOnLan: Boolean
        get() = requirePrefs().getBoolean(KEY_EXPOSE_ON_LAN, false)
        set(value) = requirePrefs().edit().putBoolean(KEY_EXPOSE_ON_LAN, value).apply()

    /** Whether the first admin account has been created from the app. */
    var adminCreated: Boolean
        get() = requirePrefs().getBoolean(KEY_ADMIN_CREATED, false)
        set(value) = requirePrefs().edit().putBoolean(KEY_ADMIN_CREATED, value).apply()

    var notificationsRequested: Boolean
        get() = requirePrefs().getBoolean(KEY_NOTIFICATIONS_REQUESTED, false)
        set(value) = requirePrefs().edit().putBoolean(KEY_NOTIFICATIONS_REQUESTED, value).apply()

    var batteryOptimisationPromptShown: Boolean
        get() = requirePrefs().getBoolean(KEY_BATTERY_PROMPT_SHOWN, false)
        set(value) = requirePrefs().edit().putBoolean(KEY_BATTERY_PROMPT_SHOWN, value).apply()

    /**
     * Whether the user wants the server running. Persisted so a START_STICKY
     * service restart (or a process restart) can resume the server without the
     * user having to press Start again.
     */
    var serverShouldRun: Boolean
        get() = requirePrefs().getBoolean(KEY_SERVER_SHOULD_RUN, false)
        set(value) = requirePrefs().edit().putBoolean(KEY_SERVER_SHOULD_RUN, value).apply()
}
