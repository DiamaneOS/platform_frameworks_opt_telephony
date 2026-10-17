// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 The DiamaneOS Project

package com.android.internal.telephony;

import static com.android.internal.telephony.ImeiRestartNotice.Decision.NONE;
import static com.android.internal.telephony.ImeiRestartNotice.Decision.SHOW;
import static com.android.internal.telephony.ImeiRestartNotice.Decision.WAIT;
import static com.android.internal.telephony.ImeiRestartNotice.WAIT_LIMIT_MS;
import static com.android.internal.telephony.ImeiRestartNotice.decide;

import static org.junit.Assert.assertEquals;

import androidx.test.filters.SmallTest;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

@SmallTest
@RunWith(JUnit4.class)
public class ImeiRestartNoticeTest {
    @Test
    public void showsOnlyAfterAWriteAndOncePerBoot() {
        assertEquals(SHOW, decide("written", 30_000, false));
        assertEquals(SHOW, decide("written", WAIT_LIMIT_MS * 10, false));
        assertEquals(NONE, decide("written", 30_000, true));
        for (String status : new String[] {"unchanged", "failed", "reversed", "corrected"}) {
            assertEquals(status, NONE, decide(status, 30_000, false));
        }
    }

    @Test
    public void waitsWhileImeiprovdRunsWithinTheLimit() {
        assertEquals(WAIT, decide("running", 30_000, false));
        assertEquals(WAIT, decide("", 30_000, false));
        assertEquals(NONE, decide("running", WAIT_LIMIT_MS, false));
        assertEquals(NONE, decide("", WAIT_LIMIT_MS, false));
        assertEquals(NONE, decide("running", 30_000, true));
    }
}
