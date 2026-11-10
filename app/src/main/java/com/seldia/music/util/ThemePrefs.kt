package com.seldia.music.util

import android.content.Context
import androidx.appcompat.app.AppCompatDelegate

/** Preferencias de tema (oscuro/día) y color de acento (paleta dinámica). */
object ThemePrefs {

    private const val PREFS = "seldia_theme_prefs"
    private const val KEY_NIGHT = "night_mode" // "dark" | "light"
    private const val KEY_ACCENT = "accent_key" // red/orange/...

    const val NIGHT_DARK = "dark"
    const val NIGHT_LIGHT = "light"
    const val NIGHT_GLASS = "glass"

    val isGlass: Boolean
        get() = nightMode == NIGHT_GLASS

    const val ACCENT_RED = "red"
    const val ACCENT_ORANGE = "orange"
    const val ACCENT_AMBER = "amber"
    const val ACCENT_GREEN = "green"
    const val ACCENT_TEAL = "teal"
    const val ACCENT_BLUE = "blue"
    const val ACCENT_PURPLE = "purple"
    const val ACCENT_PINK = "pink"

    private var prefs: android.content.SharedPreferences? = null

    fun init(context: Context) {
        if (prefs == null) {
            prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        }
        applyNightMode()
    }

    private fun getPrefs(context: Context): android.content.SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    var nightMode: String
        get() = prefs?.getString(KEY_NIGHT, NIGHT_DARK) ?: NIGHT_DARK
        set(value) {
            prefs?.edit()?.putString(KEY_NIGHT, value)?.apply()
            applyNightMode()
        }

    var accentKey: String
        get() = prefs?.getString(KEY_ACCENT, ACCENT_RED) ?: ACCENT_RED
        set(value) {
            prefs?.edit()?.putString(KEY_ACCENT, value)?.apply()
        }

    fun accentThemeRes(key: String): Int = when (key) {
        ACCENT_ORANGE -> com.seldia.music.R.style.Theme_SeldiaMusic_AccentOrange
        ACCENT_AMBER -> com.seldia.music.R.style.Theme_SeldiaMusic_AccentAmber
        ACCENT_GREEN -> com.seldia.music.R.style.Theme_SeldiaMusic_AccentGreen
        ACCENT_TEAL -> com.seldia.music.R.style.Theme_SeldiaMusic_AccentTeal
        ACCENT_BLUE -> com.seldia.music.R.style.Theme_SeldiaMusic_AccentBlue
        ACCENT_PURPLE -> com.seldia.music.R.style.Theme_SeldiaMusic_AccentPurple
        ACCENT_PINK -> com.seldia.music.R.style.Theme_SeldiaMusic_AccentPink
        else -> com.seldia.music.R.style.Theme_SeldiaMusic_AccentRed
    }

    fun accentThemeResForContext(context: Context): Int =
        accentThemeRes(getPrefs(context).getString(KEY_ACCENT, ACCENT_RED) ?: ACCENT_RED)

    fun glassThemeRes(key: String): Int = when (key) {
        ACCENT_ORANGE -> com.seldia.music.R.style.Theme_SeldiaMusic_Glass_AccentOrange
        ACCENT_AMBER -> com.seldia.music.R.style.Theme_SeldiaMusic_Glass_AccentAmber
        ACCENT_GREEN -> com.seldia.music.R.style.Theme_SeldiaMusic_Glass_AccentGreen
        ACCENT_TEAL -> com.seldia.music.R.style.Theme_SeldiaMusic_Glass_AccentTeal
        ACCENT_BLUE -> com.seldia.music.R.style.Theme_SeldiaMusic_Glass_AccentBlue
        ACCENT_PURPLE -> com.seldia.music.R.style.Theme_SeldiaMusic_Glass_AccentPurple
        ACCENT_PINK -> com.seldia.music.R.style.Theme_SeldiaMusic_Glass_AccentPink
        else -> com.seldia.music.R.style.Theme_SeldiaMusic_Glass_AccentRed
    }

    /** Tema efectivo: Glass (con acento) si está activo; si no, acento clásico. */
    fun themeResForContext(context: Context): Int {
        val mode = getPrefs(context).getString(KEY_NIGHT, NIGHT_DARK) ?: NIGHT_DARK
        val accent = getPrefs(context).getString(KEY_ACCENT, ACCENT_RED) ?: ACCENT_RED
        return if (mode == NIGHT_GLASS) glassThemeRes(accent) else accentThemeRes(accent)
    }

    /** Aplica tema enviado desde FordLauncher. Devuelve true si cambió. */
    fun applyExternalTheme(context: Context, light: Boolean): Boolean {
        val mode = if (light) NIGHT_LIGHT else NIGHT_DARK
        val p = getPrefs(context)
        val changed = (p.getString(KEY_NIGHT, NIGHT_DARK) ?: NIGHT_DARK) != mode
        if (changed) {
            p.edit().putString(KEY_NIGHT, mode).apply()
        }
        prefs = p
        applyNightMode()
        return changed
    }

    private fun applyNightMode() {
        val mode = nightMode
        val delegateMode = if (mode == NIGHT_LIGHT) {
            AppCompatDelegate.MODE_NIGHT_NO
        } else {
            AppCompatDelegate.MODE_NIGHT_YES
        }
        if (AppCompatDelegate.getDefaultNightMode() != delegateMode) {
            AppCompatDelegate.setDefaultNightMode(delegateMode)
        }
    }
}
