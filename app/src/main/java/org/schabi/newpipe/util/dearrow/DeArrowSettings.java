package org.schabi.newpipe.util.dearrow;

import android.content.Context;
import android.content.SharedPreferences;

import androidx.preference.PreferenceManager;

import org.schabi.newpipe.R;

/**
 * Central reader for the DeArrow preference toggles, so view code has one gate to consult.
 */
public final class DeArrowSettings {
    private DeArrowSettings() {
    }

    /**
     * Whether the DeArrow master toggle is on.
     *
     * @param context any context
     * @return {@code true} if DeArrow is enabled
     */
    public static boolean isEnabled(final Context context) {
        return prefs(context).getBoolean(
                context.getString(R.string.dearrow_enable_key), true);
    }

    /**
     * Whether replacement titles should be shown (implies the master toggle is on).
     *
     * @param context any context
     * @return {@code true} if titles should be replaced
     */
    public static boolean isTitleReplacementEnabled(final Context context) {
        return isEnabled(context) && prefs(context).getBoolean(
                context.getString(R.string.dearrow_replace_titles_key), true);
    }

    /**
     * Whether replacement thumbnails should be shown (implies the master toggle is on).
     *
     * @param context any context
     * @return {@code true} if thumbnails should be replaced
     */
    public static boolean isThumbnailReplacementEnabled(final Context context) {
        return isEnabled(context) && prefs(context).getBoolean(
                context.getString(R.string.dearrow_replace_thumbnails_key), true);
    }

    /**
     * Whether videos with no community-submitted thumbnail may show a random generated frame.
     *
     * <p>When off, only explicit community-submitted thumbnails replace the original; everything
     * else keeps its original thumbnail. Implies the thumbnail-replacement toggle is on.</p>
     *
     * @param context any context
     * @return {@code true} if the random-frame fallback is allowed
     */
    public static boolean isRandomThumbnailFallbackEnabled(final Context context) {
        return isThumbnailReplacementEnabled(context) && prefs(context).getBoolean(
                context.getString(R.string.dearrow_random_thumbnails_key), true);
    }

    /**
     * Whether non-exact replacement titles should be converted to title case.
     *
     * @param context any context
     * @return {@code true} if auto-formatting is on
     */
    public static boolean isAutoFormatTitlesEnabled(final Context context) {
        return prefs(context).getBoolean(
                context.getString(R.string.dearrow_auto_format_titles_key), true);
    }

    /**
     * Whether a small marker icon should be prefixed to replaced titles.
     *
     * @param context any context
     * @return {@code true} if replaced titles should be marked
     */
    public static boolean isMarkReplacedTitlesEnabled(final Context context) {
        return prefs(context).getBoolean(
                context.getString(R.string.dearrow_mark_replaced_titles_key), true);
    }

    private static SharedPreferences prefs(final Context context) {
        return PreferenceManager.getDefaultSharedPreferences(context);
    }
}
