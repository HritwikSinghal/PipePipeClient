package org.schabi.newpipe.util.dearrow;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import org.junit.Test;
import org.schabi.newpipe.extractor.ServiceList;

public class DeArrowVideoIdsTest {

    private static final int YT = ServiceList.YouTube.getServiceId();
    private static final int NOT_YT = ServiceList.SoundCloud.getServiceId();

    @Test
    public void watchUrl_extractsId() {
        assertEquals("dQw4w9WgXcQ",
                DeArrowVideoIds.of(YT, "https://www.youtube.com/watch?v=dQw4w9WgXcQ"));
    }

    @Test
    public void shortUrl_extractsId() {
        assertEquals("dQw4w9WgXcQ", DeArrowVideoIds.of(YT, "https://youtu.be/dQw4w9WgXcQ"));
    }

    @Test
    public void nonYoutubeService_isNull() {
        // Same URL, wrong service: DeArrow only has branding for YouTube.
        assertNull(DeArrowVideoIds.of(NOT_YT, "https://www.youtube.com/watch?v=dQw4w9WgXcQ"));
    }

    @Test
    public void nullUrl_isNull() {
        assertNull(DeArrowVideoIds.of(YT, null));
    }

    @Test
    public void emptyUrl_isNull() {
        assertNull(DeArrowVideoIds.of(YT, ""));
    }

    @Test
    public void unparseableUrl_isNull() {
        // The link handler throws for these; the caller must get null rather than an exception,
        // since a filter or a bind runs this over whatever URLs the database happens to hold.
        assertNull(DeArrowVideoIds.of(YT, "not a url at all"));
        assertNull(DeArrowVideoIds.of(YT, "https://www.youtube.com/"));
        assertNull(DeArrowVideoIds.of(YT, "https://example.com/watch?v=dQw4w9WgXcQ"));
    }
}
