package com.seldia.music

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.seldia.music.util.ThemePrefs

/**
 * Recibe el tema desde FordLauncher para sincronizar claro/oscuro.
 */
class LauncherThemeReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent?) {
        if (intent?.action != ACTION_APPLY_THEME) return
        val theme = intent.getStringExtra(EXTRA_THEME) ?: return
        val light = theme.equals("light", ignoreCase = true)
        val changed = ThemePrefs.applyExternalTheme(context, light)
        if (changed) {
            // Si la app está en primer plano, las actividades activas deben recrearse.
            val local = Intent(ACTION_THEME_APPLIED).setPackage(context.packageName)
            try { context.sendBroadcast(local) } catch (_: Throwable) {}
        }
    }

    companion object {
        const val ACTION_APPLY_THEME = "com.fordlauncher.launcher.action.APPLY_THEME"
        const val ACTION_THEME_APPLIED = "com.seldia.music.action.THEME_APPLIED"
        const val EXTRA_THEME = "theme"
    }
}
