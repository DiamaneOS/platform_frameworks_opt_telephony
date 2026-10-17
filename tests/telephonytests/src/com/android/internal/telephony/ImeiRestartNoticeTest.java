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
    private static final String[] STATUSES =
            {"", "running", "reversed", "corrected", "written", "unchanged", "failed"};

    @Test
    public void showsOnlyForAReversedWriteOnFreshUserdataOnce() {
        assertEquals(SHOW, decide("reversed", true, 30_000, false));
        assertEquals(SHOW, decide("reversed", true, WAIT_LIMIT_MS * 10, false));
        assertEquals(NONE, decide("reversed", true, 30_000, true));
        for (String status : new String[] {"corrected", "written", "unchanged", "failed"}) {
            assertEquals(status, NONE, decide(status, true, 30_000, false));
        }
    }

    @Test
    public void nothingWithoutFreshUserdata() {
        for (String status : STATUSES) {
            assertEquals(status, NONE, decide(status, false, 30_000, false));
        }
    }

    @Test
    public void waitsWhileImeiprovdRunsWithinTheLimit() {
        assertEquals(WAIT, decide("running", true, 30_000, false));
        assertEquals(WAIT, decide("", true, 30_000, false));
        assertEquals(NONE, decide("running", true, WAIT_LIMIT_MS, false));
        assertEquals(NONE, decide("", true, WAIT_LIMIT_MS, false));
        assertEquals(NONE, decide("running", true, 30_000, true));
    }
}
