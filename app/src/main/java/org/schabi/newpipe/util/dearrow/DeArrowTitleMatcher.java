package org.schabi.newpipe.util.dearrow;

import android.content.Context;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.util.Locale;

/**
 * Matches a list-filter query against the DeArrow title a row is actually <i>showing</i>.
 *
 * <p>The in-app filters (local playlists, watch history, the subscription feed) match the query
 * against the title stored in the database. A row whose title DeArrow replaced displays something
 * else, so typing what is on screen finds nothing. This is the second chance: callers keep their
 * existing match on the stored title and additionally ask here, so both the stored and the
 * displayed title are searchable.</p>
 *
 * <p><b>Memory-cache only.</b> Filtering runs on the main thread on every keystroke, so this never
 * touches the disk cache and never fetches -- see {@link DeArrowService#getCachedBranding}. A video
 * whose bucket is not warm simply does not match on its replacement title, exactly as before. That
 * costs little in practice: {@link DeArrowPrefetcher} warms the head of every loaded page, which is
 * the set of rows whose displayed title can differ from the stored one in the first place.</p>
 *
 * <p>Build one per filter pass with {@link #forQuery} and reuse it for every item: the preference
 * reads and the query's case folding then happen once rather than per item.</p>
 */
public final class DeArrowTitleMatcher {
    /** A matcher that never matches, used when DeArrow titles are off or the query is empty. */
    private static final DeArrowTitleMatcher DISABLED = new DeArrowTitleMatcher(null, false);

    @Nullable
    private final String lowercaseQuery;
    private final boolean autoFormat;

    private DeArrowTitleMatcher(@Nullable final String lowercaseQuery, final boolean autoFormat) {
        this.lowercaseQuery = lowercaseQuery;
        this.autoFormat = autoFormat;
    }

    /**
     * A matcher for one filter pass.
     *
     * @param context any context, used to read the DeArrow preferences once
     * @param query   the user's raw filter text
     * @return a matcher; never {@code null}, but one that matches nothing when DeArrow titles are
     *         disabled or the query is empty
     */
    @NonNull
    public static DeArrowTitleMatcher forQuery(final Context context,
                                               @Nullable final String query) {
        if (query == null || query.isEmpty()
                || !DeArrowSettings.isTitleReplacementEnabled(context)) {
            return DISABLED;
        }
        return new DeArrowTitleMatcher(query.toLowerCase(Locale.getDefault()),
                DeArrowSettings.isAutoFormatTitlesEnabled(context));
    }

    /**
     * Whether the DeArrow title currently shown for this item contains the query.
     *
     * <p>Formatting is applied exactly as {@link DeArrowItemController} applies it, so the text
     * compared here is the text on screen -- including the auto title-casing, which changes which
     * queries match.</p>
     *
     * @param serviceId the item's service ID
     * @param url       the item's URL
     * @return {@code true} if a cached DeArrow title for this item contains the query
     */
    public boolean matches(final int serviceId, @Nullable final String url) {
        if (lowercaseQuery == null) {
            return false;
        }
        final String videoId = DeArrowVideoIds.of(serviceId, url);
        if (videoId == null) {
            return false;
        }
        final DeArrowBranding branding = DeArrowService.getInstance().getCachedBranding(videoId);
        if (branding == null) {
            return false;
        }
        final String title = DeArrowTitleFormatter.selectTitle(branding, autoFormat);
        return title != null && title.toLowerCase(Locale.getDefault()).contains(lowercaseQuery);
    }
}
