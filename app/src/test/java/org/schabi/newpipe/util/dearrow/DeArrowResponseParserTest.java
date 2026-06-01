package org.schabi.newpipe.util.dearrow;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import com.grack.nanojson.JsonParserException;

import org.junit.Test;

import java.util.Map;

public class DeArrowResponseParserTest {

    private static final String BUCKET_JSON =
            "{"
            + "  \"dQw4w9WgXcQ\": {"
            + "    \"titles\": ["
            + "      {\"title\": \"Real title\", \"original\": false, \"votes\": 3,"
            + "       \"locked\": true, \"UUID\": \"t1\"},"
            + "      {\"title\": \"Clickbait\", \"original\": true, \"votes\": 0,"
            + "       \"locked\": false, \"UUID\": \"t2\"}"
            + "    ],"
            + "    \"thumbnails\": ["
            + "      {\"timestamp\": 12.5, \"original\": false, \"votes\": 1,"
            + "       \"locked\": false, \"UUID\": \"th1\"},"
            + "      {\"timestamp\": null, \"original\": true, \"votes\": 0,"
            + "       \"locked\": false, \"UUID\": \"th2\"}"
            + "    ],"
            + "    \"randomTime\": 0.25,"
            + "    \"videoDuration\": 212.0"
            + "  },"
            + "  \"abcdefghijk\": {"
            + "    \"titles\": [],"
            + "    \"thumbnails\": [],"
            + "    \"randomTime\": 0,"
            + "    \"videoDuration\": null"
            + "  }"
            + "}";

    @Test
    public void parsesAllBucketEntries() throws JsonParserException {
        final Map<String, DeArrowBranding> bucket =
                DeArrowResponseParser.parseBucket(BUCKET_JSON);
        assertEquals(2, bucket.size());
        assertTrue(bucket.containsKey("dQw4w9WgXcQ"));
        assertTrue(bucket.containsKey("abcdefghijk"));
    }

    @Test
    public void parsesTitleFields() throws JsonParserException {
        final DeArrowBranding branding =
                DeArrowResponseParser.parseBucket(BUCKET_JSON).get("dQw4w9WgXcQ");
        assertNotNull(branding);
        assertEquals(2, branding.getTitles().size());

        final DeArrowTitle first = branding.getTitles().get(0);
        assertEquals("Real title", first.getTitle());
        assertEquals(3, first.getVotes());
        assertTrue(first.isLocked());
        assertFalse(first.isOriginal());

        final DeArrowTitle second = branding.getTitles().get(1);
        assertTrue(second.isOriginal());
    }

    @Test
    public void parsesNullThumbnailTimestampAsAbsent() throws JsonParserException {
        final DeArrowBranding branding =
                DeArrowResponseParser.parseBucket(BUCKET_JSON).get("dQw4w9WgXcQ");
        assertNotNull(branding);
        assertEquals(2, branding.getThumbnails().size());

        final DeArrowThumbnail withTime = branding.getThumbnails().get(0);
        assertTrue(withTime.hasTimestamp());
        assertEquals(12.5, withTime.getTimestamp(), 0.0001);

        final DeArrowThumbnail withoutTime = branding.getThumbnails().get(1);
        assertFalse(withoutTime.hasTimestamp());
    }

    @Test
    public void parsesRandomTimeAndDuration() throws JsonParserException {
        final DeArrowBranding branding =
                DeArrowResponseParser.parseBucket(BUCKET_JSON).get("dQw4w9WgXcQ");
        assertNotNull(branding);
        assertEquals(0.25, branding.getRandomTime(), 0.0001);
        assertNotNull(branding.getVideoDuration());
        assertEquals(212.0, branding.getVideoDuration(), 0.0001);
    }

    @Test
    public void parsesNullDurationAsNull() throws JsonParserException {
        final DeArrowBranding branding =
                DeArrowResponseParser.parseBucket(BUCKET_JSON).get("abcdefghijk");
        assertNotNull(branding);
        assertTrue(branding.getTitles().isEmpty());
        assertTrue(branding.getThumbnails().isEmpty());
        assertNull(branding.getVideoDuration());
    }

    @Test
    public void parsesMissingRandomTimeAsNaN() throws JsonParserException {
        final String json =
                "{"
                + "  \"noRandom\": {"
                + "    \"titles\": [],"
                + "    \"thumbnails\": [],"
                + "    \"videoDuration\": 100.0"
                + "  }"
                + "}";
        final DeArrowBranding branding = DeArrowResponseParser.parseBucket(json).get("noRandom");
        assertNotNull(branding);
        assertTrue(Double.isNaN(branding.getRandomTime()));
    }

    @Test
    public void emptyObjectYieldsEmptyMap() throws JsonParserException {
        assertTrue(DeArrowResponseParser.parseBucket("{}").isEmpty());
    }

    @Test
    public void malformedJsonThrows() {
        assertThrows(JsonParserException.class,
                () -> DeArrowResponseParser.parseBucket("not json"));
    }
}
