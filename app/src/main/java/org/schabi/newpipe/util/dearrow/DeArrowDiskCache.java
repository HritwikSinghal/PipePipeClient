package org.schabi.newpipe.util.dearrow;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

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
 * are atomic (temp file plus rename) and every operation is best-effort -- any I/O or parse failure
 * degrades gracefully to a cache miss rather than throwing.</p>
 */
final class DeArrowDiskCache {

    private static final String TMP_SUFFIX = ".tmp";

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
        try {
            final byte[] bytes = Files.readAllBytes(f.toPath());
            final String content = new String(bytes, StandardCharsets.UTF_8);
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
        } catch (final IOException | RuntimeException e) {
            return null;
        }
    }

    /**
     * Atomically persist a bucket for the given prefix. Best-effort: swallows any I/O error.
     *
     * @param prefix      the 4-hex-char hash prefix (also the file name)
     * @param rawJson     the raw JSON body to store verbatim
     * @param fetchedAtMs the epoch-millis fetch time recorded on line 1
     */
    void write(final String prefix, final String rawJson, final long fetchedAtMs) {
        if (!isSafePrefix(prefix)) {
            return;
        }
        final File tmp = new File(dir, prefix + TMP_SUFFIX);
        try {
            dir.mkdirs();
            final String content = Long.toString(fetchedAtMs) + "\n" + rawJson;
            Files.write(tmp.toPath(), content.getBytes(StandardCharsets.UTF_8));
            final File target = new File(dir, prefix);
            if (!tmp.renameTo(target)) {
                tmp.delete();
            }
        } catch (final IOException | RuntimeException e) {
            tmp.delete();
        }
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
        } catch (final RuntimeException e) {
            // best-effort: ignore
        }
    }

    /**
     * Trim the cache by age and count. Files older than {@code maxAgeMs} (by last-modified time,
     * which write sets to roughly the fetch time) are deleted first; then, if more than
     * {@code maxEntries} remain, the oldest are dropped until the count cap is met. Best-effort.
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
            final List<File> entries = new ArrayList<>();
            for (final File f : listed) {
                if (f.isFile() && !f.getName().endsWith(TMP_SUFFIX)) {
                    entries.add(f);
                }
            }

            final List<File> remaining = new ArrayList<>();
            for (final File f : entries) {
                if (now - f.lastModified() > maxAgeMs) {
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
        } catch (final RuntimeException e) {
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
