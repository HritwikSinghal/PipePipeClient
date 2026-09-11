package org.schabi.newpipe.util.dearrow;

import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Pure selection-and-formatting logic for DeArrow titles (no Android dependencies).
 *
 * <p>Selection follows the DeArrow rules: drop entries marked {@code original}, prefer
 * {@code locked} submissions, otherwise take the highest non-negative vote count. A leading
 * {@code '>'} marks an exact title that must never be auto-formatted; an inline {@code '>'} marks
 * a single word whose casing must be preserved. Both markers are stripped on every path, so a
 * marker never reaches the UI -- not even when auto-formatting is off.</p>
 *
 * <p><b>Title casing</b> mirrors the DeArrow browser extension's {@code titleFormatter} running in
 * its {@code isCustom == true} mode, which is the only mode relevant here: entries flagged
 * {@code original} are dropped during selection, so every title this class formats is a
 * community submission. In that mode the extension trusts the submitter's capitalization -- a word
 * carrying deliberate capitals ({@code iPhone}, {@code PS5}, {@code NASA's}, {@code CS:GO},
 * {@code II}) is passed through verbatim, otherwise-lowercase words are capitalized, and the short
 * function words in {@code NOT_CAPITALIZED} stay lowercase away from a sentence boundary.</p>
 *
 * <p><b>One deliberate deviation from the extension:</b> when more than half the words are all-caps
 * the title is treated as shouting and the trust-the-submitter rule is dropped, so
 * {@code AMAZING new TRICK} becomes {@code Amazing New Trick}. The extension leaves such a
 * submission shouting; PipePipe's "auto-format titles" setting exists precisely to fix it.</p>
 *
 * <p>Inside a shouting title the re-casing is <i>uniform</i>: only a word carrying a digit is kept
 * verbatim. An all-caps title offers no way to tell an acronym from an ordinary word -- {@code CPU}
 * and {@code CAT} are structurally identical -- so exempting short all-caps words would spare every
 * short ordinary word too. See {@link #containsDigit} for why a partial word list is worse than
 * none. Acronyms in a normally-cased title are still preserved.</p>
 */
public final class DeArrowTitleFormatter {
    private static final char EXACT_MARKER = '>';

    /**
     * Short function words that stay lowercase in title case unless they open or close a sentence.
     * Ported verbatim from the DeArrow extension's {@code titleCaseNotCapitalized}.
     */
    private static final Set<String> NOT_CAPITALIZED = new HashSet<>(Arrays.asList(
            "a", "an", "the", "and", "but", "or", "nor", "for", "yet", "so", "as", "in", "of",
            "on", "to", "from", "into", "with", "w/", "upon", "at", "by", "via", "vs", "v.s.",
            "vs.", "ft", "ft.", "feat", "feat.", "etc.", "etc"));

    /**
     * Abbreviations that end in {@code '.'} without ending a sentence, so the word after them is
     * not a sentence start. Ported verbatim from the extension's {@code notStartOfSentence}.
     */
    private static final Set<String> NOT_START_OF_SENTENCE = new HashSet<>(Arrays.asList(
            "v.s.", "vs.", "ft.", "feat.", "mr.", "mrs.", "ms.", "dr.", "prof.", "sr.", "jr."));

    private static final Pattern WHITESPACE = Pattern.compile("\\s+");
    /** Bracketing punctuation dropped before a list lookup, so {@code (of} matches {@code of}. */
    private static final Pattern LIST_KEY_NOISE = Pattern.compile("[\\[({<:)}\\]]");
    /** A decade or year, optionally pluralized: {@code 2000s}, {@code (1980s)}, {@code 1980's}. */
    private static final Pattern YEAR = Pattern.compile(
            "^[\\[({\"'\u2018\u2019]*[0-9]{2,4}['\u2019]?s[)}\\]\"'\u2018\u2019]*$");
    /** A number immediately followed by a letter: {@code 3rd}, {@code 23w14a}, {@code 4K}. */
    private static final Pattern NUMBER_THEN_LETTER =
            Pattern.compile("^[\\[({\"'\u2018\u2019]*[0-9]+\\p{L}");
    /** A dot-separated acronym: {@code U.S.}, {@code U.S.A}, {@code HLTV.org}. */
    private static final Pattern DOTTED_ACRONYM = Pattern.compile("^[^\\p{L}]*(\\S\\.)+\\S?$");
    /** A word that is nothing but a separator, so it splits the title into sentences. */
    private static final Pattern ENTIRELY_DELIMITER =
            Pattern.compile("^[-:;~\u2014\u2013|]$");
    /** Trailing punctuation that closes a sentence. */
    private static final Pattern SENTENCE_END_PUNCTUATION = Pattern.compile("[:?.!\\]]$");
    /** A short word joined by a dash ({@code AH-dventures}) -- a pun, not shouting. */
    private static final Pattern SHORT_WORD_BEFORE_DASH =
            Pattern.compile("^\\p{L}{1,3}[-~\u2014]");

    private DeArrowTitleFormatter() {
    }

    /**
     * Resolve the replacement title for the given branding.
     *
     * @param branding   the DeArrow branding (may be {@code null})
     * @param autoFormat whether non-exact titles should be converted to title case
     * @return the replacement title, or {@code null} if the original title should be kept
     */
    public static String selectTitle(final DeArrowBranding branding, final boolean autoFormat) {
        if (branding == null) {
            return null;
        }

        final DeArrowTitle best = selectBest(branding.getTitles());
        if (best == null || best.getTitle() == null) {
            return null;
        }

        final String trimmed = best.getTitle().trim();
        if (trimmed.isEmpty()) {
            return null;
        }

        if (trimmed.charAt(0) == EXACT_MARKER) {
            // Exact title: strip the leading marker and never auto-format. Any further inline
            // markers are still dropped, since a marker is submission syntax, not title text.
            final String exact = stripInlineMarkers(splitWords(trimmed.substring(1).trim()));
            return exact.isEmpty() ? null : exact;
        }

        final String[] words = splitWords(trimmed);
        return autoFormat ? toTitleCase(words) : stripInlineMarkers(words);
    }

    private static DeArrowTitle selectBest(final List<DeArrowTitle> titles) {
        if (titles == null || titles.isEmpty()) {
            return null;
        }

        DeArrowTitle best = null;
        for (final DeArrowTitle candidate : titles) {
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

    private static boolean isBetter(final DeArrowTitle candidate, final DeArrowTitle current) {
        if (candidate.isLocked() != current.isLocked()) {
            return candidate.isLocked();
        }
        return candidate.getVotes() > current.getVotes();
    }

    /**
     * Split a title into words. Both the formatting and the pass-through path go through this, so
     * the two cannot disagree about where a word -- and therefore an inline marker -- begins.
     *
     * @param text a trimmed, non-empty title
     * @return the words of the title, never containing an empty entry
     */
    private static String[] splitWords(final String text) {
        return text.isEmpty() ? new String[0] : WHITESPACE.split(text);
    }

    /**
     * Rejoin words, dropping one leading {@code '>'} from each. Used when auto-formatting is off:
     * the marker is submission syntax that must never be shown, whatever the setting says.
     *
     * @param words the words of the title
     * @return the title with inline markers removed
     */
    private static String stripInlineMarkers(final String[] words) {
        final StringBuilder sb = new StringBuilder();
        for (final String word : words) {
            final String stripped = stripMarker(word);
            if (stripped.isEmpty()) {
                continue;
            }
            if (sb.length() > 0) {
                sb.append(' ');
            }
            sb.append(stripped);
        }
        return sb.toString();
    }

    private static String stripMarker(final String word) {
        return !word.isEmpty() && word.charAt(0) == EXACT_MARKER ? word.substring(1) : word;
    }

    private static String toTitleCase(final String[] words) {
        final boolean shouting = isMostlyAllCaps(words);
        final StringBuilder sb = new StringBuilder();
        for (int i = 0; i < words.length; i++) {
            final String formatted = wordToTitleCase(words[i], i, words, shouting);
            if (formatted.isEmpty()) {
                continue;
            }
            if (sb.length() > 0) {
                sb.append(' ');
            }
            sb.append(formatted);
        }
        return sb.toString();
    }

    /**
     * Title-case a single word in the context of the whole title.
     *
     * @param word     the word to format
     * @param index    the word's position in {@code words}
     * @param words    every word of the title, markers included
     * @param shouting whether the title as a whole reads as all-caps shouting
     * @return the formatted word
     */
    private static String wordToTitleCase(final String word,
                                          final int index,
                                          final String[] words,
                                          final boolean shouting) {
        if (word.isEmpty()) {
            return word;
        }
        if (word.charAt(0) == EXACT_MARKER) {
            // Inline marker: the submitter pinned this word's casing.
            return word.substring(1);
        }
        // Hashtags and subreddit references carry their own casing convention.
        if (word.startsWith("#") || word.startsWith("r/") || YEAR.matcher(word).matches()) {
            return word;
        }
        if (!shouting || !isAllCaps(word)) {
            // Trust deliberate capitalization -- iPhone, PS5, NASA's, meL, CS:GO, II, 3rd. Inside
            // a shouting title an all-caps word carries no signal, so it is re-cased instead.
            if (hasDeliberateCapitalization(word) || NUMBER_THEN_LETTER.matcher(word).find()) {
                return word;
            }
        } else if (containsDigit(word)) {
            // ... but a word carrying a digit (PS5, 4K, RTX3090) is never just a shouted word.
            return word;
        }
        if (isFirstLetterCapital(word) && DOTTED_ACRONYM.matcher(word).matches()) {
            return word;
        }
        if (!startOfSentence(index, words) && !endOfSentence(index, words)
                && NOT_CAPITALIZED.contains(listKey(word))) {
            return word.toLowerCase(Locale.ROOT);
        }
        return capitalizeFirstLetter(word);
    }

    /** A title is shouting when more than half of its words are all-caps. */
    private static boolean isMostlyAllCaps(final String[] words) {
        int count = 0;
        for (final String word : words) {
            if (isAllCaps(word)) {
                count++;
            }
        }
        return count > words.length * 0.5;
    }

    private static boolean isAllCaps(final String word) {
        return word != null
                && hasCasedLetter(word)
                && word.toUpperCase(Locale.ROOT).equals(word)
                && !DOTTED_ACRONYM.matcher(word).matches()
                && !SHORT_WORD_BEFORE_DASH.matcher(word).find();
    }

    /**
     * Whether the word contains a digit, which is the only acronym signal that survives inside a
     * shouting title.
     *
     * <p>Length is deliberately <i>not</i> used. Inside an all-caps title {@code CPU} and
     * {@code CAT} are structurally identical, so a "short all-caps words are acronyms" rule keeps
     * every short ordinary word too: it turned {@code THE CAT SAT ON THE MAT} into
     * {@code The CAT SAT ON the MAT}. Telling the two apart needs an English dictionary, and a
     * partial word list is worse than none -- the words it misses are left shouting in the middle
     * of an otherwise re-cased title. The cost of this rule is that a genuine bare acronym is
     * re-cased along with everything else ({@code NEW CPU BENCHMARKS} becomes
     * {@code New Cpu Benchmarks}); that is a uniform, predictable result rather than a mangled
     * one. Acronyms in a normally-cased title are unaffected -- they are kept by
     * {@link #hasDeliberateCapitalization}, which only stands down when the title as a whole is
     * shouting and an all-caps word therefore carries no signal.</p>
     *
     * @param word the word to inspect
     * @return {@code true} if the word contains a digit
     */
    private static boolean containsDigit(final String word) {
        for (int i = 0; i < word.length(); ) {
            final int codePoint = word.codePointAt(i);
            if (Character.isDigit(codePoint)) {
                return true;
            }
            i += Character.charCount(codePoint);
        }
        return false;
    }

    /**
     * Whether the word's capitals look chosen rather than incidental: more than one capital, or a
     * single capital that is not the first letter. Matches the extension's
     * {@code isWordCustomCapitalization}, minus its recursion into hyphen/slash compounds.
     *
     * @param word the word to inspect
     * @return {@code true} if the word's casing should be preserved
     */
    private static boolean hasDeliberateCapitalization(final String word) {
        int capitals = 0;
        for (int i = 0; i < word.length(); ) {
            final int codePoint = word.codePointAt(i);
            if (Character.isUpperCase(codePoint)) {
                capitals++;
                if (capitals > 1) {
                    return true;
                }
            }
            i += Character.charCount(codePoint);
        }
        return capitals == 1 && !isFirstLetterCapital(word);
    }

    /** Whether the word's first letter -- ignoring any leading punctuation -- is a capital. */
    private static boolean isFirstLetterCapital(final String word) {
        for (int i = 0; i < word.length(); ) {
            final int codePoint = word.codePointAt(i);
            if (Character.isLetter(codePoint)) {
                return Character.isUpperCase(codePoint);
            }
            i += Character.charCount(codePoint);
        }
        return false;
    }

    /**
     * Whether the word contains a letter that actually has a case distinction.
     *
     * <p>"Is this word SHOUTING?" is only a meaningful question for a cased script. In a caseless
     * one -- CJK, Hangul, Arabic, Thai, Devanagari -- every character is a letter and uppercasing
     * is the identity, so the plain {@code hasLetter && toUpperCase().equals(self)} test called
     * every such word all-caps. A title made of caseless words plus one Latin acronym was then
     * "mostly shouting", which switches off the deliberate-capitalization trust and re-cases the
     * acronym ({@code CPU -> Cpu}). Requiring a cased letter keeps the shouting test to the
     * scripts it means something in; a title that mixes caseless words with genuinely shouted
     * Latin ones still trips it on those Latin words.</p>
     */
    private static boolean hasCasedLetter(final String word) {
        for (int i = 0; i < word.length(); ) {
            final int codePoint = word.codePointAt(i);
            if (Character.isLetter(codePoint)
                    && Character.toUpperCase(codePoint) != Character.toLowerCase(codePoint)) {
                return true;
            }
            i += Character.charCount(codePoint);
        }
        return false;
    }

    /** The lookup key for the word lists: lowercased, with bracketing punctuation removed. */
    private static String listKey(final String word) {
        return LIST_KEY_NOISE.matcher(word.toLowerCase(Locale.ROOT)).replaceAll("");
    }

    private static boolean startOfSentence(final int index, final String[] words) {
        return index == 0 || isDelimiter(words[index - 1]);
    }

    private static boolean endOfSentence(final int index, final String[] words) {
        return index == words.length - 1
                || isDelimiter(words[index])
                || ENTIRELY_DELIMITER.matcher(words[index + 1]).matches();
    }

    private static boolean isDelimiter(final String word) {
        if (word.isEmpty()) {
            return false;
        }
        if (!ENTIRELY_DELIMITER.matcher(word).matches()
                && !SENTENCE_END_PUNCTUATION.matcher(word).find()) {
            return false;
        }
        if (NOT_START_OF_SENTENCE.contains(word.toLowerCase(Locale.ROOT))) {
            return false;
        }
        // "U.S.A." ends a word, not a sentence.
        return !(word.endsWith(".") && DOTTED_ACRONYM.matcher(word).matches());
    }

    /**
     * Upper-case the word's first letter and lower-case the rest, leaving any leading punctuation
     * in place. {@code Character.toUpperCase(int)} is locale-independent and the tail is lowered
     * with {@link Locale#ROOT}, so a Turkish device locale cannot turn {@code I} into a dotless
     * {@code i}.
     *
     * @param word the word to capitalize
     * @return the capitalized word, or the word unchanged if it contains no letter
     */
    private static String capitalizeFirstLetter(final String word) {
        for (int i = 0; i < word.length(); ) {
            final int codePoint = word.codePointAt(i);
            final int charCount = Character.charCount(codePoint);
            if (Character.isLetter(codePoint)) {
                return word.substring(0, i)
                        + new String(Character.toChars(Character.toUpperCase(codePoint)))
                        + word.substring(i + charCount).toLowerCase(Locale.ROOT);
            }
            i += charCount;
        }
        return word;
    }
}
