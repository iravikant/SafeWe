package com.safewe.service

import android.content.Context

/** Remembers that the CHILD turned sharing on, so it can be resumed after a reboot. Cleared by any stop or unpair. */
object SharingPrefs {
    private fun p(ctx: Context) = ctx.getSharedPreferences("safewe_state", Context.MODE_PRIVATE)
    fun isEnabled(ctx: Context) = p(ctx).getBoolean("sharing", false)
    fun set(ctx: Context, enabled: Boolean) { p(ctx).edit().putBoolean("sharing", enabled).apply() }
}
