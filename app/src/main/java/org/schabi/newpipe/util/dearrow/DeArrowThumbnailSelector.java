package org.schabi.newpipe.util.dearrow;

import java.util.List;

/**
 * Pure selection logic for DeArrow thumbnails (no Android dependencies).
 *
 * <p>Mirrors {@link DeArrowTitleFormatter}: drop {@code original} submissions, prefer
 * {@code locked}, otherwise the highest non-negative vote count. The chosen entry's explicit
 * frame {@code timestamp} is used when present; otherwise (including title-only videos with no
 * thumbnail submissions) it falls back to {@code randomTime * videoDuration} when both the duration
 * and a {@code randomTime} are known. A missing {@code randomTime} (NaN) yields {@link Double#NaN},
 * keeping the original thumbnail. This matches the DeArrow extension's "replace thumbnails"
 * behavior.</p>
 *
 * <p>Note: because {@code original} entries are dropped and the random-frame fallback applies when
 * no non-original submission wins, a video whose only thumbnail vote is "original" still receives a
 * random-frame replacement. To instead respect an original-vetted thumbnail, rank {@code original}
 * entries in {@link #selectBest} and short-circuit to {@link Double#NaN} when the overall best is
 * original.</p>
 */
public final class DeArrowThumbnailSelector {
    private DeArrowThumbnailSelector() {
    }

    /**
     * Resolve the DeArrow thumbnail frame time in seconds, allowing the random-frame fallback.
     *
     * <p>Equivalent to {@link #selectTime(DeArrowBranding, boolean)} with {@code true}.</p>
     *
     * @param branding the DeArrow branding (may be {@code null})
     * @return the frame time in seconds, or {@link Double#NaN} if the original thumbnail should be
     *         kept
     */
    public static double selectTime(final DeArrowBranding branding) {
        return selectTime(branding, true);
    }

    /**
     * Resolve the DeArrow thumbnail frame time in seconds.
     *
     * @param branding            the DeArrow branding (may be {@code null})
     * @param allowRandomFallback when {@code true}, a video with no usable submitted frame still
     *                            gets a {@code randomTime * duration} generated frame (the DeArrow
     *                            extension's default). When {@code false}, only an explicit
     *                            community-submitted frame is used and everything else keeps the
     *                            original thumbnail.
     * @return the frame time in seconds, or {@link Double#NaN} if the original thumbnail should be
     *         kept
     */
    public static double selectTime(final DeArrowBranding branding,
                                    final boolean allowRandomFallback) {
        if (branding == null) {
            return Double.NaN;
        }

        final DeArrowThumbnail best = selectBest(branding.getThumbnails());
        if (best != null && best.hasTimestamp()) {
            return best.getTimestamp();
        }

        // No usable explicit submitted frame (best without a timestamp, or no best incl. title-only
        // videos). Fall back to the random frame only when allowed; otherwise keep the original.
        if (!allowRandomFallback) {
            return Double.NaN;
        }
        // Fall back to the random frame when both the duration and randomTime are known. A missing
        // randomTime (NaN) means DeArrow supplied no random frame, so keep the original thumbnail.
        final Double duration = branding.getVideoDuration();
        final double randomTime = branding.getRandomTime();
        if (duration != null && !Double.isNaN(randomTime)) {
            return randomTime * duration;
        }
        return Double.NaN;
    }

    private static DeArrowThumbnail selectBest(final List<DeArrowThumbnail> thumbnails) {
        if (thumbnails == null || thumbnails.isEmpty()) {
            return null;
        }

        DeArrowThumbnail best = null;
        for (final DeArrowThumbnail candidate : thumbnails) {
            if (candidate == null || candidate.isOriginal()) {
                continue;
            }
            if (best == null || isBetter(candidate, best)) {
                best = candidate;
            }
        }

        // An unlocked winner is only acceptable if its votes are non-negative.
        if (best != null && !best.isLocked() && best.getVotes() < 0) {
            return null;
        }
        return best;
    }

    private static boolean isBetter(final DeArrowThumbnail candidate,
                                    final DeArrowThumbnail current) {
        if (candidate.isLocked() != current.isLocked()) {
            return candidate.isLocked();
        }
        return candidate.getVotes() > current.getVotes();
    }
}
