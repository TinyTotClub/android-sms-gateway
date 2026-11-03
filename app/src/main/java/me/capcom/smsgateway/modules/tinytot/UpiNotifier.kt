package me.capcom.smsgateway.modules.tinytot

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import me.capcom.smsgateway.MainActivity
import me.capcom.smsgateway.R
import java.math.BigDecimal

/** Local alerts for the counter staff about forwarded bank credits. */
class UpiNotifier(
    private val context: Context,
) {
    private val manager =
        context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

    fun paymentVerified(key: String, amount: BigDecimal?) {
        val text = amount?.let {
            context.getString(R.string.tinytot_notif_payment_verified, UpiAmounts.format(it))
        } ?: context.getString(R.string.tinytot_notif_payment_verified_no_amount)
        notify(key, text)
    }

    fun creditNeedsReview(key: String, amount: BigDecimal?) {
        val text = amount?.let {
            context.getString(R.string.tinytot_notif_credit_review_amount, UpiAmounts.format(it))
        } ?: context.getString(R.string.tinytot_notif_credit_review)
        notify(key, text)
    }

    private fun notify(key: String, text: String) {
        ensureChannel()

        val intent = MainActivity.starter(context, MainActivity.TAB_INDEX_PAYMENTS).apply {
            addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        }
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_payments)
            .setContentTitle(context.getString(R.string.app_name))
            .setContentText(text)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .setContentIntent(
                PendingIntent.getActivity(
                    context,
                    REQUEST_CODE,
                    intent,
                    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
                )
            )
            .build()

        try {
            manager.notify(NOTIFICATION_ID_BASE + (key.hashCode() and 0xFFFF), notification)
        } catch (e: SecurityException) {
            // POST_NOTIFICATIONS not granted; the Payments screen still shows the state.
        }
    }

    private fun ensureChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        if (manager.getNotificationChannel(CHANNEL_ID) != null) return

        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                context.getString(R.string.tinytot_notif_channel),
                NotificationManager.IMPORTANCE_HIGH,
            )
        )
    }

    companion object {
        private const val CHANNEL_ID = "tinytot-upi"
        private const val REQUEST_CODE = 0x7177
        private const val NOTIFICATION_ID_BASE = 0x7100_0000
    }
}
