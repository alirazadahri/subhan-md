package com.arcontroller

import android.content.Context
import android.content.Intent
import android.graphics.drawable.Drawable

data class InstalledApp(val label: String, val packageName: String, val icon: Drawable)

/** Lists launchable apps. Visibility on Android 11+ comes from the <queries> block in the manifest. */
object AppRepository {
    @Volatile
    private var cache: List<InstalledApp>? = null

    /** Slow (loads every icon) — call from a background thread. */
    fun load(ctx: Context, force: Boolean = false): List<InstalledApp> {
        if (!force) cache?.let { return it }
        val pm = ctx.packageManager
        val launcherIntent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        @Suppress("DEPRECATION")
        val apps = pm.queryIntentActivities(launcherIntent, 0)
            .filter { it.activityInfo.packageName != ctx.packageName }
            .distinctBy { it.activityInfo.packageName }
            .map {
                InstalledApp(
                    label = it.loadLabel(pm).toString(),
                    packageName = it.activityInfo.packageName,
                    icon = it.loadIcon(pm),
                )
            }
            .sortedBy { it.label.lowercase() }
        cache = apps
        return apps
    }

    fun labels(ctx: Context): List<String> = load(ctx).map { it.label }

    /** Fuzzy name match: "insta" -> Instagram, "whats app" -> WhatsApp. */
    fun findByName(ctx: Context, query: String): InstalledApp? {
        val q = normalize(query)
        if (q.isEmpty()) return null
        val apps = load(ctx)
        return apps.firstOrNull { normalize(it.label) == q }
            ?: apps.firstOrNull { normalize(it.label).startsWith(q) }
            ?: apps.firstOrNull { normalize(it.label).contains(q) }
            ?: apps.firstOrNull { q.contains(normalize(it.label)) && normalize(it.label).length >= 3 }
            ?: apps.firstOrNull { it.packageName.lowercase().contains(q) }
    }

    private fun normalize(s: String) = s.lowercase().replace(Regex("[^a-z0-9\\u0600-\\u06FF\\u0900-\\u097F]"), "")
}
