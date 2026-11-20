package com.safewe.service

import android.annotation.SuppressLint
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.safewe.MainActivity
import com.safewe.SafeWeApp

/** Tells the child, visibly, when sharing stopped or needs them to act. SafeWe never works around Android's limits. */
object ResumeNotifier {
    private const val ID = 4001

    @SuppressLint("MissingPermission")
    fun show(ctx: Context, title: String, text: String) {
        val nm = NotificationManagerCompat.from(ctx)
        if (!nm.areNotificationsEnabled()) return
        val open = PendingIntent.getActivity(
            ctx, 4, Intent(ctx, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val n = NotificationCompat.Builder(ctx, SafeWeApp.REQUEST_CHANNEL)
            .setSmallIcon(android.R.drawable.ic_dialog_info).setContentTitle(title).setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text)).setAutoCancel(true).setContentIntent(open).build()
        runCatching { nm.notify(ID, n) }
    }
}
