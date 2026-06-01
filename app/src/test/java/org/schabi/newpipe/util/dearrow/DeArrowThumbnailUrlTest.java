package org.schabi.newpipe.util.dearrow;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

public class DeArrowThumbnailUrlTest {

    private static final String BASE =
            "https://dearrow-thumb.ajay.app/api/v1/getThumbnail?videoID=";

    @Test
    public void buildsUrlWithDecimalTime() {
        assertEquals(BASE + "dQw4w9WgXcQ&time=12.34",
                DeArrowThumbnailUrl.build("dQw4w9WgXcQ", 12.34));
    }

    @Test
    public void integerTime_hasNoTrailingZeros() {
        assertEquals(BASE + "abc&time=50",
                DeArrowThumbnailUrl.build("abc", 50.0));
    }

    @Test
    public void subSecondTime_isPreserved() {
        assertEquals(BASE + "abc&time=0.5",
                DeArrowThumbnailUrl.build("abc", 0.5));
    }

    @Test
    public void zeroTime_isPlainZero() {
        assertEquals(BASE + "abc&time=0",
                DeArrowThumbnailUrl.build("abc", 0.0));
    }

    @Test
    public void timeIsRoundedToMillisecondPrecision() {
        assertEquals(BASE + "abc&time=12.346",
                DeArrowThumbnailUrl.build("abc", 12.3456));
    }

    @Test
    public void videoIdIsPassedThroughVerbatim() {
        assertEquals(BASE + "a_b-C9&time=1",
                DeArrowThumbnailUrl.build("a_b-C9", 1.0));
    }
}
