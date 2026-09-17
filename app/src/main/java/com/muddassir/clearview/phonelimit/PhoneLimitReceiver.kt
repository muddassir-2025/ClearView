package com.muddassir.clearview.phonelimit

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * Handles everything Phone Limit needs outside the UI process:
 *
 *  - [PhoneLimitCoordinator.ACTION_EXPIRE] — the AlarmManager backstop fired
 *    at the end timestamp (the service may be dead or the device dozing):
 *    expire, lock the phone and clean up.
 *  - [Intent.ACTION_BOOT_COMPLETED] — after a reboot the OS loses alarms and
 *    kills services: re-arm the expiry alarm and resume the countdown service.
 *
 * A third case, the home-screen widget's START button, lived here until that
 * widget was removed. Nothing else sent it: the sheet starts the countdown
 * itself, so the branch and the duration parsing it needed are gone rather than
 * left as an entry point with no sender.
 *
 * Exported=false: every delivery here is either a system broadcast
 * (BOOT_COMPLETED) or the app's own explicit PendingIntent.
 */
class PhoneLimitReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            PhoneLimitCoordinator.ACTION_EXPIRE -> {
                PhoneLimitCoordinator.handleExpiry(context)
                PhoneLimitService.stop(context)
            }

            Intent.ACTION_BOOT_COMPLETED -> {
                val end = PhoneLimitCoordinator.activeEndTime(context) ?: return
                if (end <= System.currentTimeMillis()) {
                    // Expired while the phone was off — lock now.
                    PhoneLimitCoordinator.handleExpiry(context)
                } else {
                    PhoneLimitCoordinator.scheduleExpiryAlarm(context)
                    PhoneLimitService.start(context, 0L)
                }
            }
        }
    }
}
