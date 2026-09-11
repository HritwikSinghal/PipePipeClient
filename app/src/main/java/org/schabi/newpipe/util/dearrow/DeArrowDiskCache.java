package org.schabi.newpipe.util.dearrow;

import androidx.annotation.Nullable;

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
 * decimal long, optionally followed by a tab and the response's ETag, then a single {@code '\n'},
 * then the raw JSON body verbatim. The ETag is optional so that files written before it was stored
 * still read as valid entries (with no ETag), rather than being discarded as corrupt. Because
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
    // Printable ASCII bounds for a storable ETag; see isStorableEtag.
    private static final char MIN_PRINTABLE_ASCII = 0x21;
    private static final char MAX_PRINTABLE_ASCII = 0x7e;

    private final File dir;

    DeArrowDiskCache(final File dir) {
        this.dir = dir;
    }

    /** One decoded cache entry: the raw bucket JSON body, when it was fetched, and its ETag. */
    static final class DiskEntry {
        final String rawJson;
        final long fetchedAtMs;
        /** The server's ETag for this body, for revalidation; {@code null} if unknown. */
        @Nullable
        final String etag;

        DiskEntry(final String rawJson, final long fetchedAtMs, @Nullable final String etag) {
            this.rawJson = rawJson;
            this.fetchedAtMs = fetchedAtMs;
            this.etag = etag;
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
            // Line 1 is "<fetchedAtMs>" or "<fetchedAtMs>\t<etag>". The tab is optional so that
            // files written before ETags were stored still read cleanly, as an entry with no ETag.
            final String header = content.substring(0, newline);
            final int tab = header.indexOf('\t');
            final long fetchedAtMs;
            try {
                fetchedAtMs = Long.parseLong(tab < 0 ? header : header.substring(0, tab));
            } catch (final NumberFormatException e) {
                return null;
            }
            final String etag = tab < 0 || tab + 1 >= header.length()
                    ? null : header.substring(tab + 1);
            final String rawJson = content.substring(newline + 1);
            return new DiskEntry(rawJson, fetchedAtMs, etag);
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
     * @param etag        the server's ETag for this body, or {@code null} if it sent none. Stored
     *                    after a tab on line 1 so a later refresh can revalidate instead of
     *                    re-downloading. Dropped rather than stored if it contains a tab or a
     *                    newline, which would corrupt the header line.
     */
    void write(final String prefix, final String rawJson, final long fetchedAtMs,
               @Nullable final String etag) {
        if (!isSafePrefix(prefix)) {
            return;
        }
        final File tmp = new File(dir, prefix + "." + UUID.randomUUID() + TMP_SUFFIX);
        try {
            dir.mkdirs();
            final String header = isStorableEtag(etag)
                    ? Long.toString(fetchedAtMs) + "\t" + etag : Long.toString(fetchedAtMs);
            final byte[] content = (header + "\n" + rawJson)
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

    /**
     * Whether an ETag is safe to store and to send back later.
     *
     * <p>Restricted to printable ASCII, which is stricter than RFC 9110 (it also permits
     * {@code obs-text}, %x80-FF). Two reasons: a tab or newline would corrupt the header line this
     * is stored on, and OkHttp rejects any header value byte >= 0x7f with an
     * {@link IllegalArgumentException} when the ETag is sent back as {@code If-None-Match}. That
     * throw is not an {@link java.io.IOException}, so it would not be retried, and the offending
     * ETag would stay on disk failing every future refresh of that bucket.</p>
     */
    private static boolean isStorableEtag(@Nullable final String etag) {
        if (etag == null || etag.isEmpty()) {
            return false;
        }
        for (int i = 0; i < etag.length(); i++) {
            final char c = etag.charAt(i);
            if (c < MIN_PRINTABLE_ASCII || c > MAX_PRINTABLE_ASCII) {
                return false;
            }
        }
        return true;
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
