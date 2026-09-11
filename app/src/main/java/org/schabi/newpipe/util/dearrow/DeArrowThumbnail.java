package org.schabi.newpipe.util.dearrow;

/**
 * An immutable single crowdsourced thumbnail submission returned by the DeArrow branding endpoint.
 *
 * <p>A {@code timestamp} of {@link Double#NaN} means the submission carries no explicit frame time
 * (e.g. the entry only marks the original thumbnail); callers fall back to the random time.</p>
 */
public final class DeArrowThumbnail {
    private final double timestamp;
    private final boolean original;
    private final int votes;
    private final boolean locked;

    public DeArrowThumbnail(final double timestamp,
                            final boolean original,
                            final int votes,
                            final boolean locked) {
        this.timestamp = timestamp;
        this.original = original;
        this.votes = votes;
        this.locked = locked;
    }

    public double getTimestamp() {
        return timestamp;
    }

    public boolean hasTimestamp() {
        return !Double.isNaN(timestamp);
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
