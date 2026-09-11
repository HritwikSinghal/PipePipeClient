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
        return new DeArrowTitle(text, original, votes, locked);
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
    public void nullTitleTextReturnsNull() {
        final DeArrowBranding b = branding(title(null, false, 1, false));
        assertNull(DeArrowTitleFormatter.selectTitle(b, true));
    }

    @Test
    public void emptyTitleTextReturnsNull() {
        final DeArrowBranding b = branding(title("", false, 1, false));
        assertNull(DeArrowTitleFormatter.selectTitle(b, true));
    }

    @Test
    public void whitespaceOnlyTitleTextReturnsNull() {
        final DeArrowBranding b = branding(title("   \t  ", false, 1, false));
        assertNull(DeArrowTitleFormatter.selectTitle(b, true));
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
    public void markerOnlyTitleReturnsNull() {
        final DeArrowBranding b = branding(title(">", false, 1, false));
        assertNull(DeArrowTitleFormatter.selectTitle(b, true));
    }

    @Test
    public void exactTitleStillStripsInlineMarkers() {
        // The exact marker only disables formatting; inline markers are submission syntax and
        // must not survive into the shown title.
        final DeArrowBranding b = branding(title("> Watch >iPhone now", false, 1, false));
        assertEquals("Watch iPhone now", DeArrowTitleFormatter.selectTitle(b, true));
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
    public void autoFormatOffStillStripsInlineMarker() {
        // Casing is left alone with auto-format off, but the marker itself must never be shown.
        final DeArrowBranding b = branding(title("WATCH >iPhone REVIEW", false, 1, false));
        assertEquals("WATCH iPhone REVIEW", DeArrowTitleFormatter.selectTitle(b, false));
    }

    @Test
    public void autoFormatOffDropsLoneMarkerWord() {
        // A marker with nothing after it leaves an empty word, which must not become a double
        // space in the output.
        final DeArrowBranding b = branding(title("hello > world", false, 1, false));
        assertEquals("hello world", DeArrowTitleFormatter.selectTitle(b, false));
    }

    @Test
    public void autoFormatOnDropsLoneMarkerWord() {
        final DeArrowBranding b = branding(title("hello > world", false, 1, false));
        assertEquals("Hello World", DeArrowTitleFormatter.selectTitle(b, true));
    }

    @Test
    public void surroundingWhitespaceIsTrimmed() {
        final DeArrowBranding b = branding(title("   Trimmed title   ", false, 1, false));
        assertEquals("Trimmed title", DeArrowTitleFormatter.selectTitle(b, false));
    }

    @Test
    public void innerWhitespaceIsCollapsedOnBothPaths() {
        // Both paths rejoin the same word split, so neither can leak a run of spaces.
        final DeArrowBranding b = branding(title("Double  spaced\twords", false, 1, false));
        assertEquals("Double spaced words", DeArrowTitleFormatter.selectTitle(b, false));
        assertEquals("Double Spaced Words", DeArrowTitleFormatter.selectTitle(b, true));
    }

    // --- Title casing, mirroring the DeArrow extension's isCustom == true behaviour ---

    @Test
    public void deliberateCapitalizationIsTrusted() {
        // NASA's and DART carry chosen capitals, so they pass through untouched.
        final DeArrowBranding b = branding(
                title("NASA's DART mission explained", false, 1, false));
        assertEquals("NASA's DART Mission Explained",
                DeArrowTitleFormatter.selectTitle(b, true));
    }

    @Test
    public void productNameKeepsItsCapitalization() {
        final DeArrowBranding b = branding(title("I bought a PS5", false, 1, false));
        assertEquals("I Bought a PS5", DeArrowTitleFormatter.selectTitle(b, true));
    }

    @Test
    public void romanNumeralAndColonAreLeftAlone() {
        final DeArrowBranding b = branding(title("Star Wars: Episode II", false, 1, false));
        assertEquals("Star Wars: Episode II", DeArrowTitleFormatter.selectTitle(b, true));
    }

    @Test
    public void functionWordsStayLowercaseMidTitle() {
        final DeArrowBranding b = branding(title("The Lord of the Rings", false, 1, false));
        assertEquals("The Lord of the Rings", DeArrowTitleFormatter.selectTitle(b, true));
    }

    @Test
    public void alreadyTitleCasedTitleIsUnchanged() {
        final DeArrowBranding b = branding(title("Why the Sky Is Blue", false, 1, false));
        assertEquals("Why the Sky Is Blue", DeArrowTitleFormatter.selectTitle(b, true));
    }

    @Test
    public void shoutingTitleIsRecasedUniformly() {
        // An all-caps title carries no signal that would separate an acronym from an ordinary
        // word, so every word is re-cased -- a bare CPU included. Exempting short all-caps words
        // instead would spare every short ordinary word too; see shoutingTitleOfShortWords.
        final DeArrowBranding b = branding(title("NEW CPU BENCHMARKS", false, 1, false));
        assertEquals("New Cpu Benchmarks", DeArrowTitleFormatter.selectTitle(b, true));
    }

    @Test
    public void shoutingTitleOfShortWordsIsFullyRecased() {
        // Regression: a length-based acronym exemption left every word below its threshold
        // shouting, yielding "The CAT SAT ON the MAT" -- worse than not formatting at all.
        final DeArrowBranding b = branding(title("THE CAT SAT ON THE MAT", false, 1, false));
        assertEquals("The Cat Sat on the Mat", DeArrowTitleFormatter.selectTitle(b, true));
    }

    @Test
    public void shoutingTitleOfCommonWordsIsFullyRecased() {
        // Same regression, second shape: "MY DOG ATE MY Homework".
        final DeArrowBranding b = branding(title("MY DOG ATE MY HOMEWORK", false, 1, false));
        assertEquals("My Dog Ate My Homework", DeArrowTitleFormatter.selectTitle(b, true));
    }

    @Test
    public void digitBearingWordSurvivesShoutingTitle() {
        // A digit is the one acronym signal that still means something inside an all-caps title.
        final DeArrowBranding b = branding(title("MY NEW PS5 SETUP", false, 1, false));
        assertEquals("My New PS5 Setup", DeArrowTitleFormatter.selectTitle(b, true));
    }

    @Test
    public void singleAllCapsWordIsTitleCased() {
        final DeArrowBranding b = branding(title("HELP", false, 1, false));
        assertEquals("Help", DeArrowTitleFormatter.selectTitle(b, true));
    }

    @Test
    public void singleAcronymWordIsRecasedLikeAnyShoutingTitle() {
        // A one-word all-caps title is shouting (1 > 1 * 0.5), and a bare acronym is
        // indistinguishable from a shouted ordinary word, so it is re-cased like "HELP" above.
        // Keeping it would equally keep every one-word shout.
        final DeArrowBranding b = branding(title("GPU", false, 1, false));
        assertEquals("Gpu", DeArrowTitleFormatter.selectTitle(b, true));
    }

    @Test
    public void acronymIsKeptInANormallyCasedTitle() {
        // The trust-the-submitter rule only stands down when the title as a whole shouts, so an
        // acronym among normally-cased words survives.
        final DeArrowBranding b = branding(title("my new GPU is fast", false, 1, false));
        assertEquals("My New GPU Is Fast", DeArrowTitleFormatter.selectTitle(b, true));
    }

    @Test
    public void hashtagKeepsItsCasing() {
        final DeArrowBranding b = branding(title("cool clip #shorts", false, 1, false));
        assertEquals("Cool Clip #shorts", DeArrowTitleFormatter.selectTitle(b, true));
    }

    @Test
    public void subredditKeepsItsCasing() {
        final DeArrowBranding b = branding(title("best of r/videos", false, 1, false));
        assertEquals("Best of r/videos", DeArrowTitleFormatter.selectTitle(b, true));
    }

    @Test
    public void yearKeepsItsCasing() {
        // Without the year rule the trailing "s" would be capitalized into "1980S".
        final DeArrowBranding b = branding(title("best of the 1980s", false, 1, false));
        assertEquals("Best of the 1980s", DeArrowTitleFormatter.selectTitle(b, true));
    }

    // Caseless scripts are written as \\u escapes so this file stays pure ASCII. The literals are
    // Japanese ("nihongo no" ... "review") and Korean ("hangugeo" ... "teseuteu").

    @Test
    public void caselessScriptTitleIsNotTreatedAsShouting() {
        // Every character of a CJK/Hangul/Arabic/Thai word is a letter whose uppercase form is
        // itself, so the all-caps test used to match such words and call the whole title
        // "shouting" -- which switched off the deliberate-capitalization trust and re-cased the
        // one real acronym in it (CPU -> Cpu). Nothing here is shouted, so nothing changes.
        final String input = "\u65e5\u672c\u8a9e\u306e CPU \u30ec\u30d3\u30e5\u30fc";
        final DeArrowBranding b = branding(title(input, false, 1, false));
        assertEquals(input, DeArrowTitleFormatter.selectTitle(b, true));
    }

    @Test
    public void caselessScriptTitleKeepsAnAcronymInHangul() {
        final String input = "\ud55c\uad6d\uc5b4 DNA \ud14c\uc2a4\ud2b8";
        final DeArrowBranding b = branding(title(input, false, 1, false));
        assertEquals(input, DeArrowTitleFormatter.selectTitle(b, true));
    }

    @Test
    public void shoutedLatinIsStillReCasedAlongsideCaselessWords() {
        // The counterpart to the two above: caseless words no longer count toward "shouting", but
        // a title whose CASED words are shouted must still be re-cased.
        final DeArrowBranding b = branding(
                title("AMAZING \u65e5\u672c\u8a9e TRICK", false, 1, false));
        assertEquals("Amazing \u65e5\u672c\u8a9e Trick",
                DeArrowTitleFormatter.selectTitle(b, true));
    }
}
