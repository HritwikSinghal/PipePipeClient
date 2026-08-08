package org.schabi.newpipe.util.dearrow;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import org.junit.Test;
import org.schabi.newpipe.extractor.InfoItem;
import org.schabi.newpipe.extractor.ServiceList;
import org.schabi.newpipe.extractor.channel.ChannelInfoItem;
import org.schabi.newpipe.extractor.stream.StreamInfoItem;
import org.schabi.newpipe.extractor.stream.StreamType;

import java.util.ArrayList;
import java.util.List;

public class DeArrowPrefetcherTest {

    private static final int YT = ServiceList.YouTube.getServiceId();
    private static final int NOT_YT = ServiceList.SoundCloud.getServiceId();

    /** Mirrors the private {@code DeArrowPrefetcher.MAX_PREFETCH_ITEMS}. */
    private static final int MAX_PREFETCH_ITEMS = 25;

    private static StreamInfoItem stream(final int serviceId, final String url) {
        return new StreamInfoItem(serviceId, url, "some title", StreamType.VIDEO_STREAM);
    }

    /** A mutable list of distinct items, standing in for a caller-owned page of results. */
    private static List<InfoItem> mutablePage(final int size) {
        final List<InfoItem> page = new ArrayList<>(size);
        for (int i = 0; i < size; i++) {
            page.add(stream(YT, "https://www.youtube.com/watch?v=video" + i));
        }
        return page;
    }

    @Test
    public void youtubeWatchUrl_extractsId() {
        final InfoItem item = stream(YT, "https://www.youtube.com/watch?v=dQw4w9WgXcQ");
        assertEquals("dQw4w9WgXcQ", DeArrowPrefetcher.youtubeVideoId(item, YT));
    }

    @Test
    public void youtuBeShortUrl_extractsId() {
        final InfoItem item = stream(YT, "https://youtu.be/dQw4w9WgXcQ");
        assertEquals("dQw4w9WgXcQ", DeArrowPrefetcher.youtubeVideoId(item, YT));
    }

    @Test
    public void nonYoutubeService_returnsNull() {
        final InfoItem item = stream(NOT_YT, "https://soundcloud.com/user/track");
        assertNull(DeArrowPrefetcher.youtubeVideoId(item, YT));
    }

    @Test
    public void nonStreamItem_returnsNull() {
        final InfoItem channel = new ChannelInfoItem(YT, "https://www.youtube.com/@some", "name");
        assertNull(DeArrowPrefetcher.youtubeVideoId(channel, YT));
    }

    @Test
    public void unparseableUrl_returnsNull() {
        final InfoItem item = stream(YT, "https://example.com/not-a-video");
        assertNull(DeArrowPrefetcher.youtubeVideoId(item, YT));
    }

    @Test
    public void nullUrl_returnsNull() {
        final InfoItem item = stream(YT, null);
        assertNull(DeArrowPrefetcher.youtubeVideoId(item, YT));
    }

    // --- snapshot: the caller's list is copied on the calling thread ---

    @Test
    public void snapshot_copiesShortPageInOrder() {
        final List<InfoItem> page = mutablePage(3);
        final List<InfoItem> snap = DeArrowPrefetcher.snapshot(page);

        assertEquals(3, snap.size());
        for (int i = 0; i < 3; i++) {
            assertSame(page.get(i), snap.get(i));
        }
    }

    @Test
    public void snapshot_ofEmptyListIsEmpty() {
        assertTrue(DeArrowPrefetcher.snapshot(new ArrayList<InfoItem>()).isEmpty());
    }

    @Test
    public void snapshot_survivesCallerClearingItsList() {
        // RepliesHandler hands us its cachedReplies and clears it on the main thread; iterating
        // the caller's list later would race, so the snapshot must already own its items.
        final List<InfoItem> page = mutablePage(3);
        final InfoItem first = page.get(0);
        final List<InfoItem> snap = DeArrowPrefetcher.snapshot(page);

        page.clear();

        assertEquals(3, snap.size());
        assertSame(first, snap.get(0));
    }

    @Test
    public void snapshot_ignoresItemsAddedAfterwards() {
        final List<InfoItem> page = mutablePage(2);
        final List<InfoItem> snap = DeArrowPrefetcher.snapshot(page);

        page.add(stream(YT, "https://www.youtube.com/watch?v=appended"));

        assertEquals(2, snap.size());
    }

    @Test
    public void snapshot_capsAtMaxPrefetchItems() {
        final List<InfoItem> page = mutablePage(MAX_PREFETCH_ITEMS + 15);
        final List<InfoItem> snap = DeArrowPrefetcher.snapshot(page);

        // The cap keeps the leading, viewport-sized window rather than an arbitrary slice.
        assertEquals(MAX_PREFETCH_ITEMS, snap.size());
        assertSame(page.get(0), snap.get(0));
        assertSame(page.get(MAX_PREFETCH_ITEMS - 1), snap.get(MAX_PREFETCH_ITEMS - 1));
    }

    @Test
    public void snapshot_exactlyAtCapCopiesEverything() {
        final List<InfoItem> page = mutablePage(MAX_PREFETCH_ITEMS);
        assertEquals(MAX_PREFETCH_ITEMS, DeArrowPrefetcher.snapshot(page).size());
    }
}
