package org.schabi.newpipe.util.dearrow;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;

public class DeArrowTitleFormatterTest {

    private static DeArrowTitle title(final String text,
                                      final boolean original,
                                      final int votes,
                                      final boolean locked) {
        return new DeArrowTitle(text, original, votes, locked, "uuid-" + text);
    }

    private static DeArrowBranding branding(final DeArrowTitle... titles) {
        return new DeArrowBranding(Arrays.asList(titles), Collections.emptyList(), 0.0, null);
    }

    @Test
    public void nullBrandingReturnsNull() {
        assertNull(DeArrowTitleFormatter.selectTitle(null, true));
    }

    @Test
    public void emptyTitlesReturnsNull() {
        assertNull(DeArrowTitleFormatter.selectTitle(branding(), true));
    }

    @Test
    public void allOriginalTitlesReturnsNull() {
        final DeArrowBranding b = branding(
                title("Original A", true, 5, false),
                title("Original B", true, 9, false));
        assertNull(DeArrowTitleFormatter.selectTitle(b, false));
    }

    @Test
    public void originalEntriesAreDropped() {
        final DeArrowBranding b = branding(
                title("Clickbait Original", true, 100, false),
                title("Honest title", false, 1, false));
        assertEquals("Honest title", DeArrowTitleFormatter.selectTitle(b, false));
    }

    @Test
    public void lockedWinsOverHigherVotes() {
        final DeArrowBranding b = branding(
                title("Locked low votes", false, 1, true),
                title("Unlocked high votes", false, 50, false));
        assertEquals("Locked low votes", DeArrowTitleFormatter.selectTitle(b, false));
    }

    @Test
    public void lockedWinsEvenWithNegativeVotes() {
        final DeArrowBranding b = branding(
                title("Locked negative", false, -7, true),
                title("Unlocked positive", false, 3, false));
        assertEquals("Locked negative", DeArrowTitleFormatter.selectTitle(b, false));
    }

    @Test
    public void multipleLockedPicksHighestVotes() {
        final DeArrowBranding b = branding(
                title("Locked few", false, 2, true),
                title("Locked many", false, 8, true));
        assertEquals("Locked many", DeArrowTitleFormatter.selectTitle(b, false));
    }

    @Test
    public void highestVotesWinsAmongUnlocked() {
        final DeArrowBranding b = branding(
                title("Two", false, 2, false),
                title("Five", false, 5, false),
                title("Three", false, 3, false));
        assertEquals("Five", DeArrowTitleFormatter.selectTitle(b, false));
    }

    @Test
    public void onlyNegativeUnlockedReturnsNull() {
        final DeArrowBranding b = branding(title("Downvoted", false, -3, false));
        assertNull(DeArrowTitleFormatter.selectTitle(b, false));
    }

    @Test
    public void zeroVoteBeatsNegativeVote() {
        final DeArrowBranding b = branding(
                title("Downvoted", false, -3, false),
                title("Neutral", false, 0, false));
        assertEquals("Neutral", DeArrowTitleFormatter.selectTitle(b, false));
    }

    @Test
    public void exactMarkerStripsAndSkipsFormatting() {
        final DeArrowBranding b = branding(title("> KEEP As-IS casing", false, 1, false));
        assertEquals("KEEP As-IS casing", DeArrowTitleFormatter.selectTitle(b, true));
    }

    @Test
    public void exactMarkerWithOnlyWhitespaceReturnsNull() {
        final DeArrowBranding b = branding(title(">   ", false, 1, false));
        assertNull(DeArrowTitleFormatter.selectTitle(b, true));
    }

    @Test
    public void autoFormatOnTitleCasesShoutingTitle() {
        final DeArrowBranding b = branding(title("AMAZING new TRICK", false, 1, false));
        assertEquals("Amazing New Trick", DeArrowTitleFormatter.selectTitle(b, true));
    }

    @Test
    public void autoFormatOffLeavesTitleUnchanged() {
        final DeArrowBranding b = branding(title("AMAZING new TRICK", false, 1, false));
        assertEquals("AMAZING new TRICK", DeArrowTitleFormatter.selectTitle(b, false));
    }

    @Test
    public void autoFormatPreservesInlineMarkerWord() {
        final DeArrowBranding b = branding(title("WATCH >iPhone REVIEW", false, 1, false));
        assertEquals("Watch iPhone Review", DeArrowTitleFormatter.selectTitle(b, true));
    }

    @Test
    public void surroundingWhitespaceIsTrimmed() {
        final DeArrowBranding b = branding(title("   Trimmed title   ", false, 1, false));
        assertEquals("Trimmed title", DeArrowTitleFormatter.selectTitle(b, false));
    }
}
