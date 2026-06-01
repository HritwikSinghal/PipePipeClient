package org.schabi.newpipe.util.dearrow;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import org.junit.Test;
import org.schabi.newpipe.extractor.InfoItem;
import org.schabi.newpipe.extractor.ServiceList;
import org.schabi.newpipe.extractor.channel.ChannelInfoItem;
import org.schabi.newpipe.extractor.stream.StreamInfoItem;
import org.schabi.newpipe.extractor.stream.StreamType;

public class DeArrowPrefetcherTest {

    private static final int YT = ServiceList.YouTube.getServiceId();
    private static final int NOT_YT = ServiceList.SoundCloud.getServiceId();

    private static StreamInfoItem stream(final int serviceId, final String url) {
        return new StreamInfoItem(serviceId, url, "some title", StreamType.VIDEO_STREAM);
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
}
