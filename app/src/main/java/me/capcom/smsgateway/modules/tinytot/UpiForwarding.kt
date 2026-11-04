package me.capcom.smsgateway.modules.tinytot

import android.content.Context
import me.capcom.smsgateway.modules.receiver.MessagesReceiver

/**
 * PNB SMS forwarding runs on its own, whether or not the upstream local/cloud
 * gateway has been started from the Home tab: upstream only enables the SMS
 * receiver when that gateway starts, which left forwarding silently off.
 */
object UpiForwarding {
    fun isActive(settings: TinyTotSettings): Boolean =
        settings.forwardEnabled && settings.isConfigured

    /**
     * Enables the manifest SMS receiver while forwarding is active. The enabled
     * state persists, so SMS wake the app even after it is killed or rebooted.
     */
    fun ensure(context: Context, settings: TinyTotSettings) {
        if (isActive(settings)) MessagesReceiver.enableComponent(context)
    }
}
