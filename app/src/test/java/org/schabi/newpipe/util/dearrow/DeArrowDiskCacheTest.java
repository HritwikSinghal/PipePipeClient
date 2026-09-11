package org.schabi.newpipe.util.dearrow;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assume.assumeFalse;
import static org.junit.Assume.assumeTrue;

import com.grack.nanojson.JsonParserException;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

public class DeArrowDiskCacheTest {

    private static final long DAY_MS = 24L * 60L * 60L * 1000L;
    private static final long MINUTE_MS = 60L * 1000L;
    // mirrors the cache's own private TMP_MAX_AGE_MS: temp files are reclaimed after 5 minutes
    private static final long TMP_MAX_AGE_MS = 5L * MINUTE_MS;

    @Rule
    public TemporaryFolder folder = new TemporaryFolder();

    private File cacheDir() {
        return new File(folder.getRoot(), "dearrow");
    }

    /**
     * Drop a temp file into the cache directory holding a partially flushed body, as a write
     * killed before its rename would leave behind. The real name carries a UUID; only the
     * {@code .tmp} suffix is load-bearing.
     */
    private File writeTempFile(final String name, final long lastModified) throws IOException {
        final File tmp = new File(cacheDir(), name);
        Files.write(tmp.toPath(), "1700000000000\n{\"partia".getBytes(StandardCharsets.UTF_8));
        assertTrue(tmp.setLastModified(lastModified));
        return tmp;
    }

    private static boolean canCreateFileIn(final File dir) {
        final File probe = new File(dir, "probe");
        try {
            if (!probe.createNewFile()) {
                return false;
            }
        } catch (final IOException e) {
            return false;
        }
        probe.delete();
        return true;
    }

    @Test
    public void writeThenReadRoundTrip() {
        final DeArrowDiskCache cache = new DeArrowDiskCache(cacheDir());
        final String json = "{\"dQw4w9WgXcQ\":{\"titles\":[],\"thumbnails\":[]}}";
        final long fetchedAt = 1_700_000_000_123L;

        cache.write("a1b2", json, fetchedAt, null);

        final DeArrowDiskCache.DiskEntry entry = cache.read("a1b2");
        assertNotNull(entry);
        assertEquals(json, entry.rawJson);
        assertEquals(fetchedAt, entry.fetchedAtMs);
    }

    @Test
    public void etagRoundTrip() {
        final DeArrowDiskCache cache = new DeArrowDiskCache(cacheDir());
        final String json = "{\"dQw4w9WgXcQ\":{\"titles\":[],\"thumbnails\":[]}}";
        final String etag = "\"brandingHash;6fe0;YouTube;1789119806000\"";

        cache.write("a1b2", json, 1_700_000_000_123L, etag);

        final DeArrowDiskCache.DiskEntry entry = cache.read("a1b2");
        assertNotNull(entry);
        assertEquals(etag, entry.etag);
        assertEquals(json, entry.rawJson);
        assertEquals(1_700_000_000_123L, entry.fetchedAtMs);
    }

    @Test
    public void entryWrittenBeforeEtagsReadsBackWithoutOne() throws IOException {
        // A file in the pre-ETag format -- header line is the timestamp alone, no tab. It must
        // still read as a valid entry, or every user's existing cache would be discarded on
        // upgrade and re-downloaded in full.
        final DeArrowDiskCache cache = new DeArrowDiskCache(cacheDir());
        assertTrue(cacheDir().mkdirs());
        final File legacy = new File(cacheDir(), "d4d4");
        Files.write(legacy.toPath(),
                "1700000000123\n{\"abc\":{}}".getBytes(StandardCharsets.UTF_8));

        final DeArrowDiskCache.DiskEntry entry = cache.read("d4d4");
        assertNotNull(entry);
        assertNull(entry.etag);
        assertEquals("{\"abc\":{}}", entry.rawJson);
        assertEquals(1_700_000_000_123L, entry.fetchedAtMs);
    }

    @Test
    public void etagWithNonAsciiIsDroppedSoItCannotBreakTheNextRequest() {
        // RFC 9110 allows obs-text (%x80-FF) in an ETag, but OkHttp throws IllegalArgumentException
        // on any header byte >= 0x7f when sending it back as If-None-Match. That throw is not an
        // IOException, so it would not be retried, and the ETag would sit on disk failing every
        // future refresh of this bucket.
        final DeArrowDiskCache cache = new DeArrowDiskCache(cacheDir());
        // Escaped, not a literal accented char, so this source file stays ASCII.
        cache.write("f00f", "{\"x\":{}}", 1_700_000_000_123L, "\"caf\u00e9\"");

        final DeArrowDiskCache.DiskEntry entry = cache.read("f00f");
        assertNotNull(entry);
        assertNull(entry.etag);
        assertEquals("{\"x\":{}}", entry.rawJson);
    }

    @Test
    public void etagContainingATabIsDroppedRatherThanCorruptingTheHeader() {
        // A tab would be read back as the field separator and split the header in the wrong place.
        final DeArrowDiskCache cache = new DeArrowDiskCache(cacheDir());
        cache.write("beef", "{\"x\":{}}", 1_700_000_000_123L, "bad\tetag");

        final DeArrowDiskCache.DiskEntry entry = cache.read("beef");
        assertNotNull(entry);
        assertNull(entry.etag);
        assertEquals("{\"x\":{}}", entry.rawJson);
        assertEquals(1_700_000_000_123L, entry.fetchedAtMs);
    }

    @Test
    public void negativeCacheRoundTrip() throws JsonParserException {
        final DeArrowDiskCache cache = new DeArrowDiskCache(cacheDir());
        final long fetchedAt = 1_700_000_001_000L;

        cache.write("00ff", "{}", fetchedAt, null);

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

        cache.write("beef", pretty, fetchedAt, null);

        final DeArrowDiskCache.DiskEntry entry = cache.read("beef");
        assertNotNull(entry);
        assertEquals(pretty, entry.rawJson);
        assertEquals(fetchedAt, entry.fetchedAtMs);
        // the body's own newlines must survive: only the first newline is a delimiter
        assertTrue(entry.rawJson.indexOf('\n') >= 0);
    }

    @Test
    public void rawJsonWithMultiByteUtf8RoundTrips() {
        final DeArrowDiskCache cache = new DeArrowDiskCache(cacheDir());
        // 2-byte, 3-byte and (as a surrogate pair) 4-byte UTF-8 sequences next to an embedded
        // newline: the byte-oriented read must decode the whole body as UTF-8 in one go rather
        // than per chunk, and must still delimit on the first newline only
        final String json = "{\"vid\":{\"titles\":[{\"title\":\"caf\u00e9 "
                + "\u65e5\u672c\u8a9e \uD83D\uDE00\"}],\n"
                + "\"thumbnails\":[]}}";
        final long fetchedAt = 1_700_000_003_000L;

        cache.write("f00d", json, fetchedAt, null);

        final DeArrowDiskCache.DiskEntry entry = cache.read("f00d");
        assertNotNull(entry);
        assertEquals(json, entry.rawJson);
        assertEquals(fetchedAt, entry.fetchedAtMs);
        // guards this test's intent: were the escapes above ever flattened to ASCII the
        // multi-byte path would silently stop being exercised
        assertTrue(json.getBytes(StandardCharsets.UTF_8).length > json.length());
    }

    @Test
    public void rawJsonLargerThanCopyBufferRoundTrips() {
        final DeArrowDiskCache cache = new DeArrowDiskCache(cacheDir());
        // The reader fills an 8 KiB buffer per pass, so a body many buffers long only survives
        // if every pass is appended -- a single read() would truncate it at the first chunk.
        final StringBuilder builder = new StringBuilder("{\"vid\":{\"titles\":[\n");
        for (int i = 0; i < 2000; i++) {
            builder.append("{\"title\":\"padded title number ").append(i).append("\"},\n");
        }
        builder.append("null]}}");
        final String json = builder.toString();
        assertTrue(json.length() > 4 * 8192);
        final long fetchedAt = 1_700_000_004_000L;

        cache.write("cafe", json, fetchedAt, null);

        final DeArrowDiskCache.DiskEntry entry = cache.read("cafe");
        assertNotNull(entry);
        assertEquals(json.length(), entry.rawJson.length());
        assertEquals(json, entry.rawJson);
        assertEquals(fetchedAt, entry.fetchedAtMs);
    }

    @Test
    public void atomicOverwriteReplaces() {
        final DeArrowDiskCache cache = new DeArrowDiskCache(cacheDir());

        cache.write("c0de", "{\"first\":{}}", 1L, null);
        cache.write("c0de", "{\"second\":{}}", 2L, null);

        final DeArrowDiskCache.DiskEntry entry = cache.read("c0de");
        assertNotNull(entry);
        assertEquals("{\"second\":{}}", entry.rawJson);
        assertEquals(2L, entry.fetchedAtMs);
    }

    @Test
    public void concurrentWritesToSamePrefixNeverPublishAMixture() throws InterruptedException {
        final File dir = cacheDir();
        assertTrue(dir.mkdirs());
        final DeArrowDiskCache cache = new DeArrowDiskCache(dir);

        final int writers = 6;
        final long baseFetchedAt = 1_700_000_100_000L;
        // Bodies several copy buffers long, one distinct fill character each: writers sharing a
        // single temp file would truncate and interleave mid-body instead of each completing in
        // one burst, and the rename would then publish the mixture.
        final String[] bodies = new String[writers];
        for (int i = 0; i < writers; i++) {
            final char[] fill = new char[64 * 1024];
            Arrays.fill(fill, (char) ('a' + i));
            bodies[i] = "{\"" + new String(fill) + "\":{}}";
        }

        final CountDownLatch startGate = new CountDownLatch(1);
        final Thread[] threads = new Thread[writers];
        for (int i = 0; i < writers; i++) {
            final int index = i;
            threads[i] = new Thread(() -> {
                try {
                    startGate.await();
                } catch (final InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                }
                cache.write("a1b2", bodies[index], baseFetchedAt + index, null);
            });
            threads[i].start();
        }
        startGate.countDown();
        for (final Thread t : threads) {
            t.join(TimeUnit.SECONDS.toMillis(30));
            assertFalse("a writer thread never finished", t.isAlive());
        }

        final DeArrowDiskCache.DiskEntry entry = cache.read("a1b2");
        assertNotNull("the last successful rename must leave a readable entry", entry);
        int winner = -1;
        for (int i = 0; i < writers; i++) {
            if (bodies[i].equals(entry.rawJson)) {
                winner = i;
                break;
            }
        }
        assertTrue("published body matches no single writer, length " + entry.rawJson.length(),
                winner >= 0);
        // header and body have to come from the same writer, not be spliced from two
        assertEquals(baseFetchedAt + winner, entry.fetchedAtMs);

        // every writer either renamed its temp file away or deleted it, so only the entry is left
        final File[] left = dir.listFiles();
        assertNotNull(left);
        for (final File f : left) {
            assertFalse("temp file left behind: " + f.getName(), f.getName().endsWith(".tmp"));
        }
        assertEquals(1, left.length);
    }

    @Test
    public void enforceBoundsAgePurge() {
        final DeArrowDiskCache cache = new DeArrowDiskCache(cacheDir());
        final long now = System.currentTimeMillis();

        cache.write("old0", "{}", now, null);
        cache.write("new0", "{}", now, null);
        cache.write("new1", "{}", now, null);

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
            cache.write(prefixes[i], "{}", now, null);
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
    public void enforceBoundsReclaimsStaleTempOrphanButSparesAFreshOne() throws IOException {
        final DeArrowDiskCache cache = new DeArrowDiskCache(cacheDir());
        final long now = System.currentTimeMillis();

        cache.write("keep", "{}", now, null);
        cache.write("aged", "{}", now, null);
        assertTrue(new File(cacheDir(), "aged").setLastModified(now - 40L * DAY_MS));

        // one orphan from a process killed mid-write, one belonging to a write in flight right
        // now -- the latter must not be pulled out from under the writer
        final File staleTmp = writeTempFile("keep.orphan.tmp", now - 2L * TMP_MAX_AGE_MS);
        final File freshTmp = writeTempFile("keep.live.tmp", now);

        // an age cap far longer than the temp deadline, so only the temp-specific sweep can
        // account for the orphan disappearing
        cache.enforceBounds(1000, 365L * DAY_MS);

        assertFalse("stale temp orphan was not reclaimed", staleTmp.exists());
        assertTrue("temp file of a live write was deleted", freshTmp.exists());
        assertNotNull(cache.read("keep"));
        assertNotNull(cache.read("aged"));

        // the age purge of real entries still runs alongside the temp sweep
        cache.enforceBounds(1000, 30L * DAY_MS);

        assertNull(cache.read("aged"));
        assertNotNull(cache.read("keep"));
        assertTrue("temp file of a live write was deleted", freshTmp.exists());
    }

    @Test
    public void enforceBoundsCountCapDoesNotCountTempFiles() throws IOException {
        final DeArrowDiskCache cache = new DeArrowDiskCache(cacheDir());
        final long now = System.currentTimeMillis();

        final String[] prefixes = {"d000", "d001", "d002"};
        for (int i = 0; i < prefixes.length; i++) {
            cache.write(prefixes[i], "{}", now, null);
            final File f = new File(cacheDir(), prefixes[i]);
            // strictly increasing mtime: d000 is what a miscounted cap would evict first
            assertTrue(f.setLastModified(now - (prefixes.length - i) * 1000L));
        }
        final File freshTmp = writeTempFile("d000.live.tmp", now);

        // exactly at the cap: a temp file counted as an entry would push it over
        cache.enforceBounds(prefixes.length, 365L * DAY_MS);

        assertNotNull("temp file was counted towards the entry cap", cache.read("d000"));
        assertNotNull(cache.read("d001"));
        assertNotNull(cache.read("d002"));
        assertTrue(freshTmp.exists());
    }

    @Test
    public void writeUnderAPlainFileDegradesToAMiss() throws IOException {
        // the cache directory path is occupied by a regular file, so mkdirs() and the temp-file
        // open both fail -- every operation has to swallow that and simply report a miss
        assertTrue(folder.newFile("dearrow").isFile());
        final DeArrowDiskCache cache = new DeArrowDiskCache(cacheDir());

        cache.write("a1b2", "{}", 1L, null);
        assertNull(cache.read("a1b2"));

        cache.enforceBounds(10, DAY_MS);
        cache.clear();
        assertNull(cache.read("a1b2"));
    }

    @Test
    public void writeToAReadOnlyDirDegradesToAMiss() throws IOException {
        final DeArrowDiskCache cache = new DeArrowDiskCache(cacheDir());
        cache.write("a1b2", "{\"before\":{}}", 7L, null);
        assertNotNull(cache.read("a1b2"));

        final File dir = cacheDir();
        assumeTrue("filesystem cannot drop the write bit on a directory",
                dir.setWritable(false, false));
        try {
            // running as root, or on a filesystem that ignores the bit: the scenario under test
            // cannot be produced here, so skip rather than assert something untrue
            assumeFalse("directory is still writable without the write bit", canCreateFileIn(dir));

            cache.write("c0de", "{\"after\":{}}", 8L, null);

            assertNull(cache.read("c0de"));
            // an unwritable cache degrades to read-only, not to broken
            final DeArrowDiskCache.DiskEntry existing = cache.read("a1b2");
            assertNotNull(existing);
            assertEquals("{\"before\":{}}", existing.rawJson);
            assertEquals(7L, existing.fetchedAtMs);
        } finally {
            // restore write access so TemporaryFolder can clean up
            dir.setWritable(true, true);
        }
    }

    @Test
    public void clearRemovesAll() {
        final DeArrowDiskCache cache = new DeArrowDiskCache(cacheDir());

        cache.write("aaaa", "{}", 1L, null);
        cache.write("bbbb", "{}", 2L, null);
        cache.write("cccc", "{}", 3L, null);

        cache.clear();

        assertNull(cache.read("aaaa"));
        assertNull(cache.read("bbbb"));
        assertNull(cache.read("cccc"));
    }
}
