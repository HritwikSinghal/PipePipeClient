package org.schabi.newpipe.util.dearrow;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import com.grack.nanojson.JsonParserException;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Map;

public class DeArrowDiskCacheTest {

    private static final long DAY_MS = 24L * 60L * 60L * 1000L;

    @Rule
    public TemporaryFolder folder = new TemporaryFolder();

    private File cacheDir() {
        return new File(folder.getRoot(), "dearrow");
    }

    @Test
    public void writeThenReadRoundTrip() {
        final DeArrowDiskCache cache = new DeArrowDiskCache(cacheDir());
        final String json = "{\"dQw4w9WgXcQ\":{\"titles\":[],\"thumbnails\":[]}}";
        final long fetchedAt = 1_700_000_000_123L;

        cache.write("a1b2", json, fetchedAt);

        final DeArrowDiskCache.DiskEntry entry = cache.read("a1b2");
        assertNotNull(entry);
        assertEquals(json, entry.rawJson);
        assertEquals(fetchedAt, entry.fetchedAtMs);
    }

    @Test
    public void negativeCacheRoundTrip() throws JsonParserException {
        final DeArrowDiskCache cache = new DeArrowDiskCache(cacheDir());
        final long fetchedAt = 1_700_000_001_000L;

        cache.write("00ff", "{}", fetchedAt);

        final DeArrowDiskCache.DiskEntry entry = cache.read("00ff");
        assertNotNull(entry);
        assertEquals("{}", entry.rawJson);
        assertEquals(fetchedAt, entry.fetchedAtMs);

        final Map<String, DeArrowBranding> bucket =
                DeArrowResponseParser.parseBucket(entry.rawJson);
        assertTrue(bucket.isEmpty());
    }

    @Test
    public void missReturnsNull() {
        final DeArrowDiskCache cache = new DeArrowDiskCache(cacheDir());
        assertNull(cache.read("dead"));
    }

    @Test
    public void corruptFileReturnsNull() throws IOException {
        final DeArrowDiskCache cache = new DeArrowDiskCache(cacheDir());
        final File dir = cacheDir();
        assertTrue(dir.mkdirs());

        final File nonNumeric = new File(dir, "abcd");
        Files.write(nonNumeric.toPath(), "oops\n{}".getBytes(StandardCharsets.UTF_8));
        assertNull(cache.read("abcd"));

        final File empty = new File(dir, "ef01");
        Files.write(empty.toPath(), new byte[0]);
        assertNull(cache.read("ef01"));
    }

    @Test
    public void rawJsonWithEmbeddedNewlines() {
        final DeArrowDiskCache cache = new DeArrowDiskCache(cacheDir());
        final String pretty =
                "{\n"
                + "  \"vid\": {\n"
                + "    \"titles\": [],\n"
                + "    \"thumbnails\": []\n"
                + "  }\n"
                + "}";
        final long fetchedAt = 1_700_000_002_000L;

        cache.write("beef", pretty, fetchedAt);

        final DeArrowDiskCache.DiskEntry entry = cache.read("beef");
        assertNotNull(entry);
        assertEquals(pretty, entry.rawJson);
        assertEquals(fetchedAt, entry.fetchedAtMs);
    }

    @Test
    public void atomicOverwriteReplaces() {
        final DeArrowDiskCache cache = new DeArrowDiskCache(cacheDir());

        cache.write("c0de", "{\"first\":{}}", 1L);
        cache.write("c0de", "{\"second\":{}}", 2L);

        final DeArrowDiskCache.DiskEntry entry = cache.read("c0de");
        assertNotNull(entry);
        assertEquals("{\"second\":{}}", entry.rawJson);
        assertEquals(2L, entry.fetchedAtMs);
    }

    @Test
    public void enforceBoundsAgePurge() {
        final DeArrowDiskCache cache = new DeArrowDiskCache(cacheDir());
        final long now = System.currentTimeMillis();

        cache.write("old0", "{}", now);
        cache.write("new0", "{}", now);
        cache.write("new1", "{}", now);

        final File old = new File(cacheDir(), "old0");
        assertTrue(old.setLastModified(now - 40L * DAY_MS));

        cache.enforceBounds(1000, 30L * DAY_MS);

        assertNull(cache.read("old0"));
        assertNotNull(cache.read("new0"));
        assertNotNull(cache.read("new1"));
    }

    @Test
    public void enforceBoundsCountCap() {
        final DeArrowDiskCache cache = new DeArrowDiskCache(cacheDir());
        final long now = System.currentTimeMillis();

        final String[] prefixes = {"e000", "e001", "e002", "e003", "e004"};
        for (int i = 0; i < prefixes.length; i++) {
            cache.write(prefixes[i], "{}", now);
            final File f = new File(cacheDir(), prefixes[i]);
            // strictly increasing mtime: e000 oldest, e004 newest
            assertTrue(f.setLastModified(now - (prefixes.length - i) * 1000L));
        }

        cache.enforceBounds(3, 365L * DAY_MS);

        assertNull(cache.read("e000"));
        assertNull(cache.read("e001"));
        assertNotNull(cache.read("e002"));
        assertNotNull(cache.read("e003"));
        assertNotNull(cache.read("e004"));
    }

    @Test
    public void clearRemovesAll() {
        final DeArrowDiskCache cache = new DeArrowDiskCache(cacheDir());

        cache.write("aaaa", "{}", 1L);
        cache.write("bbbb", "{}", 2L);
        cache.write("cccc", "{}", 3L);

        cache.clear();

        assertNull(cache.read("aaaa"));
        assertNull(cache.read("bbbb"));
        assertNull(cache.read("cccc"));
    }
}
