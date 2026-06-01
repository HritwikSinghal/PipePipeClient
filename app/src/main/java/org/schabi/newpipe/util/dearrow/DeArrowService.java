package org.schabi.newpipe.util.dearrow;

import android.content.Context;
import android.util.Log;
import android.util.LruCache;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import org.schabi.newpipe.BuildConfig;
import org.schabi.newpipe.extractor.NewPipe;
import org.schabi.newpipe.extractor.downloader.Response;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import io.reactivex.rxjava3.core.Flowable;
import io.reactivex.rxjava3.core.Maybe;
import io.reactivex.rxjava3.schedulers.Schedulers;

/**
 * Client-side access to DeArrow crowdsourced branding, fetched per hash-prefix bucket.
 *
 * <p>{@link #getBranding(String)} resolves a single video's branding from the bucket that contains
 * its hashed ID. Results are cached per bucket with a TTL; a video that is absent from an already
 * fetched bucket resolves to {@link Maybe#empty()} without re-fetching (negative caching).
 * Concurrent requests for the same bucket share a single network call. Any failure resolves to
 * {@link Maybe#empty()} so the caller can keep the original title/thumbnail.</p>
 *
 * <p>Only a genuine {@code 404} is negative-cached. Transient failures (5xx, 429, network errors,
 * timeouts) are retried with bounded backoff and, if they still fail, left <i>uncached</i> so a
 * later view re-fetches them -- this prevents a transient server blip from poisoning a bucket with
 * a false "no data" result for the whole TTL.</p>
 */
public final class DeArrowService {
    private static final String BRANDING_ENDPOINT = "https://sponsor.ajay.app/api/branding/";
    private static final String USER_AGENT =
            "PipePipe DeArrow (+https://github.com/HritwikSinghal/PipePipe)";
    private static final int HASH_PREFIX_LENGTH = 4;
    private static final int HTTP_OK = 200;
    // The ONLY genuine negative: 404 means this bucket truly has no branding -> safe to cache.
    private static final int HTTP_NOT_FOUND = 404;
    private static final int MAX_CACHED_BUCKETS = 256;
    private static final int HEX_MASK = 0xff;
    private static final long CACHE_TTL_MS = TimeUnit.HOURS.toMillis(6);
    private static final long MAX_STALE_MS = TimeUnit.DAYS.toMillis(30);
    private static final int MAX_DISK_BUCKETS = 1024;
    // Bounded retry on transient errors only (kept small so it can't re-introduce the load that
    // triggered the 5xx storm in the first place): up to MAX_RETRIES extra attempts with
    // exponential backoff (RETRY_BASE_DELAY_MS, then doubling).
    private static final int MAX_RETRIES = 2;
    private static final long RETRY_BASE_DELAY_MS = 500L;

    // Diagnostic logging, compiled out of release builds (BuildConfig.DEBUG == false there).
    private static final String TAG = "DeArrowPerf";
    private static final boolean DEBUG = BuildConfig.DEBUG;

    private static DeArrowService instance;

    private final LruCache<String, BucketResult> cache = new LruCache<>(MAX_CACHED_BUCKETS);
    private final ConcurrentHashMap<String, Maybe<BucketResult>> inFlight =
            new ConcurrentHashMap<>();
    private volatile DeArrowDiskCache diskCache;

    private DeArrowService() {
    }

    public static synchronized DeArrowService getInstance() {
        if (instance == null) {
            instance = new DeArrowService();
        }
        return instance;
    }

    /**
     * Enable the persistent disk tier. Call once at app startup. No-op if already initialized or
     * if called again. Safe to never call -- the service stays fully functional, memory-only.
     */
    public void init(final Context context) {
        if (diskCache != null) {
            return;
        }
        final File dir = new File(context.getApplicationContext().getCacheDir(), "dearrow");
        diskCache = new DeArrowDiskCache(dir);
        // Trim stale/oversized entries off the hot path.
        final DeArrowDiskCache dc = diskCache;
        Schedulers.io().scheduleDirect(() -> dc.enforceBounds(MAX_DISK_BUCKETS, MAX_STALE_MS));
    }

    /** Drop both the in-memory and on-disk branding caches. */
    public void clearDiskCache() {
        cache.evictAll();
        final DeArrowDiskCache dc = diskCache;
        if (dc != null) {
            dc.clear();
        }
    }

    /**
     * Resolve the DeArrow branding for a single video.
     *
     * @param videoId the platform video ID (e.g. a YouTube 11-char ID)
     * @return the branding, or {@link Maybe#empty()} on a miss or any error
     */
    public Maybe<DeArrowBranding> getBranding(@Nullable final String videoId) {
        if (videoId == null || videoId.isEmpty()) {
            return Maybe.empty();
        }
        final String prefix = hashPrefix(videoId);
        if (prefix == null) {
            return Maybe.empty();
        }
        return bucket(prefix).flatMap(result -> {
            final DeArrowBranding branding = result.entries.get(videoId);
            return branding == null ? Maybe.empty() : Maybe.just(branding);
        });
    }

    private Maybe<BucketResult> bucket(final String prefix) {
        final BucketResult cached = cache.get(prefix);
        if (cached != null && !cached.isExpired(System.currentTimeMillis())) {
            if (DEBUG) {
                Log.d(TAG, "bucket " + prefix + " HIT (warm)");
            }
            return Maybe.just(cached);
        }
        if (DEBUG) {
            Log.d(TAG, "bucket " + prefix + " MISS ("
                    + (inFlight.containsKey(prefix) ? "in-flight" : "cold") + ")");
        }
        // Share a single in-flight fetch per prefix; cache the result on success.
        // The cache may have been warmed by another thread between the check above and
        // here; re-check inside the atomic computeIfAbsent to avoid a redundant fetch.
        return inFlight.computeIfAbsent(prefix, key -> {
            final BucketResult warmed = cache.get(key);
            if (warmed != null && !warmed.isExpired(System.currentTimeMillis())) {
                return Maybe.<BucketResult>just(warmed).doFinally(() -> inFlight.remove(key));
            }
            return diskRead(key)
                    .flatMap(disk -> {
                        final long age = System.currentTimeMillis() - disk.fetchedAtMs;
                        if (age > CACHE_TTL_MS) {
                            // Stale but within the hard cap: serve now, refresh in background.
                            if (DEBUG) {
                                Log.d(TAG, "disk " + key + " STALE -> serve + bg refresh");
                            }
                            fetchBucket(key)
                                    .doOnSuccess(fresh -> cache.put(key, fresh))
                                    .subscribe(r -> { }, e -> { });
                            return Maybe.<BucketResult>just(
                                    new BucketResult(disk.entries, System.currentTimeMillis()));
                        }
                        return Maybe.just(disk);
                    })
                    .switchIfEmpty(Maybe.defer(() -> fetchBucket(key)))
                    .doOnSuccess(result -> cache.put(key, result))
                    .doFinally(() -> inFlight.remove(key))
                    .cache();
        });
    }

    private Maybe<BucketResult> diskRead(final String prefix) {
        final DeArrowDiskCache dc = diskCache;
        if (dc == null) {
            return Maybe.empty();
        }
        return Maybe.<BucketResult>fromCallable(() -> {
            final DeArrowDiskCache.DiskEntry entry = dc.read(prefix);
            if (entry == null) {
                return null;
            }
            final long age = System.currentTimeMillis() - entry.fetchedAtMs;
            if (age > MAX_STALE_MS) {
                if (DEBUG) {
                    Log.d(TAG, "disk " + prefix + " EXPIRED (age=" + age + "ms)");
                }
                return null;
            }
            final Map<String, DeArrowBranding> entries =
                    DeArrowResponseParser.parseBucket(entry.rawJson);
            if (DEBUG) {
                Log.d(TAG, "disk " + prefix + " HIT entries=" + entries.size()
                        + " age=" + age + "ms");
            }
            return new BucketResult(entries, entry.fetchedAtMs);
        })
                .subscribeOn(Schedulers.io())
                .onErrorComplete();
    }

    private void diskWrite(final String prefix, final String rawJson, final long fetchedAtMs) {
        final DeArrowDiskCache dc = diskCache;
        if (dc != null) {
            dc.write(prefix, rawJson, fetchedAtMs);
        }
    }

    private Maybe<BucketResult> fetchBucket(final String prefix) {
        return Maybe.<BucketResult>fromCallable(() -> {
            final long startMs = System.currentTimeMillis();
            final Response response = NewPipe.getDownloader()
                    .get(BRANDING_ENDPOINT + prefix, requestHeaders());
            final long elapsedMs = System.currentTimeMillis() - startMs;
            final int code = response.responseCode();
            if (code == HTTP_OK) {
                final String body = response.responseBody();
                final Map<String, DeArrowBranding> entries =
                        DeArrowResponseParser.parseBucket(body);
                if (DEBUG) {
                    Log.d(TAG, "fetch " + prefix + " code=200 entries=" + entries.size()
                            + " in " + elapsedMs + "ms");
                }
                final long now = System.currentTimeMillis();
                diskWrite(prefix, body, now);
                return new BucketResult(entries, now);
            }
            if (code == HTTP_NOT_FOUND) {
                // 404 is the only genuine negative: this bucket truly has no branding. Cache it.
                if (DEBUG) {
                    Log.d(TAG, "fetch " + prefix + " code=404 (negative-cached) in "
                            + elapsedMs + "ms");
                }
                final long now = System.currentTimeMillis();
                diskWrite(prefix, "{}", now);
                return new BucketResult(Collections.emptyMap(), now);
            }
            // 5xx / 429 / any other non-200 == transient. Surface as an IOException so it is
            // retried (below) and, if it still fails, stays UNcached -- a later view re-fetches
            // instead of seeing a poisoned empty bucket for 6h.
            if (DEBUG) {
                Log.d(TAG, "fetch " + prefix + " code=" + code
                        + " (transient, NOT cached) in " + elapsedMs + "ms");
            }
            throw new IOException("transient DeArrow response: HTTP " + code);
        })
                .subscribeOn(Schedulers.io())
                .retryWhen(errors -> {
                    final AtomicInteger attempts = new AtomicInteger();
                    return errors.flatMap(error -> {
                        final int attempt = attempts.incrementAndGet();
                        if (attempt > MAX_RETRIES || !isTransient(error)) {
                            return Flowable.<Long>error(error);
                        }
                        final long delayMs = backoffMillis(attempt);
                        if (DEBUG) {
                            Log.d(TAG, "fetch " + prefix + " retry " + attempt + "/" + MAX_RETRIES
                                    + " in " + delayMs + "ms after " + error);
                        }
                        return Flowable.timer(delayMs, TimeUnit.MILLISECONDS, Schedulers.io());
                    });
                })
                .doOnError(e -> {
                    if (DEBUG) {
                        Log.w(TAG, "fetch " + prefix + " FAILED after retries: " + e);
                    }
                })
                // Transient/network/parse errors stay uncached so the next request re-fetches.
                .onErrorComplete();
    }

    /**
     * Whether an error from {@link #fetchBucket} is worth retrying. Network failures (including
     * {@link java.net.SocketTimeoutException}) and our transient-HTTP marker are all
     * {@link IOException}s; a parse failure (nanojson {@code JsonParserException}) or anything else
     * is not retried, since it would only fail again.
     */
    private static boolean isTransient(final Throwable error) {
        return error instanceof IOException;
    }

    /** Exponential backoff for retry {@code attempt} (1-based): 500ms, 1000ms, ... */
    private static long backoffMillis(final int attempt) {
        return RETRY_BASE_DELAY_MS << (attempt - 1);
    }

    @NonNull
    private static Map<String, List<String>> requestHeaders() {
        return Collections.singletonMap("User-Agent", Collections.singletonList(USER_AGENT));
    }

    @Nullable
    private static String hashPrefix(final String videoId) {
        try {
            final MessageDigest digest = MessageDigest.getInstance("SHA-256");
            final byte[] bytes = digest.digest(videoId.getBytes(StandardCharsets.UTF_8));
            final StringBuilder sb = new StringBuilder();
            for (int i = 0; i < bytes.length && sb.length() < HASH_PREFIX_LENGTH; i++) {
                final String hex = Integer.toHexString(HEX_MASK & bytes[i]);
                if (hex.length() == 1) {
                    sb.append('0');
                }
                sb.append(hex);
            }
            return sb.substring(0, HASH_PREFIX_LENGTH);
        } catch (final NoSuchAlgorithmException e) {
            return null;
        }
    }

    private static final class BucketResult {
        private final Map<String, DeArrowBranding> entries;
        private final long fetchedAtMs;

        BucketResult(final Map<String, DeArrowBranding> entries, final long fetchedAtMs) {
            this.entries = entries;
            this.fetchedAtMs = fetchedAtMs;
        }

        boolean isExpired(final long nowMs) {
            return nowMs - fetchedAtMs > CACHE_TTL_MS;
        }
    }
}
