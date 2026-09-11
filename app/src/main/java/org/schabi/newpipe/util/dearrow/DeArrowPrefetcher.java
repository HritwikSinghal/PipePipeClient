package org.schabi.newpipe.util.dearrow;

import android.content.Context;
import android.util.Log;

import androidx.annotation.Nullable;
import androidx.annotation.VisibleForTesting;

import org.schabi.newpipe.BuildConfig;
import org.schabi.newpipe.extractor.InfoItem;
import org.schabi.newpipe.extractor.ServiceList;
import org.schabi.newpipe.extractor.stream.StreamInfoItem;
import org.schabi.newpipe.util.PicassoHelper;

import java.util.ArrayList;
import java.util.List;

import io.reactivex.rxjava3.core.Observable;
import io.reactivex.rxjava3.schedulers.Schedulers;

/**
 * Warms the DeArrow caches for a whole page of items ahead of RecyclerView binding.
 *
 * <p>{@link DeArrowItemController} resolves branding lazily, at bind time, so items below the fold
 * only start fetching once they scroll into view; each then pays a full network round-trip and, for
 * thumbnails, a cold server-side generation. This prefetcher front-loads that work: when a page of
 * items is loaded it fires a bounded, fire-and-forget warm that (1) populates the per-bucket
 * branding cache of {@link DeArrowService} and (2) pre-fetches the resulting DeArrow thumbnail into
 * Picasso's cache. By the time a row binds, both the title replacement and the thumbnail are
 * already warm, so they appear immediately instead of trickling in.</p>
 *
 * <p><b>Thumbnail warming is limited to community-submitted frames</b> (it never front-loads the
 * {@code randomTime} fallback, regardless of the user's "random video frames" setting). A submitted
 * frame is already generated server-side, so its {@code fetch()} returns a real {@code 200} that
 * Picasso caches and a later bind shows instantly. A random fallback frame, by contrast, has almost
 * never been generated yet: the generator answers {@code 204 No Content} (kicking off async
 * generation) with an empty body that caches nothing, so prefetching it cannot make the bind any
 * faster -- it only fires a redundant cold generation for ~every row. Random frames therefore stay
 * lazy: {@link DeArrowItemController} still requests them at bind time when the setting is on.</p>
 *
 * <p>The caller's list is snapshotted synchronously before any work is scheduled. Callers hand us
 * live, mutable fields -- {@code RepliesHandler} passes its {@code cachedReplies} and clears it on
 * the main thread, and {@code InfoListAdapter} forwards extractor page lists directly -- so
 * iterating the caller's list on an io thread would race and throw
 * {@link java.util.ConcurrentModificationException}, silently aborting the whole page warm.</p>
 *
 * <p>The warm therefore retains only its own bounded snapshot plus the DeArrow/Picasso singletons;
 * it holds no view or Activity references, so it is safe to leave running if the surface is torn
 * down. Concurrency is capped to stay polite to the DeArrow API.</p>
 */
public final class DeArrowPrefetcher {
    /**
     * Max simultaneous bucket fetches for a page warm.
     *
     * <p>One, because this is speculative work for rows that may never be looked at, and it shares
     * {@link DeArrowService}'s bounded fetch pool with the bind-time fetches that a visible row
     * <i>is</i> waiting on. Leaving a warm able to fill that pool would trade a faster warm for a
     * slower row, which is the wrong way round.</p>
     */
    private static final int MAX_CONCURRENCY = 1;
    /**
     * Warm at most this many items per page -- a viewport-sized window, not the whole feed. The
     * single DeArrow host rate-limits (returns 5xx) under a full-page burst, so we front-load only
     * a screenful-plus and let bind-time resolution cover the rest as the user scrolls. Tunable.
     *
     * <p>Public so a caller that has to <i>build</i> the list it passes here can stop at the same
     * bound instead of converting a whole page for a warm that keeps the first slice of it.</p>
     */
    public static final int MAX_PREFETCH_ITEMS = 25;

    // Diagnostic logging, compiled out of release builds (BuildConfig.DEBUG == false there).
    private static final String TAG = "DeArrowPerf";
    private static final boolean DEBUG = BuildConfig.DEBUG;

    private DeArrowPrefetcher() {
    }

    /**
     * Warm DeArrow branding (and, when enabled, thumbnails) for the given items.
     *
     * <p>No-op unless DeArrow is enabled and at least one replacement (titles or thumbnails) is on.
     * Only YouTube stream items are considered; everything else is skipped cheaply.</p>
     *
     * @param context any context (only the application context is retained)
     * @param items   the freshly loaded page of items; may be {@code null} or empty
     */
    public static void prefetch(@Nullable final Context context,
                                @Nullable final List<? extends InfoItem> items) {
        if (context == null || items == null || items.isEmpty()) {
            return;
        }
        final Context app = context.getApplicationContext();
        if (!DeArrowSettings.isEnabled(app)) {
            return;
        }
        final boolean titlesOn = DeArrowSettings.isTitleReplacementEnabled(app);
        final boolean thumbsOn = DeArrowSettings.isThumbnailReplacementEnabled(app)
                && PicassoHelper.getShouldLoadImages();
        if (!titlesOn && !thumbsOn) {
            return;
        }

        final int youtubeServiceId = ServiceList.YouTube.getServiceId();
        final long startMs = System.currentTimeMillis();
        final int pageSize = items.size();
        // Snapshot on the calling thread: the list is caller-owned and may be mutated or cleared
        // while the warm runs. See the class javadoc.
        final List<InfoItem> warmItems = snapshot(items);
        if (DEBUG) {
            Log.d(TAG, "prefetch page: " + pageSize + " items (warming first " + warmItems.size()
                    + ", titles=" + titlesOn + " thumbs=" + thumbsOn
                    + ", concurrency=" + MAX_CONCURRENCY + ")");
        }
        // Parse IDs and warm buckets off the main thread, capping concurrent fetches (flatMap is
        // the only operator with a maxConcurrency overload). getBranding's bucket cache +
        // in-flight dedup make this idempotent and cheap on a cache hit, so the smaller pages that
        // follow a scroll mostly hit the warm cache.
        Observable.fromIterable(warmItems)
                .subscribeOn(Schedulers.io())
                .flatMap(item -> {
                    final String videoId = youtubeVideoId(item, youtubeServiceId);
                    if (videoId == null) {
                        return Observable.<DeArrowBranding>empty();
                    }
                    return DeArrowService.getInstance().getBrandingSpeculative(videoId)
                            .doOnSuccess(branding -> {
                                if (thumbsOn) {
                                    warmThumbnail(videoId, branding);
                                }
                            })
                            .onErrorComplete()
                            .toObservable();
                }, false, MAX_CONCURRENCY)
                .subscribe(branding -> { }, error -> {
                    // An aborted warm is otherwise indistinguishable from a completed one: the
                    // rows still resolve lazily at bind time, just without the front-loading.
                    if (DEBUG) {
                        Log.w(TAG, "prefetch page ABORTED after "
                                + (System.currentTimeMillis() - startMs) + "ms: " + error);
                    }
                }, () -> {
                    if (DEBUG) {
                        Log.d(TAG, "prefetch page done in "
                                + (System.currentTimeMillis() - startMs) + "ms");
                    }
                });
    }

    /**
     * Copy at most {@link #MAX_PREFETCH_ITEMS} items out of a caller-owned list, on the calling
     * thread.
     *
     * <p>This is both the viewport-window cap and the defence against the caller mutating its list
     * underneath us -- see the class javadoc. Iteration is index-based rather than via an iterator
     * so that a concurrent mutation can at worst truncate the snapshot instead of throwing; the
     * size is re-read each step because the list may shrink mid-copy.</p>
     *
     * @param items the caller's list
     * @return a private, bounded snapshot
     */
    @VisibleForTesting
    static List<InfoItem> snapshot(final List<? extends InfoItem> items) {
        final List<InfoItem> copy = new ArrayList<>(
                Math.min(items.size(), MAX_PREFETCH_ITEMS));
        for (int i = 0; i < MAX_PREFETCH_ITEMS && i < items.size(); i++) {
            try {
                copy.add(items.get(i));
            } catch (final IndexOutOfBoundsException e) {
                // The list shrank between the size check and the get -- stop with what we have.
                break;
            }
        }
        return copy;
    }

    private static void warmThumbnail(final String videoId, final DeArrowBranding branding) {
        // Community-submitted frames only (allowRandomFallback = false): a random fallback frame is
        // almost always a cold 204 that caches nothing, so prefetching it is pure churn. See the
        // class javadoc.
        final double time = DeArrowThumbnailSelector.selectTime(branding, false);
        if (!Double.isNaN(time)) {
            PicassoHelper.prefetchDeArrowThumbnail(DeArrowThumbnailUrl.build(videoId, time));
        }
    }

    /**
     * Extract the YouTube video ID for an item, or {@code null} if it is not a parseable YouTube
     * stream. Pure (no Android/network); package-visible for unit testing.
     *
     * <p>The stream-item check is this method's own: {@link DeArrowVideoIds} works from a service
     * ID and a URL, which a channel or playlist item also has, and warming branding for those would
     * be wasted requests.</p>
     *
     * @param item             the item to inspect
     * @param youtubeServiceId the YouTube service ID to match against
     * @return the video ID, or {@code null}
     */
    @Nullable
    @VisibleForTesting
    static String youtubeVideoId(final InfoItem item, final int youtubeServiceId) {
        if (!(item instanceof StreamInfoItem) || item.getServiceId() != youtubeServiceId) {
            return null;
        }
        return DeArrowVideoIds.of(item.getServiceId(), item.getUrl());
    }
}
