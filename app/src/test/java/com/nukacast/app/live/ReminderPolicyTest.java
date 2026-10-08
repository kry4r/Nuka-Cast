package com.nukacast.app.live;

import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * When a reminder takes the screen, and when it is forgotten.
 *
 * <p>This is the part of the feature that is invisible on the device until it is wrong: a reminder that
 * fires two hours late, or one that fires while the viewer is already watching the channel.
 */
public class ReminderPolicyTest {

    private static final long MINUTE = 60_000L;

    private static ProgrammeReminder reminder(long startMs) {
        return new ProgrammeReminder("src", "cctv1", "CCTV-1", "新闻联播", startMs);
    }

    @Test public void firesShortlyBeforeTheProgrammeStarts() {
        long now = 1_000_000_000L;
        // Half a minute before: time for the channel to come up.
        assertTrue(ReminderPolicy.isDue(reminder(now + 20_000L), now));
        // Two minutes before: not yet.
        assertFalse(ReminderPolicy.isDue(reminder(now + 2 * MINUTE), now));
        // The moment itself.
        assertTrue(ReminderPolicy.isDue(reminder(now), now));
    }

    @Test public void aReminderThatJustMissedItsMomentStillFires() {
        long now = 1_000_000_000L;
        // The television was asleep for four minutes: still worth taking the viewer there.
        assertTrue(ReminderPolicy.isDue(reminder(now - 4 * MINUTE), now));
        // An hour late is not: the programme is over or nearly so.
        assertFalse(ReminderPolicy.isDue(reminder(now - 60 * MINUTE), now));
    }

    @Test public void oneProgrammeTakesTheScreenAtATime() {
        long now = 1_000_000_000L;
        List<ProgrammeReminder> items = new ArrayList<ProgrammeReminder>();
        items.add(reminder(now + 90 * MINUTE));      // not yet
        items.add(reminder(now + 5_000L));           // due first
        items.add(reminder(now - 1_000L));           // due, a moment earlier
        assertEquals(now - 1_000L, ReminderPolicy.due(items, now).startMs);
        assertNull(ReminderPolicy.due(items, now - 10 * MINUTE));
    }

    @Test public void staleRemindersAreForgottenAndFutureOnesKept() {
        long now = 1_000_000_000L;
        List<ProgrammeReminder> items = new ArrayList<ProgrammeReminder>();
        items.add(reminder(now - 8 * 3600_000L));  // this morning
        items.add(reminder(now + 30 * MINUTE));    // later tonight
        items.add(reminder(now - 5 * MINUTE));     // just started, may still be worth it
        List<ProgrammeReminder> kept = ReminderPolicy.pruned(items, now, 4 * 3600_000L);
        // Order is preserved here; sorting is what upcoming() is for.
        assertEquals(2, kept.size());
        assertTrue(kept.contains(items.get(1)));
        assertTrue(kept.contains(items.get(2)));
    }

    @Test public void theListReadsSoonestFirst() {
        long now = 1_000_000_000L;
        List<ProgrammeReminder> items = new ArrayList<ProgrammeReminder>();
        items.add(reminder(now + 3 * 3600_000L));
        items.add(reminder(now + 10 * MINUTE));
        items.add(reminder(now + 60 * MINUTE));
        items.add(reminder(now - 3 * 3600_000L));  // long gone: not in the list
        List<ProgrammeReminder> ahead = ReminderPolicy.upcoming(items, now);
        assertEquals(3, ahead.size());
        assertEquals(now + 10 * MINUTE, ahead.get(0).startMs);
        assertEquals(now + 60 * MINUTE, ahead.get(1).startMs);
        assertEquals(now + 3 * 3600_000L, ahead.get(2).startMs);
    }

    @Test public void theSameChannelAndMomentIsTheSameReminder() {
        ProgrammeReminder first = reminder(1_000_000L);
        ProgrammeReminder second = reminder(1_000_000L);
        assertEquals(first.key(), second.key());
        assertFalse(first.key().equals(reminder(2_000_000L).key()));
        assertFalse(first.isValid() == false);
        assertFalse(new ProgrammeReminder("s", "", "named", "t", 5L).isValid());
        assertFalse(new ProgrammeReminder("s", "id", "named", "t", 0L).isValid());
    }
}
