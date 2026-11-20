package com.safewe.health

import kotlin.math.max

/** Things that stop SafeWe from working properly on the child's phone. Child and parent each get their own wording. */
enum class IssueCode(val childMessage: String, val parentMessage: String) {
    LOCATION_PERMISSION("Location permission is off, so sharing cannot work.", "Location permission is off on the child's phone."),
    BACKGROUND_LOCATION("“Allow all the time” location is off. Safe-zone alerts may be missed.", "“Allow all the time” location is off. Safe-zone alerts may be missed."),
    NOTIFICATIONS("Notifications are off. SafeWe needs them to show that sharing is on.", "Notifications are off, so screenshots and photos are refused."),
    LOCATION_SERVICES("Location is switched off on this phone.", "Location is switched off on the child's phone."),
    USAGE_ACCESS("Usage access is off, so app usage cannot be shared.", "Usage access is off, so app usage is unavailable."),
    POWER_SAVER("Battery saver is on. Updates may be delayed.", "Battery saver is on. Updates may be delayed."),
    BATTERY_LOW("The battery is very low.", "The battery is very low."),
    RESTRICTED_BUCKET("Android is limiting SafeWe in the background.", "Android is limiting SafeWe in the background, so updates may be late."),
    OFFLINE("There is no internet connection.", "The child's phone has no internet connection.");

    companion object { fun parse(s: String): IssueCode? = entries.firstOrNull { it.name == s } }
}

data class HealthInput(
    val fineLocation: Boolean, val backgroundLocation: Boolean, val notifications: Boolean, val locationServices: Boolean,
    val usageAccess: Boolean, val usageWanted: Boolean, val powerSave: Boolean, val batteryPct: Int, val charging: Boolean,
    val standbyBucket: Int, val online: Boolean, val sharing: Boolean
)

object DeviceHealth {
    const val BUCKET_RARE = 40 // UsageStatsManager.STANDBY_BUCKET_RARE; RESTRICTED is 45

    fun evaluate(i: HealthInput): List<IssueCode> = buildList {
        if (i.sharing) {
            if (!i.fineLocation) add(IssueCode.LOCATION_PERMISSION)
            else if (!i.backgroundLocation) add(IssueCode.BACKGROUND_LOCATION)
            if (!i.locationServices) add(IssueCode.LOCATION_SERVICES)
        }
        if ((i.sharing || i.usageWanted) && !i.notifications) add(IssueCode.NOTIFICATIONS)
        if (i.usageWanted && !i.usageAccess) add(IssueCode.USAGE_ACCESS)
        if (i.powerSave) add(IssueCode.POWER_SAVER)
        if (i.batteryPct in 0..10 && !i.charging) add(IssueCode.BATTERY_LOW)
        if (i.standbyBucket >= BUCKET_RARE) add(IssueCode.RESTRICTED_BUCKET)
        if (!i.online) add(IssueCode.OFFLINE)
    }
}

enum class Presence { UNKNOWN, ONLINE, PAUSED, OFFLINE }

object PresenceRules {
    const val FRESH_MS = 35 * 60_000L // two missed 15-minute heartbeats plus slack

    fun of(sharing: Boolean, updatedAt: Long, now: Long): Presence = when {
        updatedAt <= 0 -> Presence.UNKNOWN
        !sharing -> Presence.PAUSED
        now - updatedAt <= FRESH_MS -> Presence.ONLINE
        else -> Presence.OFFLINE
    }

    fun ago(updatedAt: Long, now: Long): String {
        if (updatedAt <= 0) return "never"
        val m = max(0, (now - updatedAt) / 60_000)
        return when { m < 1 -> "just now"; m < 60 -> "$m min ago"; m < 24 * 60 -> "${m / 60} h ago"; else -> "${m / (24 * 60)} d ago" }
    }
}
