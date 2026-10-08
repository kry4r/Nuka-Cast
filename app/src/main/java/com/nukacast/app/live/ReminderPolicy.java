package com.nukacast.app.live;

import java.util.ArrayList;
import java.util.List;

/**
 * Which reminder is due, and which ones have gone stale.
 *
 * <p>Kept apart from the storage and from the television so it can be checked without a device: "did the
 * reminder fire at the right moment" is exactly the part that goes wrong and is invisible from the screen.
 */
public final class ReminderPolicy {

    /**
     * How long before the start the television switches.
     *
     * <p>Half a minute: long enough that the channel is on screen when the programme begins, short enough
     * that the viewer is not left wondering why the channel changed.
     */
    public static final long LEAD_MS = 30_000L;

    /**
     * How long after the start it is still worth switching.
     *
     * <p>Televisions sleep and the app may have been in the background; a reminder that missed its moment by
     * a few minutes still wants to take the viewer there, but one from this morning does not.
     */
    public static final long GRACE_MS = 5 * 60_000L;

    private ReminderPolicy() {}

    /** Whether this reminder's moment has arrived (and has not passed too far). */
    public static boolean isDue(ProgrammeReminder reminder, long nowMs) {
        if (reminder == null || !reminder.isValid()) return false;
        return nowMs >= reminder.startMs - LEAD_MS && nowMs <= reminder.startMs + GRACE_MS;
    }

    /**
     * The reminder to act on now: the earliest one whose moment has come.
     *
     * <p>One at a time: two programmes that start together cannot both take the screen, and the viewer's
     * attention is a single thing.
     */
    public static ProgrammeReminder due(List<ProgrammeReminder> reminders, long nowMs) {
        ProgrammeReminder earliest = null;
        if (reminders == null) return null;
        for (ProgrammeReminder reminder : reminders) {
            if (!isDue(reminder, nowMs)) continue;
            if (earliest == null || reminder.startMs < earliest.startMs) earliest = reminder;
        }
        return earliest;
    }

    /**
     * Drops the reminders that are too old to be useful and the ones that are over.
     *
     * @param programmeLengthMs how long to keep a fired reminder before forgetting it
     */
    public static List<ProgrammeReminder> pruned(List<ProgrammeReminder> reminders, long nowMs,
                                                 long programmeLengthMs) {
        List<ProgrammeReminder> kept = new ArrayList<ProgrammeReminder>();
        if (reminders == null) return kept;
        for (ProgrammeReminder reminder : reminders) {
            if (reminder == null || !reminder.isValid()) continue;
            if (nowMs > reminder.startMs + programmeLengthMs) continue;
            kept.add(reminder);
        }
        return kept;
    }

    /** The reminders still ahead, soonest first, which is how the list reads. */
    public static List<ProgrammeReminder> upcoming(List<ProgrammeReminder> reminders, long nowMs) {
        List<ProgrammeReminder> ahead = new ArrayList<ProgrammeReminder>();
        if (reminders == null) return ahead;
        for (ProgrammeReminder reminder : reminders) {
            if (reminder != null && reminder.isValid() && reminder.startMs + GRACE_MS > nowMs) {
                ahead.add(reminder);
            }
        }
        for (int i = 1; i < ahead.size(); i++) {
            ProgrammeReminder value = ahead.get(i);
            int j = i - 1;
            while (j >= 0 && ahead.get(j).startMs > value.startMs) {
                ahead.set(j + 1, ahead.get(j));
                j--;
            }
            ahead.set(j + 1, value);
        }
        return ahead;
    }
}
