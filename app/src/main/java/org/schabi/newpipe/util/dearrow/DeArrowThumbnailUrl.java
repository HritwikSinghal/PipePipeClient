package org.schabi.newpipe.util.dearrow;

import java.util.Locale;

/**
 * Builds the DeArrow thumbnail-generator URL (no Android dependencies).
 *
 * <p>Format: {@code https://dearrow-thumb.ajay.app/api/v1/getThumbnail?videoID=<id>&time=<sec>}.
 * The time is formatted as a plain, locale-independent decimal with up to millisecond precision
 * and no trailing zeros. YouTube video IDs are URL-safe, so no escaping is applied.</p>
 */
public final class DeArrowThumbnailUrl {
    private static final String BASE =
            "https://dearrow-thumb.ajay.app/api/v1/getThumbnail?videoID=";

    private DeArrowThumbnailUrl() {
    }

    /**
     * Build the generator URL for the given video and frame time.
     *
     * @param videoId     the YouTube video ID
     * @param timeSeconds the frame time in seconds
     * @return the fully-formed thumbnail URL
     */
    public static String build(final String videoId, final double timeSeconds) {
        return BASE + videoId + "&time=" + formatSeconds(timeSeconds);
    }

    private static String formatSeconds(final double seconds) {
        String formatted = String.format(Locale.ROOT, "%.3f", seconds);
        if (formatted.indexOf('.') >= 0) {
            // Strip trailing zeros, then a dangling decimal point.
            int end = formatted.length();
            while (end > 0 && formatted.charAt(end - 1) == '0') {
                end--;
            }
            if (end > 0 && formatted.charAt(end - 1) == '.') {
                end--;
            }
            formatted = formatted.substring(0, end);
        }
        return formatted;
    }
}
