package org.schabi.newpipe.util.dearrow;

import java.util.List;
import java.util.Locale;

/**
 * Pure selection-and-formatting logic for DeArrow titles (no Android dependencies).
 *
 * <p>Selection follows the DeArrow rules: drop entries marked {@code original}, prefer
 * {@code locked} submissions, otherwise take the highest non-negative vote count. A leading
 * {@code '>'} marks an exact title that must never be auto-formatted; an inline {@code '>'} marks
 * a single word whose casing must be preserved.</p>
 */
public final class DeArrowTitleFormatter {
    private static final char EXACT_MARKER = '>';

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
            // Exact title: strip the leading marker and never auto-format.
            final String exact = trimmed.substring(1).trim();
            return exact.isEmpty() ? null : exact;
        }

        return autoFormat ? toTitleCase(trimmed) : trimmed;
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

    private static String toTitleCase(final String text) {
        final String[] words = text.split("\\s+");
        final StringBuilder sb = new StringBuilder(text.length());
        for (final String word : words) {
            if (word.isEmpty()) {
                continue;
            }
            if (sb.length() > 0) {
                sb.append(' ');
            }
            if (word.charAt(0) == EXACT_MARKER) {
                // Inline marker: keep this word verbatim, dropping the marker.
                sb.append(word.substring(1));
            } else {
                sb.append(Character.toUpperCase(word.charAt(0)));
                sb.append(word.substring(1).toLowerCase(Locale.ROOT));
            }
        }
        return sb.toString();
    }
}
