// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 The DiamaneOS Project

package com.android.internal.telephony;

import android.annotation.NonNull;
import android.app.AppGlobals;
import android.app.Notification;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.PowerManager;
import android.os.RemoteException;
import android.os.SystemClock;
import android.os.SystemProperties;

import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.telephony.util.NotificationChannelController;
import com.android.telephony.Rlog;

/**
 * DiamaneOS: offers a restart on the first boot after a clean install, where Wi-Fi calling failed
 * until a restart. That boot has fresh userdata and imeiprovd found the modem without IMEIs
 * ("reversed"). Updates keep userdata, and a factory reset finds the IMEIs in place. Shown once
 * and gone after the restart.
 */
public final class ImeiRestartNotice {
    private static final String TAG = "ImeiRestartNotice";
    private static final String STATUS_PROPERTY = "vendor.diamaneos.imei.status";
    private static final String ACTION_RESTART =
            "com.android.internal.telephony.action.IMEI_RESTART";
    private static final String PREFS = "imei_restart_notice";
    private static final String KEY_SHOWN = "shown";
    private static final String NOTIFICATION_TAG = "ImeiRestart";
    private static final int NOTIFICATION_ID = 1;
    private static final long RECHECK_MS = 5_000;
    /** imeiprovd gives up on the modem well within this time after boot. */
    @VisibleForTesting
    static final long WAIT_LIMIT_MS = 180_000;

    @VisibleForTesting
    enum Decision { WAIT, SHOW, NONE }

    private final Context mContext;
    private final Handler mHandler;
    private final SharedPreferences mPrefs;
    private final boolean mFirstBoot;
    private boolean mReceiverRegistered;

    /** Starts the check. Call once in the phone process, after the notification channels. */
    public static void start(@NonNull Context context) {
        new ImeiRestartNotice(context).check();
    }

    @VisibleForTesting
    static Decision decide(@NonNull String status, boolean firstBoot, long sinceBootMs,
            boolean shown) {
        if (!firstBoot || shown) return Decision.NONE;
        if ("reversed".equals(status)) return Decision.SHOW;
        // "running" until imeiprovd is done, empty until it starts.
        boolean pending = status.isEmpty() || "running".equals(status);
        return pending && sinceBootMs < WAIT_LIMIT_MS ? Decision.WAIT : Decision.NONE;
    }

    private ImeiRestartNotice(Context context) {
        mContext = context;
        mHandler = new Handler(context.getMainLooper());
        // Device-protected: the phone process starts before the user unlocks.
        mPrefs = context.createDeviceProtectedStorageContext()
                .getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        mFirstBoot = isFirstBoot();
    }

    /** Whether userdata is fresh: the package manager found no package list at this boot. */
    private static boolean isFirstBoot() {
        try {
            return AppGlobals.getPackageManager().isFirstBoot();
        } catch (RemoteException e) {
            Rlog.e(TAG, "isFirstBoot failed", e);
            return false;
        }
    }

    private void check() {
        boolean shown = mPrefs.getBoolean(KEY_SHOWN, false);
        if (shown && mFirstBoot) {
            // The phone process restarted. Keep the Restart action of a notice still shown.
            registerRestart();
        }
        Decision decision = decide(SystemProperties.get(STATUS_PROPERTY), mFirstBoot,
                SystemClock.elapsedRealtime(), shown);
        if (decision == Decision.WAIT) {
            mHandler.postDelayed(this::check, RECHECK_MS);
        } else if (decision == Decision.SHOW) {
            show();
        }
    }

    private void show() {
        mPrefs.edit().putBoolean(KEY_SHOWN, true).apply();
        registerRestart();
        Intent restart = new Intent(ACTION_RESTART).setPackage(mContext.getPackageName());
        PendingIntent restartIntent = PendingIntent.getBroadcast(
                mContext, 0, restart, PendingIntent.FLAG_IMMUTABLE);
        CharSequence text = mContext.getText(com.android.internal.R.string.sim_added_message);
        Notification notification = new Notification.Builder(
                mContext, NotificationChannelController.CHANNEL_ID_SIM_HIGH_PRIORITY)
                .setSmallIcon(android.R.drawable.stat_sys_warning)
                .setColor(mContext.getColor(
                        com.android.internal.R.color.system_notification_accent_color))
                .setContentTitle(mContext.getText(
                        com.android.internal.R.string.notification_channel_wfc))
                .setContentText(text)
                .setStyle(new Notification.BigTextStyle().bigText(text))
                .setVisibility(Notification.VISIBILITY_PUBLIC)
                .setOnlyAlertOnce(true)
                .addAction(new Notification.Action.Builder(null,
                        mContext.getText(com.android.internal.R.string.sim_restart_button),
                        restartIntent).build())
                .build();
        mContext.getSystemService(NotificationManager.class)
                .notify(NOTIFICATION_TAG, NOTIFICATION_ID, notification);
        Rlog.i(TAG, "restart notice shown");
    }

    private void registerRestart() {
        if (mReceiverRegistered) return;
        mReceiverRegistered = true;
        mContext.registerReceiver(new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                Rlog.i(TAG, "restart chosen");
                context.getSystemService(PowerManager.class).reboot(null);
            }
        }, new IntentFilter(ACTION_RESTART), Context.RECEIVER_NOT_EXPORTED);
    }
}
