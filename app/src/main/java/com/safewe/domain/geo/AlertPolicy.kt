package com.safewe.domain.geo

/** Decides which transitions are worth a cloud alert (keeps Firestore writes low on Spark). */
object AlertPolicy {
    fun shouldAlert(zone: String, transition: String): Boolean = when (zone) {
        "SAFE" -> transition == "ENTER" || transition == "EXIT"          // arrived / left a safe place
        "RESTRICTED" -> transition == "ENTER" || transition == "DWELL"    // entered / stayed in a restricted place
        else -> false
    }
}
