package org.schabi.newpipe.util.dearrow;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

public class DeArrowThumbnailSelectorTest {

    private static final double EPS = 1e-9;

    private static DeArrowThumbnail thumb(final double timestamp, final boolean original,
                                          final int votes, final boolean locked) {
        return new DeArrowThumbnail(timestamp, original, votes, locked);
    }

    private static DeArrowBranding branding(final List<DeArrowThumbnail> thumbs,
                                            final double randomTime, final Double duration) {
        return new DeArrowBranding(Collections.emptyList(), thumbs, randomTime, duration);
    }

    @Test
    public void nullBranding_returnsNaN() {
        assertTrue(Double.isNaN(DeArrowThumbnailSelector.selectTime(null)));
    }

    @Test
    public void explicitTimestamp_isReturned() {
        final DeArrowBranding b = branding(
                Collections.singletonList(thumb(42.5, false, 3, false)), 0.5, 100.0);
        assertEquals(42.5, DeArrowThumbnailSelector.selectTime(b), EPS);
    }

    @Test
    public void lockedWins_overHigherVotedUnlocked() {
        final DeArrowBranding b = branding(Arrays.asList(
                thumb(10.0, false, 99, false),
                thumb(20.0, false, 1, true)), 0.5, 100.0);
        assertEquals(20.0, DeArrowThumbnailSelector.selectTime(b), EPS);
    }

    @Test
    public void highestVotesWins_amongUnlocked() {
        final DeArrowBranding b = branding(Arrays.asList(
                thumb(10.0, false, 2, false),
                thumb(20.0, false, 7, false)), 0.5, 100.0);
        assertEquals(20.0, DeArrowThumbnailSelector.selectTime(b), EPS);
    }

    @Test
    public void originalEntries_areDropped() {
        // Only entry is original -> no best -> random fallback (0.5 * 100 = 50).
        final DeArrowBranding b = branding(
                Collections.singletonList(thumb(10.0, true, 50, true)), 0.5, 100.0);
        assertEquals(50.0, DeArrowThumbnailSelector.selectTime(b), EPS);
    }

    @Test
    public void bestWithoutTimestamp_fallsBackToRandomFrame() {
        // votes-only entry, no timestamp -> random fallback (0.25 * 200 = 50).
        final DeArrowBranding b = branding(
                Collections.singletonList(thumb(Double.NaN, false, 5, false)), 0.25, 200.0);
        assertEquals(50.0, DeArrowThumbnailSelector.selectTime(b), EPS);
    }

    @Test
    public void titleOnlyVideo_noThumbnails_usesRandomFrame() {
        final DeArrowBranding b = branding(Collections.emptyList(), 0.1, 300.0);
        assertEquals(30.0, DeArrowThumbnailSelector.selectTime(b), EPS);
    }

    @Test
    public void noThumbnails_noDuration_returnsNaN() {
        final DeArrowBranding b = branding(Collections.emptyList(), 0.1, null);
        assertTrue(Double.isNaN(DeArrowThumbnailSelector.selectTime(b)));
    }

    @Test
    public void missingRandomTime_withDuration_returnsNaN() {
        // No thumbnail submission and a missing randomTime (NaN) -> keep the original thumbnail,
        // even though the duration is known (must not generate a &time=0 frame).
        final DeArrowBranding b = branding(Collections.emptyList(), Double.NaN, 300.0);
        assertTrue(Double.isNaN(DeArrowThumbnailSelector.selectTime(b)));
    }

    @Test
    public void negativeVotesUnlocked_isRejected_thenRandomFallback() {
        final DeArrowBranding b = branding(
                Collections.singletonList(thumb(10.0, false, -1, false)), 0.5, 80.0);
        // best rejected -> random fallback (0.5 * 80 = 40).
        assertEquals(40.0, DeArrowThumbnailSelector.selectTime(b), EPS);
    }

    @Test
    public void negativeVotesUnlocked_noDuration_returnsNaN() {
        final DeArrowBranding b = branding(
                Collections.singletonList(thumb(10.0, false, -1, false)), 0.5, null);
        assertTrue(Double.isNaN(DeArrowThumbnailSelector.selectTime(b)));
    }

    @Test
    public void lockedEntry_withNegativeVotes_isStillReturned() {
        final DeArrowBranding b = branding(
                Collections.singletonList(thumb(5.0, false, -5, true)), 0.5, 100.0);
        // Locked entries are not subject to the negative-vote filter.
        assertEquals(5.0, DeArrowThumbnailSelector.selectTime(b), EPS);
    }

    // --- allowRandomFallback == false: replace only community-submitted frames ---

    @Test
    public void noFallback_submittedTimestamp_isStillReturned() {
        // An explicit submitted frame is honored regardless of the fallback flag.
        final DeArrowBranding b = branding(
                Collections.singletonList(thumb(42.5, false, 3, false)), 0.5, 100.0);
        assertEquals(42.5, DeArrowThumbnailSelector.selectTime(b, false), EPS);
    }

    @Test
    public void noFallback_noSubmission_returnsNaN() {
        // No submission + fallback disabled -> keep the original (would be 30.0 with fallback on).
        final DeArrowBranding b = branding(Collections.emptyList(), 0.1, 300.0);
        assertTrue(Double.isNaN(DeArrowThumbnailSelector.selectTime(b, false)));
    }

    @Test
    public void noFallback_onlyOriginalEntry_returnsNaN() {
        // Only an "original" vote -> no submitted frame -> keep the original.
        final DeArrowBranding b = branding(
                Collections.singletonList(thumb(10.0, true, 50, true)), 0.5, 100.0);
        assertTrue(Double.isNaN(DeArrowThumbnailSelector.selectTime(b, false)));
    }

    @Test
    public void noFallback_submissionWithoutTimestamp_returnsNaN() {
        // A votes-only entry has no usable submitted frame -> keep the original.
        final DeArrowBranding b = branding(
                Collections.singletonList(thumb(Double.NaN, false, 5, false)), 0.25, 200.0);
        assertTrue(Double.isNaN(DeArrowThumbnailSelector.selectTime(b, false)));
    }

    @Test
    public void noFallback_rejectedNegativeVotes_returnsNaN() {
        // Best is rejected (unlocked, negative votes) -> no submitted frame -> keep the original.
        final DeArrowBranding b = branding(
                Collections.singletonList(thumb(10.0, false, -1, false)), 0.5, 80.0);
        assertTrue(Double.isNaN(DeArrowThumbnailSelector.selectTime(b, false)));
    }

    @Test
    public void noFallback_lockedNegativeVotes_isStillReturned() {
        // Locked entries bypass the negative-vote filter and are a genuine submitted frame.
        final DeArrowBranding b = branding(
                Collections.singletonList(thumb(5.0, false, -5, true)), 0.5, 100.0);
        assertEquals(5.0, DeArrowThumbnailSelector.selectTime(b, false), EPS);
    }
}
