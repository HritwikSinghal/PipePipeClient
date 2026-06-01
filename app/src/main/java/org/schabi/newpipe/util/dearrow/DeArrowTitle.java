package org.schabi.newpipe.util.dearrow;

/**
 * An immutable single crowdsourced title submission returned by the DeArrow branding endpoint.
 */
public final class DeArrowTitle {
    private final String title;
    private final boolean original;
    private final int votes;
    private final boolean locked;
    private final String uuid;

    public DeArrowTitle(final String title,
                        final boolean original,
                        final int votes,
                        final boolean locked,
                        final String uuid) {
        this.title = title;
        this.original = original;
        this.votes = votes;
        this.locked = locked;
        this.uuid = uuid;
    }

    public String getTitle() {
        return title;
    }

    public boolean isOriginal() {
        return original;
    }

    public int getVotes() {
        return votes;
    }

    public boolean isLocked() {
        return locked;
    }

    public String getUuid() {
        return uuid;
    }
}
