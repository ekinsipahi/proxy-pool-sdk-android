package com.proxypool.sdk

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * Brings sharing back after a reboot — but only if the user already said yes.
 *
 * This matters most for the devices that matter most. A signage player or TV box
 * reboots on a power cut at 4 a.m.; without this it sits there, consented and
 * idle, until somebody opens the app. Those are the network's best nodes (stable
 * residential IP, always on, nothing competing for the uplink), so losing them
 * to a power blip is losing the inventory the pool is actually sold on.
 *
 * A partner app must still call [ProxyPoolSdk.init] with its key in
 * `Application.onCreate` — the service stops itself if no config is present,
 * which is the correct behaviour for a host app that has since removed the SDK.
 */
class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return
        if (action != Intent.ACTION_BOOT_COMPLETED &&
            action != "android.intent.action.QUICKBOOT_POWERON"
        ) {
            return
        }
        if (!ProxyPoolSdk.hasConsent(context)) return
        ProxyPoolSdk.start(context)
    }
}
