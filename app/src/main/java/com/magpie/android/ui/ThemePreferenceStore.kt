package com.magpie.android.ui

import android.content.Context

internal object ThemePreferenceStore {
    private const val PREFS = "magpie_go_ui"
    private const val KEY = "theme_preference"

    fun get(context: Context): ThemePreference =
        runCatching {
            ThemePreference.valueOf(context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, null) ?: "")
        }.getOrDefault(ThemePreference.FollowSystem)

    fun set(context: Context, preference: ThemePreference) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(KEY, preference.name)
            .apply()
    }
}
