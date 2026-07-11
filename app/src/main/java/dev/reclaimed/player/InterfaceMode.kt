package dev.reclaimed.player

import android.content.Context

enum class InterfaceMode {
    Classic,
    Touch,
}

class InterfaceModeStore(context: Context) {
    private val preferences = context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)

    fun load(): InterfaceMode = runCatching {
        InterfaceMode.valueOf(
            preferences.getString(KEY_INTERFACE_MODE, InterfaceMode.Classic.name).orEmpty(),
        )
    }.getOrDefault(InterfaceMode.Classic)

    fun save(mode: InterfaceMode) {
        preferences.edit().putString(KEY_INTERFACE_MODE, mode.name).apply()
    }

    private companion object {
        const val PREFERENCES_NAME = "interface_preferences"
        const val KEY_INTERFACE_MODE = "interface_mode"
    }
}
