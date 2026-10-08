package com.nukacast.app.live;

/** One programme the viewer asked to be reminded about. */
public final class ProgrammeReminder {

    /** Which playlist the channel belongs to. */
    public String sourceId = "";
    public String channelId = "";
    public String channelName = "";
    public String title = "";
    /** When the programme starts, in milliseconds since the epoch. */
    public long startMs;

    public ProgrammeReminder() {}

    public ProgrammeReminder(String sourceId, String channelId, String channelName, String title,
                             long startMs) {
        this.sourceId = sourceId;
        this.channelId = channelId;
        this.channelName = channelName;
        this.title = title;
        this.startMs = startMs;
    }

    /** Stable identity: the same channel and the same start time is the same reminder. */
    public String key() {
        return sourceId + "|" + channelId + "|" + startMs;
    }

    public boolean isValid() {
        return !channelId.isEmpty() && startMs > 0;
    }
}
