// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 The DiamaneOS Project

package com.android.internal.telephony;

import android.app.AlarmManager;
import android.os.SystemClock;
import android.os.SystemProperties;
import android.telephony.ServiceState;
import android.telephony.TelephonyManager;

import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.telephony.domainselection.DomainSelectionResolver;
import com.android.internal.telephony.emergency.EmergencyStateTracker;

import java.io.PrintWriter;
import java.util.function.BooleanSupplier;

/**
 * DiamaneOS: network-state poll coalescing for ServiceStateTracker.
 * Some modems report networkStateChanged many times a minute while idle without any
 * registration change, and each report costs a four-request poll that keeps the AP awake.
 * With ro.telephony.network_state_poll_window_ms = W > 0, a report while the device is idle
 * and in service, with the SIM ready and no call or emergency mode on any SIM, polls at once
 * and opens a window of W. Reports in the window wait for the first report after it, which
 * polls, or for a backstop alarm 2W after the first of them, so every report is polled
 * within 2W. The alarm is late on purpose: the kernel will not suspend within 2 s of a
 * wakeup alarm and holds the AP awake instead. Anything else polls at once, as upstream.
 */
public class NetworkStatePollCoalescer {
    private static final String PROP_NETWORK_STATE_POLL_WINDOW_MS =
            "ro.telephony.network_state_poll_window_ms";
    private static final int MAX_NETWORK_STATE_POLL_WINDOW_MS = 15000;
    @VisibleForTesting
    public static final String NETWORK_STATE_POLL_ALARM_TAG = "SST.networkStatePoll";
    private int mNetworkStatePollWindowMs;
    private final AlarmManager mAlarmManager;
    private final ServiceStateTracker mSst;
    private final GsmCdmaPhone mPhone;
    private final CommandsInterface mCi;
    private final Runnable mPoll;
    private final BooleanSupplier mSimReadyAndRadioWanted;
    /** elapsedRealtime at which the current window ends, or 0 when no window is open. */
    @VisibleForTesting
    public long mNetworkStatePollWindowEndMs;
    private boolean mNetworkStatePollPending;
    private int mNetworkStateReportsCoalesced;
    private int mNetworkStateBackstopPolls;
    @VisibleForTesting
    public final AlarmManager.OnAlarmListener mNetworkStatePollAlarm =
            this::onNetworkStatePollBackstop;

    /**
     * @param poll polls the modem now (pollStateInternal(true))
     * @param simReadyAndRadioWanted whether the SIM is ready and the radio is meant to be on
     */
    NetworkStatePollCoalescer(ServiceStateTracker sst, GsmCdmaPhone phone, CommandsInterface ci,
            Runnable poll, BooleanSupplier simReadyAndRadioWanted) {
        mSst = sst;
        mPhone = phone;
        mCi = ci;
        mPoll = poll;
        mSimReadyAndRadioWanted = simReadyAndRadioWanted;
        setNetworkStatePollWindowMs(SystemProperties.getInt(PROP_NETWORK_STATE_POLL_WINDOW_MS, 0));
        mAlarmManager = phone.getContext().getSystemService(AlarmManager.class);
    }

    void onNetworkStateChanged() {
        if (!canCoalesceNetworkStatePoll()) {
            mNetworkStatePollWindowEndMs = 0; // the next idle report polls at once
            mPoll.run();
            return;
        }
        long now = SystemClock.elapsedRealtime();
        if (now < mNetworkStatePollWindowEndMs) {
            mNetworkStateReportsCoalesced++;
            if (!mNetworkStatePollPending) {
                mNetworkStatePollPending = true;
                // Wakeup alarm: suspend cannot stretch the delay and Doze does not defer it.
                mAlarmManager.setExact(AlarmManager.ELAPSED_REALTIME_WAKEUP,
                        now + 2L * mNetworkStatePollWindowMs,
                        NETWORK_STATE_POLL_ALARM_TAG + mPhone.getPhoneId(),
                        mNetworkStatePollAlarm, mSst);
            }
            return;
        }
        mPoll.run(); // also answers the reports that waited in the last window
        mNetworkStatePollWindowEndMs = now + mNetworkStatePollWindowMs;
    }

    private void onNetworkStatePollBackstop() {
        if (!mNetworkStatePollPending) return;
        mNetworkStatePollPending = false; // this alarm has fired
        mNetworkStateBackstopPolls++;
        mPoll.run();
        mNetworkStatePollWindowEndMs = canCoalesceNetworkStatePoll()
                ? SystemClock.elapsedRealtime() + mNetworkStatePollWindowMs : 0;
    }

    /** Polls a waiting report now and closes the window. Called when the device leaves idle. */
    public void flushNetworkStatePoll() {
        if (mNetworkStatePollWindowMs <= 0) return;
        mSst.post(() -> {
            mNetworkStatePollWindowEndMs = 0;
            if (mNetworkStatePollPending) mPoll.run();
        });
    }

    /** Any poll answers a waiting report, so its backstop alarm is no longer needed. */
    void clearPendingNetworkStatePoll() {
        if (!mNetworkStatePollPending) return;
        mNetworkStatePollPending = false;
        mAlarmManager.cancel(mNetworkStatePollAlarm);
    }

    private boolean canCoalesceNetworkStatePoll() {
        if (mNetworkStatePollWindowMs <= 0) return false;
        // SIM ready, radio on and meant to stay on, and the last poll found full terrestrial
        // service.
        if (!mSimReadyAndRadioWanted.getAsBoolean()
                || mCi.getRadioState() != TelephonyManager.RADIO_POWER_ON
                || mSst.mSS.getState() != ServiceState.STATE_IN_SERVICE
                || mSst.mSS.getDataRegistrationState() != ServiceState.STATE_IN_SERVICE
                || mSst.mSS.isEmergencyOnly() || mSst.mSS.isUsingNonTerrestrialNetwork()) {
            return false;
        }
        // No call in any state (CS or IMS), ECBM or emergency SMS mode on any SIM.
        for (Phone phone : PhoneFactory.getPhones()) {
            if (phone.getState() != PhoneConstants.State.IDLE || phone.isInEcm()
                    || phone.isInEmergencySmsMode()) {
                return false;
            }
        }
        if (DomainSelectionResolver.getInstance().isDomainSelectionSupported()) {
            EmergencyStateTracker est = EmergencyStateTracker.getInstance();
            if (est.isInEmergencyMode() || est.isInEmergencyCall() || est.isInScbm()) {
                return false;
            }
        }
        // Screen off, not charging, no tethering or Android Auto (DeviceStateMonitor).
        return mPhone.isDeviceIdle();
    }

    @VisibleForTesting
    public void setNetworkStatePollWindowMs(int windowMs) {
        mNetworkStatePollWindowMs =
                Math.max(0, Math.min(windowMs, MAX_NETWORK_STATE_POLL_WINDOW_MS));
    }

    void dump(PrintWriter pw) {
        pw.println(" mNetworkStatePollWindowMs=" + mNetworkStatePollWindowMs
                + " pending=" + mNetworkStatePollPending
                + " coalesced=" + mNetworkStateReportsCoalesced
                + " backstopPolls=" + mNetworkStateBackstopPolls);
    }
}
