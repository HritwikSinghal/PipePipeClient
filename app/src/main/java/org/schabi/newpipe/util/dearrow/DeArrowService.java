package org.schabi.newpipe.util.dearrow;

import android.content.Context;
import android.util.Log;
import android.util.LruCache;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.grack.nanojson.JsonParserException;

import org.schabi.newpipe.BuildConfig;
import org.schabi.newpipe.extractor.NewPipe;
import org.schabi.newpipe.extractor.downloader.StreamingResponse;
import org.schabi.newpipe.extractor.exceptions.ReCaptchaException;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import io.reactivex.rxjava3.core.Flowable;
import io.reactivex.rxjava3.core.Maybe;
import io.reactivex.rxjava3.core.Scheduler;
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
 * <p>Only a genuine {@code 404} is negative-cached. Transient failures (5xx, network errors,
 * timeouts) are retried with bounded backoff and, if they still fail, left <i>uncached</i> so a
 * later view re-fetches them -- this prevents a transient server blip from poisoning a bucket with
 * a false "no data" result for the whole TTL. A {@code 429} is the exception: it is never retried,
 * and instead suspends <i>all</i> branding fetches for {@link #RATE_LIMIT_HOLD_MS}, since retrying
 * a rate limit only adds load at the moment the server is asking for less of it.</p>
 */
public final class DeArrowService {
    private static final String BRANDING_ENDPOINT = "https://sponsor.ajay.app/api/branding/";
    private static final String USER_AGENT =
            "PipePipe DeArrow (+https://github.com/HritwikSinghal/PipePipe)";
    private static final int HASH_PREFIX_LENGTH = 4;
    private static final int HTTP_OK = 200;
    // The server answers a conditional revalidation with 304 when the bucket has not changed: no
    // body, so the ~17KB we already hold on disk stands and only its freshness needs updating.
    private static final int HTTP_NOT_MODIFIED = 304;
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
    // Whole-call budget per bucket. The shared downloader client has a 30s read timeout, no call
    // timeout, and its own 3-attempt DNS retry loop, which under our own retry ladder could occupy
    // one of only MAX_FETCH_THREADS threads for over a minute. Branding is decorative: if it is
    // not here in a few seconds the user has already seen the original title.
    private static final long FETCH_TIMEOUT_MS = 5000L;
    // How long to stop fetching entirely after the server rate-limits us.
    private static final long RATE_LIMIT_HOLD_MS = TimeUnit.SECONDS.toMillis(60);
    // Re-run the disk sweep every N writes, so a single long session cannot grow past the cap.
    private static final int ENFORCE_BOUNDS_EVERY_WRITES = 64;
    private static final int BODY_BUFFER_BYTES = 8192;
    // Hard cap on simultaneous requests to the single volunteer-run DeArrow host, applied where the
    // requests are issued rather than at each caller. Schedulers.io() is unbounded, so every caller
    // that reached it was its own private limit -- and backgroundRefresh had none at all: a cold
    // start against a warm-but-stale disk cache fired one refresh per distinct prefix on screen,
    // all at once.
    //
    // Three, deliberately more than DeArrowPrefetcher's own gate of 1: a speculative page warm and
    // a visible row's bind share this pool, so sizing the two equal would let the warm occupy every
    // thread while the row the user is looking at queues behind it. (A bind for a bucket the warm
    // is already fetching does not queue at all -- inFlight joins it.)
    private static final int MAX_FETCH_THREADS = 3;
    // Refreshes are strictly off the critical path (a stale bucket has already been served), so
    // they get their own single low-priority thread: bounded, and unable to queue in front of a
    // fetch that a visible row is actually waiting on.
    private static final int MAX_REFRESH_THREADS = 1;
    // Page warms get their own thread too. DeArrowPrefetcher's own flatMap gate bounds ONE warm,
    // not all of them -- it builds a fresh chain per call and there are four call sites -- so
    // overlapping warms (a paginating feed plus a tab switch plus a playlist) could still take
    // every foreground thread and put a visible row's fetch behind them.
    private static final int MAX_PREFETCH_THREADS = 1;
    // How long a queued fetch may wait before it is abandoned rather than run; see fetchBucketNow.
    // Generous enough not to interfere with the retry ladder (2 retries at 5s + 0.5s/1s backoff).
    private static final long MAX_QUEUE_WAIT_MS = 15_000L;

    // Diagnostic logging, compiled out of release builds (BuildConfig.DEBUG == false there).
    private static final String TAG = "DeArrowPerf";
    private static final boolean DEBUG = BuildConfig.DEBUG;

    private static DeArrowService instance;

    /** Every foreground bucket fetch runs here, so at most {@link #MAX_FETCH_THREADS} are in the
     *  air at once whatever mix of binds, prefetches and filters asked for them. */
    private static final Scheduler FETCH_SCHEDULER =
            boundedScheduler(MAX_FETCH_THREADS, "DeArrow-fetch", Thread.NORM_PRIORITY - 1);
    /** Stale-while-revalidate refreshes; see {@link #MAX_REFRESH_THREADS}. */
    private static final Scheduler REFRESH_SCHEDULER =
            boundedScheduler(MAX_REFRESH_THREADS, "DeArrow-refresh", Thread.MIN_PRIORITY);
    /** Speculative page warms; see {@link #MAX_PREFETCH_THREADS}. */
    private static final Scheduler PREFETCH_SCHEDULER =
            boundedScheduler(MAX_PREFETCH_THREADS, "DeArrow-prefetch", Thread.MIN_PRIORITY);

    private final LruCache<String, BucketResult> cache = new LruCache<>(MAX_CACHED_BUCKETS);
    private final ConcurrentHashMap<String, Maybe<BucketResult>> inFlight =
            new ConcurrentHashMap<>();
    // Prefixes whose stale disk entry is being refreshed in the background. Separate from
    // inFlight: a background refresh has no subscriber waiting on it and must not be joined.
    private final Set<String> refreshInFlight = ConcurrentHashMap.newKeySet();
    // Bumped by clearDiskCache(). A fetch captures the generation it started under and discards
    // its own memory/disk writes if it no longer matches, so results that were already in flight
    // when the user cleared the cache cannot repopulate it seconds later.
    private final AtomicInteger cacheGeneration = new AtomicInteger();
    private final AtomicInteger diskWrites = new AtomicInteger();
    // Epoch millis before which no fetch may be attempted. Set when the server rate-limits us.
    private volatile long rateLimitedUntilMs;
    private volatile DeArrowDiskCache diskCache;

    private DeArrowService() {
    }

    /**
     * A fixed-size scheduler of daemon threads, so a pending DeArrow fetch can never hold the
     * process open and the pool size is the concurrency limit.
     *
     * @param threads  the pool size, which is the maximum concurrency
     * @param name     the thread name, for logs and traces
     * @param priority the thread priority; below normal, since branding is decorative
     * @return the scheduler
     */
    private static Scheduler boundedScheduler(final int threads, final String name,
                                              final int priority) {
        return Schedulers.from(Executors.newFixedThreadPool(threads, runnable -> {
            final Thread thread = new Thread(runnable, name);
            thread.setDaemon(true);
            thread.setPriority(priority);
            return thread;
        }));
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

    /**
     * Drop both the in-memory and on-disk branding caches.
     *
     * <p>Fetches that are already in flight are deliberately left running -- cancelling them would
     * strand the subscribers waiting on them. Bumping the generation instead makes those fetches
     * discard their own writes when they land, which is the same outcome from the user's point of
     * view (the caches stay empty) with only one invariant to reason about.</p>
     */
    public void clearDiskCache() {
        cacheGeneration.incrementAndGet();
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
        return getBranding(videoId, FETCH_SCHEDULER);
    }

    /**
     * As {@link #getBranding(String)}, but for speculative work -- a page warm nobody is waiting
     * on. Any bucket it has to fetch runs on the prefetch pool, so it cannot occupy a thread that
     * a visible row's fetch needs. A warm already in flight is still shared with a later bind
     * through {@link #inFlight}, so the bind joins it rather than queueing a second request.
     *
     * @param videoId the platform video ID
     * @return the branding, or {@link Maybe#empty()} on a miss or any error
     */
    public Maybe<DeArrowBranding> getBrandingSpeculative(@Nullable final String videoId) {
        return getBranding(videoId, PREFETCH_SCHEDULER);
    }

    private Maybe<DeArrowBranding> getBranding(@Nullable final String videoId,
                                               final Scheduler scheduler) {
        if (videoId == null || videoId.isEmpty()) {
            return Maybe.empty();
        }
        final String prefix = hashPrefix(videoId);
        if (prefix == null) {
            return Maybe.empty();
        }
        return bucket(prefix, scheduler).flatMap(result -> {
            final DeArrowBranding branding = result.entries.get(videoId);
            return branding == null ? Maybe.empty() : Maybe.just(branding);
        });
    }

    /**
     * The branding for a video if -- and only if -- its bucket is already resolved in memory.
     *
     * <p>Unlike {@link #getBranding(String)} this never reads the disk cache and never fetches, so
     * it is safe to call synchronously on the main thread. It exists for the list filters, which
     * run once per item on every keystroke and cannot wait on I/O; a video whose bucket is not warm
     * simply does not match, which is the same outcome the caller already had.</p>
     *
     * <p>Expiry is deliberately ignored. A bucket past its TTL is still exactly what the rows
     * currently on screen were rendered from -- they only pick up fresher branding when they rebind
     * -- so honouring the TTL here would make the filter disagree with the visible titles, which is
     * the very bug this exists to fix.</p>
     *
     * @param videoId the platform video ID
     * @return the cached branding, or {@code null} if nothing is in the memory cache for it
     */
    @Nullable
    public DeArrowBranding getCachedBranding(@Nullable final String videoId) {
        if (videoId == null || videoId.isEmpty()) {
            return null;
        }
        final String prefix = hashPrefix(videoId);
        if (prefix == null) {
            return null;
        }
        final BucketResult cached = cache.get(prefix);
        return cached == null ? null : cached.entries.get(videoId);
    }

    /**
     * The bucket containing {@code prefix}, from memory, disk or the network in that order.
     *
     * <p>Everything runs inside a {@link Maybe#defer} so that merely <i>assembling</i> the chain
     * has no side effects: {@link #getBranding(String)} is documented as a cold {@link Maybe}, and
     * RxJava's {@code flatMap} applies its mapper before the {@code maxConcurrency} gate, so an
     * eagerly registered {@link #inFlight} entry from a source that is never subscribed would leak
     * for the lifetime of the process, outside the {@link LruCache} bound. See DeArrowPrefetcher,
     * which maps 25 items at a time behind a concurrency limit of 2.</p>
     */
    private Maybe<BucketResult> bucket(final String prefix, final Scheduler scheduler) {
        return Maybe.defer(() -> {
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
            return sharedFetch(prefix, scheduler);
        });
    }

    /**
     * Join, or start, the single shared resolution of {@code prefix}. Called at subscribe time
     * only.
     */
    private Maybe<BucketResult> sharedFetch(final String prefix,
                                            final Scheduler scheduler) {
        final int generation = cacheGeneration.get();
        // Holds the exact instance published to inFlight, so the terminal removal below can be
        // made conditional on identity: an unconditional remove(key) would evict whatever is
        // mapped at that moment, which after a late completion can be a different, live chain.
        final AtomicReference<Maybe<BucketResult>> published = new AtomicReference<>();
        // The cache may have been warmed by another thread between bucket()'s check and here;
        // re-check inside the atomic computeIfAbsent to avoid a redundant fetch. A warm hit is
        // returned via the holder rather than published to inFlight -- there is nothing in flight
        // to share, and a shared Maybe.just would fire its doFinally once per subscriber.
        final AtomicReference<BucketResult> warmHit = new AtomicReference<>();
        final Maybe<BucketResult> shared = inFlight.computeIfAbsent(prefix, key -> {
            final BucketResult warmed = cache.get(key);
            if (warmed != null && !warmed.isExpired(System.currentTimeMillis())) {
                warmHit.set(warmed);
                return null;
            }
            final Maybe<BucketResult> chain = diskRead(key)
                    .flatMap(disk -> {
                        final long age = System.currentTimeMillis() - disk.fetchedAtMs;
                        if (age > CACHE_TTL_MS) {
                            // Stale but within the hard cap: serve now, refresh in background.
                            if (DEBUG) {
                                Log.d(TAG, "disk " + key + " STALE -> serve + bg refresh");
                            }
                            backgroundRefresh(key);
                            return Maybe.<BucketResult>just(
                                    new BucketResult(disk.entries, System.currentTimeMillis()));
                        }
                        return Maybe.just(disk);
                    })
                    // revalidate=false: reaching here means the disk had nothing, so there is no
                    // copy to revalidate against.
                    .switchIfEmpty(Maybe.defer(
                            () -> fetchBucket(key, generation, scheduler, false)))
                    .doOnSuccess(result -> cachePut(key, result, generation))
                    .doFinally(() -> inFlight.remove(key, published.get()))
                    // Upstream of cache(), so doFinally fires exactly once however many
                    // subscribers join this chain.
                    .cache();
            published.set(chain);
            return chain;
        });
        if (shared != null) {
            return shared;
        }
        final BucketResult warmed = warmHit.get();
        return warmed == null ? Maybe.empty() : Maybe.just(warmed);
    }

    /**
     * Re-fetch a stale bucket off the critical path, at most one refresh per prefix at a time.
     *
     * <p>The gate is not redundant with {@link #inFlight}: the {@link LruCache} holds only
     * {@link #MAX_CACHED_BUCKETS} buckets, so a scroll touching more prefixes than that can evict
     * this one mid-refresh, and the next lookup would read the still-stale disk entry and fire a
     * second fetch (plus a second concurrent disk write) for the same prefix.</p>
     */
    private void backgroundRefresh(final String prefix) {
        if (!refreshInFlight.add(prefix)) {
            return;
        }
        final int generation = cacheGeneration.get();
        // REFRESH_SCHEDULER, not the foreground pool: this subscribes outside every caller's
        // concurrency gate (the chain that triggered it has already resolved from the stale disk
        // entry and completed), so the scheduler is the only thing bounding it.
        // revalidate=true: a stale disk entry is exactly what got us here, so send its ETag and
        // let the server answer 304 when nothing has changed.
        fetchBucket(prefix, generation, REFRESH_SCHEDULER, true)
                .doOnSuccess(fresh -> cachePut(prefix, fresh, generation))
                .doFinally(() -> refreshInFlight.remove(prefix))
                .subscribe(r -> { }, e -> { });
    }

    /** Commit to the memory cache, unless the caches were cleared since this fetch started. */
    private void cachePut(final String prefix, final BucketResult result, final int generation) {
        if (generation == cacheGeneration.get()) {
            cache.put(prefix, result);
        }
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

    private void diskWrite(final String prefix, final String rawJson, final long fetchedAtMs,
                           @Nullable final String etag, final int generation) {
        final DeArrowDiskCache dc = diskCache;
        if (dc == null || generation != cacheGeneration.get()) {
            return;
        }
        dc.write(prefix, rawJson, fetchedAtMs, etag);
        // The startup sweep alone lets one long session grow past the cap without limit. Re-sweep
        // periodically, on a separate io task so it never lands on a fetch's own thread.
        if (diskWrites.incrementAndGet() % ENFORCE_BOUNDS_EVERY_WRITES == 0) {
            Schedulers.io().scheduleDirect(() -> dc.enforceBounds(MAX_DISK_BUCKETS, MAX_STALE_MS));
        }
    }

    /**
     * Fetch one bucket over the network, with the bounded retry ladder.
     *
     * @param prefix     the hash prefix bucket to fetch
     * @param generation the cache generation this fetch started under
     * @param scheduler  the bounded pool the request runs on, which is what caps concurrency
     *                   against the DeArrow host -- see {@link #MAX_FETCH_THREADS}
     * @param revalidate whether a cached copy exists to revalidate conditionally rather than
     *                   re-download; true only on the stale-refresh path
     * @return the parsed bucket, or empty if it could not be fetched
     */
    private Maybe<BucketResult> fetchBucket(final String prefix, final int generation,
                                            final Scheduler scheduler, final boolean revalidate) {
        // Captured here, where the decision to fetch is made, rather than inside the callable,
        // which only runs once a pool thread picks the task up -- the gap between the two is
        // exactly the queue wait that fetchBucketNow checks.
        final long queuedAtMs = System.currentTimeMillis();
        return Maybe.<BucketResult>fromCallable(
                        () -> fetchBucketNow(prefix, generation, revalidate, queuedAtMs))
                .subscribeOn(scheduler)
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
     * One blocking attempt at the branding endpoint. Runs on an io thread.
     *
     * @param prefix     the hash prefix bucket to fetch
     * @param generation the cache generation this fetch started under
     * @return the parsed bucket, or {@code null} for "no answer, cache nothing"
     * @throws IOException         on a transient failure, so the caller's ladder can retry it
     * @throws JsonParserException on a malformed body. Deliberately not an {@link IOException}:
     *                             {@link #isTransient} therefore does not retry it, since a body
     *                             that failed to parse once will fail again, and it stays uncached
     *                             so a later view can re-fetch.
     */
    @Nullable
    private BucketResult fetchBucketNow(final String prefix, final int generation,
                                        final boolean revalidate, final long queuedAtMs)
            throws IOException, JsonParserException {
        // The pools have unbounded FIFO queues and nothing cancels a queued task -- disposing a
        // recycled row's subscription does not dispose the shared upstream. A fling past a hundred
        // cold rows therefore leaves a long backlog, and the row the user actually stops on queues
        // behind all of it. Abandoning tasks that waited too long drains that backlog in
        // microseconds instead of a network call each. Returning null is the existing
        // "no answer, cache nothing" path, so the row simply keeps its original.
        final long waitedMs = System.currentTimeMillis() - queuedAtMs;
        if (waitedMs > MAX_QUEUE_WAIT_MS) {
            if (DEBUG) {
                Log.d(TAG, "fetch " + prefix + " ABANDONED (queued " + waitedMs + "ms)");
            }
            return null;
        }
        // Likewise pointless once the caches have been cleared underneath this task.
        if (generation != cacheGeneration.get()) {
            if (DEBUG) {
                Log.d(TAG, "fetch " + prefix + " ABANDONED (cache cleared while queued)");
            }
            return null;
        }

        final long holdRemainingMs = rateLimitedUntilMs - System.currentTimeMillis();
        // The upper bound catches a backwards wall-clock jump, which would otherwise leave the
        // hold stuck far into the future. Resolving to null (not an error) means nothing is
        // negative-cached, so the bucket is fetched normally once the hold expires.
        if (holdRemainingMs > 0 && holdRemainingMs <= RATE_LIMIT_HOLD_MS) {
            if (DEBUG) {
                Log.d(TAG, "fetch " + prefix + " SKIPPED (rate-limit hold, "
                        + holdRemainingMs + "ms left)");
            }
            return null;
        }

        // On the stale-refresh path we already hold a copy of this bucket; read it back so the
        // request can be conditional. The disk read costs one small file open on a background
        // thread, against re-downloading ~17KB that has usually not changed.
        final DeArrowDiskCache dc = diskCache;
        final DeArrowDiskCache.DiskEntry cached =
                (revalidate && dc != null) ? dc.read(prefix) : null;
        final String knownEtag = cached == null ? null : cached.etag;

        final long startMs = System.currentTimeMillis();
        final int code;
        final String body;
        final String newEtag;
        // Null localization: an Accept-Language header would only add identifying entropy to a
        // request whose whole point is to be indistinguishable from other clients'.
        try (StreamingResponse response = NewPipe.getDownloader().getStreaming(
                BRANDING_ENDPOINT + prefix, requestHeaders(knownEtag), null, FETCH_TIMEOUT_MS)) {
            code = response.responseCode();
            body = code == HTTP_OK ? readBody(response.body()) : "";
            newEtag = response.getHeader("ETag");
        } catch (final ReCaptchaException e) {
            // The downloader converts every HTTP 429 into this before the response -- and its
            // Retry-After header -- is visible here, so we cannot honour the server's own hint
            // and use a fixed hold instead. Never retried: retrying a rate limit raises the
            // request volume exactly when the server is asking for less of it.
            rateLimitedUntilMs = System.currentTimeMillis() + RATE_LIMIT_HOLD_MS;
            if (DEBUG) {
                Log.w(TAG, "fetch " + prefix + " RATE LIMITED -- holding off all DeArrow "
                        + "fetches for " + RATE_LIMIT_HOLD_MS + "ms");
            }
            return null;
        }
        final long elapsedMs = System.currentTimeMillis() - startMs;

        if (code == HTTP_NOT_MODIFIED && cached != null) {
            // Unchanged since we stored it: keep the body, just mark it fresh for another TTL.
            // This is the cheap path a daily user hits for most of their buckets -- a few hundred
            // bytes of headers instead of a full bucket re-download.
            if (DEBUG) {
                Log.d(TAG, "fetch " + prefix + " code=304 (revalidated, body reused) in "
                        + elapsedMs + "ms");
            }
            // Parse BEFORE writing, exactly as the 200 path below does, and for the same reason.
            // A body that fails to parse must not have its fetchedAtMs and mtime bumped: that
            // would make it un-expirable by both MAX_STALE_MS and enforceBounds' age sweep, and
            // every later refresh would re-stamp it -- permanent, silent branding loss for every
            // video in this prefix.
            final Map<String, DeArrowBranding> entries =
                    DeArrowResponseParser.parseBucket(cached.rawJson);
            final long now = System.currentTimeMillis();
            diskWrite(prefix, cached.rawJson, now, knownEtag, generation);
            return new BucketResult(entries, now);
        }
        if (code == HTTP_OK) {
            final Map<String, DeArrowBranding> entries = DeArrowResponseParser.parseBucket(body);
            if (DEBUG) {
                Log.d(TAG, "fetch " + prefix + " code=200 entries=" + entries.size()
                        + " in " + elapsedMs + "ms");
            }
            final long now = System.currentTimeMillis();
            diskWrite(prefix, body, now, newEtag, generation);
            return new BucketResult(entries, now);
        }
        if (code == HTTP_NOT_FOUND) {
            // 404 is the only genuine negative: this bucket truly has no branding. Cache it.
            if (DEBUG) {
                Log.d(TAG, "fetch " + prefix + " code=404 (negative-cached) in "
                        + elapsedMs + "ms");
            }
            final long now = System.currentTimeMillis();
            diskWrite(prefix, "{}", now, newEtag, generation);
            return new BucketResult(Collections.emptyMap(), now);
        }
        // 5xx / any other non-200 == transient. Surface as an IOException so it is retried by the
        // caller and, if it still fails, stays UNcached -- a later view re-fetches instead of
        // seeing a poisoned empty bucket for 6h.
        if (DEBUG) {
            Log.d(TAG, "fetch " + prefix + " code=" + code
                    + " (transient, NOT cached) in " + elapsedMs + "ms");
        }
        throw new IOException("transient DeArrow response: HTTP " + code);
    }

    /** Drain a response body to a string. A bucket is a few KB, so buffering it whole is fine. */
    @NonNull
    private static String readBody(final InputStream in) throws IOException {
        final ByteArrayOutputStream out = new ByteArrayOutputStream();
        final byte[] buffer = new byte[BODY_BUFFER_BYTES];
        int read;
        while ((read = in.read(buffer)) != -1) {
            out.write(buffer, 0, read);
        }
        return new String(out.toByteArray(), StandardCharsets.UTF_8);
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

    /**
     * Headers for a branding fetch.
     *
     * <p>The empty {@code Cookie} entry is load-bearing. The downloader injects the app's cookie
     * jar -- including the reCAPTCHA cookie, which it attaches unconditionally for <i>every</i>
     * host -- only when the caller did not supply a {@code Cookie} header of its own. That cookie
     * is a stable identifier, and sending it alongside a hash prefix would tie every bucket a user
     * requests to one identity, defeating the k-anonymity the prefix scheme exists to provide.
     * An empty value list marks the header as caller-supplied without putting anything on the
     * wire, because the downloader only sets a header for a value list of size one or more.</p>
     *
     * @param knownEtag the ETag of the copy already held for this bucket, or {@code null} to make
     *                  an unconditional request. When present the server may answer 304 with no
     *                  body, which is the point: a stale bucket is usually still current.
     * @return the headers for one branding request
     */
    @NonNull
    private static Map<String, List<String>> requestHeaders(@Nullable final String knownEtag) {
        final Map<String, List<String>> headers = new HashMap<>();
        headers.put("User-Agent", Collections.singletonList(USER_AGENT));
        headers.put("Cookie", Collections.emptyList());
        if (knownEtag != null && !knownEtag.isEmpty()) {
            headers.put("If-None-Match", Collections.singletonList(knownEtag));
        }
        return headers;
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
