package org.schabi.newpipe.util.dearrow;

/**
 * An immutable single crowdsourced title submission returned by the DeArrow branding endpoint.
 */
public final class DeArrowTitle {
    private final String title;
    private final boolean original;
    private final int votes;
    private final boolean locked;

    public DeArrowTitle(final String title,
                        final boolean original,
                        final int votes,
                        final boolean locked) {
        this.title = title;
        this.original = original;
        this.votes = votes;
        this.locked = locked;
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
}
