package com.safewe.data

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

enum class AppRole { PARENT, CHILD }

/** Remembers which mode this phone is in, so SafeWe opens straight into it. */
@Singleton
class RoleStore @Inject constructor(@ApplicationContext ctx: Context) {
    private val prefs = ctx.getSharedPreferences("safewe_role", Context.MODE_PRIVATE)
    fun get(): AppRole? = runCatching { AppRole.valueOf(prefs.getString("role", "")!!) }.getOrNull()
    fun set(r: AppRole) { prefs.edit().putString("role", r.name).apply() }
    fun clear() { prefs.edit().remove("role").apply() }
}

/** Parent-chosen nicknames for paired devices. Stored only on the parent's phone. */
@Singleton
class DeviceNames @Inject constructor(@ApplicationContext ctx: Context) {
    private val prefs = ctx.getSharedPreferences("safewe_names", Context.MODE_PRIVATE)
    @Suppress("UNCHECKED_CAST") fun all(): Map<String, String> = prefs.all.filterValues { it is String } as Map<String, String>
    fun set(uid: String, name: String) { prefs.edit().putString(uid, name.trim().take(30)).apply() }
}
