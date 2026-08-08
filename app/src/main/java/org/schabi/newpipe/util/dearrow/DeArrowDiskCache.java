package org.schabi.newpipe.util.dearrow;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

/**
 * Persistent, file-backed disk cache for DeArrow branding buckets.
 *
 * <p>Branding lookups are served from a three-tier hierarchy: an in-memory cache first, this disk
 * cache second, and the network last. The disk tier lets negative ("{@code {}}") and positive
 * bucket results survive an app restart, sparing the network and the DeArrow servers from repeated
 * fetches of the same hash prefixes.</p>
 *
 * <p>Each prefix bucket is stored as one file named exactly after its 4-hex-char prefix under
 * {@code <cacheDir>/dearrow}. The file layout is: line 1 is the epoch-millis fetch time as a
 * decimal long, followed by a single {@code '\n'}, followed by the raw JSON body verbatim. Because
 * the JSON body may itself contain newlines, readers split only at the <i>first</i> newline. Writes
 * are atomic (unique temp file plus rename) and every operation is best-effort -- any I/O or parse
 * failure degrades gracefully to a cache miss rather than throwing.</p>
 *
 * <p>Deliberately built on {@code java.io} only. {@code java.nio.file} is API 26+ and is <i>not</i>
 * covered by the standard {@code desugar_jdk_libs} artifact this app ships, so touching it would
 * raise a {@link LinkageError} on an API 23-25 device -- an {@link Error}, which RxJava rethrows
 * instead of routing to {@code onError}, silently wedging the calling chain. Nothing here needs an
 * API level above 1.</p>
 */
final class DeArrowDiskCache {

    private static final String TMP_SUFFIX = ".tmp";
    private static final int COPY_BUFFER_BYTES = 8192;
    // A live write renames its temp file within milliseconds, so any temp file older than this is
    // an orphan left behind by a process that was killed mid-write.
    private static final long TMP_MAX_AGE_MS = TimeUnit.MINUTES.toMillis(5);

    private final File dir;

    DeArrowDiskCache(final File dir) {
        this.dir = dir;
    }

    /** One decoded cache entry: the raw bucket JSON body and when it was fetched. */
    static final class DiskEntry {
        final String rawJson;
        final long fetchedAtMs;

        DiskEntry(final String rawJson, final long fetchedAtMs) {
            this.rawJson = rawJson;
            this.fetchedAtMs = fetchedAtMs;
        }
    }

    /**
     * Read the cached bucket for the given prefix.
     *
     * @param prefix the 4-hex-char hash prefix (also the file name)
     * @return the decoded entry, or {@code null} on miss, corruption, or any I/O error
     */
    DiskEntry read(final String prefix) {
        if (!isSafePrefix(prefix)) {
            return null;
        }
        final File f = new File(dir, prefix);
        if (!f.isFile()) {
            return null;
        }
        // Throwable, not Exception: an Error escaping here would break this class's documented
        // "degrades to a cache miss" contract and, worse, wedge the RxJava chain that called it.
        try (InputStream in = new FileInputStream(f)) {
            final String content = new String(readFully(in), StandardCharsets.UTF_8);
            final int newline = content.indexOf('\n');
            if (newline < 0) {
                return null;
            }
            final long fetchedAtMs;
            try {
                fetchedAtMs = Long.parseLong(content.substring(0, newline));
            } catch (final NumberFormatException e) {
                return null;
            }
            final String rawJson = content.substring(newline + 1);
            return new DiskEntry(rawJson, fetchedAtMs);
        } catch (final Throwable t) {
            return null;
        }
    }

    /**
     * Atomically persist a bucket for the given prefix. Best-effort: swallows any I/O error.
     *
     * <p>The temp file name is unique per call, not per prefix: two threads persisting the same
     * prefix concurrently would otherwise truncate and interleave into one shared temp file, so the
     * rename would publish a corrupt mixture even though the rename itself is atomic. Temp files
     * orphaned by a kill mid-write are reclaimed by {@link #enforceBounds(int, long)}.</p>
     *
     * @param prefix      the 4-hex-char hash prefix (also the file name)
     * @param rawJson     the raw JSON body to store verbatim
     * @param fetchedAtMs the epoch-millis fetch time recorded on line 1
     */
    void write(final String prefix, final String rawJson, final long fetchedAtMs) {
        if (!isSafePrefix(prefix)) {
            return;
        }
        final File tmp = new File(dir, prefix + "." + UUID.randomUUID() + TMP_SUFFIX);
        try {
            dir.mkdirs();
            final byte[] content = (Long.toString(fetchedAtMs) + "\n" + rawJson)
                    .getBytes(StandardCharsets.UTF_8);
            try (FileOutputStream out = new FileOutputStream(tmp)) {
                out.write(content);
            }
            final File target = new File(dir, prefix);
            if (!tmp.renameTo(target)) {
                tmp.delete();
            }
        } catch (final Throwable t) {
            tmp.delete();
        }
    }

    /** Read a stream to its end. Buckets are a few KB, so buffering the whole body is fine. */
    private static byte[] readFully(final InputStream in) throws IOException {
        final ByteArrayOutputStream out = new ByteArrayOutputStream();
        final byte[] buffer = new byte[COPY_BUFFER_BYTES];
        int read;
        while ((read = in.read(buffer)) != -1) {
            out.write(buffer, 0, read);
        }
        return out.toByteArray();
    }

    /** Delete every entry file in the cache directory. Best-effort. */
    void clear() {
        try {
            if (!dir.isDirectory()) {
                return;
            }
            final File[] files = dir.listFiles();
            if (files == null) {
                return;
            }
            for (final File f : files) {
                f.delete();
            }
        } catch (final Throwable t) {
            // best-effort: ignore
        }
    }

    /**
     * Trim the cache by age and count. Files older than {@code maxAgeMs} (by last-modified time,
     * which write sets to roughly the fetch time) are deleted first; then, if more than
     * {@code maxEntries} remain, the oldest are dropped until the count cap is met. Best-effort.
     *
     * <p>Orphaned temp files are swept too, on their own much shorter deadline: they are always
     * garbage once a write can no longer be in progress, and they do not count towards
     * {@code maxEntries} because they are not readable entries.</p>
     *
     * @param maxEntries the maximum number of entry files to retain
     * @param maxAgeMs   the maximum age in milliseconds before an entry is purged
     */
    void enforceBounds(final int maxEntries, final long maxAgeMs) {
        try {
            if (!dir.isDirectory()) {
                return;
            }
            final File[] listed = dir.listFiles();
            if (listed == null) {
                return;
            }

            final long now = System.currentTimeMillis();
            final List<File> remaining = new ArrayList<>();
            for (final File f : listed) {
                if (!f.isFile()) {
                    continue;
                }
                if (f.getName().endsWith(TMP_SUFFIX)) {
                    if (now - f.lastModified() > TMP_MAX_AGE_MS) {
                        f.delete();
                    }
                } else if (now - f.lastModified() > maxAgeMs) {
                    f.delete();
                } else {
                    remaining.add(f);
                }
            }

            if (remaining.size() > maxEntries) {
                remaining.sort(Comparator.comparingLong(File::lastModified));
                final int toDrop = remaining.size() - maxEntries;
                for (int i = 0; i < toDrop; i++) {
                    remaining.get(i).delete();
                }
            }
        } catch (final Throwable t) {
            // best-effort: ignore
        }
    }

    private static boolean isSafePrefix(final String prefix) {
        if (prefix == null || prefix.isEmpty()) {
            return false;
        }
        if (prefix.indexOf('/') >= 0 || prefix.indexOf('\\') >= 0) {
            return false;
        }
        return !".".equals(prefix) && !"..".equals(prefix);
    }
}
