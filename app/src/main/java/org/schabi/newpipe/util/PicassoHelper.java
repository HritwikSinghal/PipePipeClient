package org.schabi.newpipe.util;

import static org.schabi.newpipe.extractor.utils.Utils.isBlank;

import android.annotation.SuppressLint;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.drawable.Drawable;

import android.util.Log;
import android.widget.ImageView;

import com.squareup.picasso.Cache;
import com.squareup.picasso.LruCache;
import com.squareup.picasso.OkHttp3Downloader;
import com.squareup.picasso.Picasso;
import com.squareup.picasso.RequestCreator;
import com.squareup.picasso.Target;
import com.squareup.picasso.Transformation;

import org.schabi.newpipe.BuildConfig;
import org.schabi.newpipe.R;

import java.io.File;
import java.io.IOException;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

import okhttp3.OkHttpClient;

public final class PicassoHelper {
    public static final String PLAYER_THUMBNAIL_TAG = "PICASSO_PLAYER_THUMBNAIL_TAG";
    private static final String PLAYER_THUMBNAIL_TRANSFORMATION_KEY
            = "PICASSO_PLAYER_THUMBNAIL_TRANSFORMATION_KEY";

    private PicassoHelper() {
    }

    private static Cache picassoCache;
    private static OkHttpClient picassoDownloaderClient;

    // suppress because terminate() is called in App.onTerminate(), preventing leaks
    @SuppressLint("StaticFieldLeak")
    private static Picasso picassoInstance;

    private static boolean shouldLoadImages;

    private static final Transformation transformation = new Transformation() {
        @Override
        public Bitmap transform(final Bitmap source) {
            final float notificationThumbnailWidth = Math.min(
                    600,
                    source.getWidth());

            final Bitmap result = Bitmap.createScaledBitmap(
                    source,
                    (int) notificationThumbnailWidth,
                    (int) (source.getHeight()
                            / (source.getWidth() / notificationThumbnailWidth)),
                    true);

            if (result == source) {
                // create a new mutable bitmap to prevent strange crashes on some
                // devices (see #4638)
                try {
                    final Bitmap copied = Bitmap.createScaledBitmap(
                            source,
                            (int) notificationThumbnailWidth - 1,
                            (int) (source.getHeight() / (source.getWidth()
                                    / (notificationThumbnailWidth - 1))),
                            true);
                    source.recycle();
                    return copied;
                } catch (final IllegalArgumentException e) {
                    Log.e("PicassoHelper", "Failed to create scaled down copied bitmap", e);
                    return result;
                }

            } else {
                source.recycle();
                return result;
            }
        }

        @Override
        public String key() {
            return PLAYER_THUMBNAIL_TRANSFORMATION_KEY;
        }
    };

    public static void init(final Context context) {
        picassoCache = new LruCache(512 * 1024 * 1024);
        picassoDownloaderClient = new OkHttpClient.Builder()
                .cache(new okhttp3.Cache(new File(context.getExternalCacheDir(), "picasso"),
                        512 * 1024 * 1024))
                // this should already be the default timeout in OkHttp3, but just to be sure...
                .callTimeout(15, TimeUnit.SECONDS)
                .build();

        picassoInstance = new Picasso.Builder(context)
//                .memoryCache(picassoCache) // memory cache
                .downloader(new OkHttp3Downloader(picassoDownloaderClient)) // disk cache
                .defaultBitmapConfig(Bitmap.Config.RGB_565)
                .build();

        if (BuildConfig.DEBUG) {
            // Diagnostic: show the per-image source ribbon (green=memory, blue=disk, red=network)
            // so DeArrow thumbnail cache hits vs cold generations are visible while scrolling.
            picassoInstance.setIndicatorsEnabled(true);
        }
    }

    public static void terminate() {
        picassoCache = null;
        picassoDownloaderClient = null;

        if (picassoInstance != null) {
            picassoInstance.shutdown();
            picassoInstance = null;
        }
    }

    public static void clearCache(final Context context) throws IOException {
        picassoInstance.shutdown();
        picassoCache.clear(); // clear memory cache
        final okhttp3.Cache diskCache = picassoDownloaderClient.cache();
        if (diskCache != null) {
            diskCache.delete(); // clear disk cache
        }
        init(context);
    }

    public static void cancelTag(final Object tag) {
        picassoInstance.cancelTag(tag);
    }

    public static void setIndicatorsEnabled(final boolean enabled) {
        picassoInstance.setIndicatorsEnabled(enabled); // useful for debugging
    }

    public static void setShouldLoadImages(final boolean shouldLoadImages) {
        PicassoHelper.shouldLoadImages = shouldLoadImages;
    }

    public static boolean getShouldLoadImages() {
        return shouldLoadImages;
    }


    public static RequestCreator loadAvatar(final String url) {
        return loadImageDefault(url, R.drawable.buddy).transform(transformation);
    }

    public static RequestCreator loadThumbnail(final String url) {
        return loadImageDefault(url, R.drawable.dummy_thumbnail).transform(transformation);
    }

    public static RequestCreator loadBanner(final String url) {
        return loadImageDefault(url, R.drawable.channel_banner);
    }

    public static RequestCreator loadPlaylistThumbnail(final String url) {
        return loadImageDefault(url, R.drawable.dummy_thumbnail_playlist).transform(transformation);
    }

    public static RequestCreator loadSeekbarThumbnailPreview(final String url) {
        return picassoInstance.load(url); ///should not transform, see https://github.com/InfinityLoop1308/PipePipe/issues/215
    }

    public static RequestCreator loadScaledDownThumbnail(final Context context, final String url){ // reserve for compatibility
        return loadScaledDownThumbnail(context, url, false);
    }

    public static RequestCreator loadScaledDownThumbnail(final Context context, final String url,
                                                         final boolean shouldSetTag) {
        // scale down the notification thumbnail for performance
        final RequestCreator requestCreator = loadImageDefault(url, R.drawable.dummy_thumbnail)
                .transform(transformation);
        return shouldSetTag ? requestCreator.tag(PLAYER_THUMBNAIL_TAG) : requestCreator;
    }

    /**
     * Load a scaled-down thumbnail into a view that already shows an image, keeping that image on
     * screen for the whole load instead of blanking the view.
     *
     * <p>{@link #loadImageDefault} deliberately sets no placeholder for a real URL, so on a memory
     * cache miss Picasso calls {@code setPlaceholder(target, null)} -- i.e.
     * {@code setImageDrawable(null)} -- and the view stays empty until the network load returns.
     * That is the right default when there is nothing worth preserving, but it makes the DeArrow
     * badge's "restore the original" toggle flash a blank thumbnail. Handing Picasso the view's
     * current drawable as the placeholder keeps the outgoing image visible instead.</p>
     *
     * <p>The placeholder is only supplied when {@link #loadImageDefault} leaves that slot free: for
     * a blank URL (or with image loading off) it installs the dummy-thumbnail resource itself, and
     * Picasso rejects a second placeholder with an {@link IllegalStateException}.</p>
     *
     * @param view the target view, whose current drawable is preserved during the load
     * @param url  the thumbnail URL
     */
    public static void loadScaledDownThumbnailKeepingCurrent(final ImageView view,
                                                             final String url) {
        final RequestCreator request = loadScaledDownThumbnail(view.getContext(), url);
        final Drawable current = view.getDrawable();
        if (current != null && shouldLoadImages && !isBlank(url)) {
            request.placeholder(current);
        }
        request.into(view);
    }

    /**
     * Load a DeArrow replacement thumbnail into an off-view {@link Target} that decodes the frame
     * before it is shown, so the visible {@link android.widget.ImageView} is never disturbed unless
     * a real frame actually arrives.
     *
     * <p>The DeArrow thumbnail generator returns {@code 204 No Content} ("not generated yet") for
     * frames it has not produced server-side. A 204 is an HTTP <em>success</em> with an empty body,
     * so a {@code fetch()} reports {@code onSuccess} and a subsequent {@code into(view)} would
     * cancel the in-flight original load on that view and then render nothing -- leaving the view
     * permanently blank. Loading into a {@link Target} instead (a) never targets the live view, so
     * the original load is left running, and (b) delivers a decoded {@link Bitmap} to
     * {@code onBitmapLoaded} only for a real image; a 204/empty body (or any error) fails to decode
     * and routes to {@code onBitmapFailed}, letting the caller keep the original. The
     * {@code transform} matches {@link #prefetchDeArrowThumbnail} so a prefetched frame resolves
     * from cache.</p>
     *
     * <p>The flip side of (a) is that Picasso does not know the two requests are related, so a
     * caller that goes on to draw the frame on the view must cancel the original itself -- see
     * {@link #cancelInto}.</p>
     *
     * @param url    the DeArrow thumbnail-generator URL
     * @param target the off-view target that receives the decoded frame; the caller must hold a
     *               strong reference to it (Picasso keeps targets weakly)
     */
    public static void loadDeArrowThumbnailInto(final String url, final Target target) {
        picassoInstance.load(url)
                .transform(transformation)
                .into(target);
    }

    /**
     * Cancel an in-flight DeArrow thumbnail load for a target (e.g. on recycle/teardown).
     *
     * @param target the target previously passed to {@link #loadDeArrowThumbnailInto}
     */
    public static void cancelDeArrowThumbnail(final Target target) {
        picassoInstance.cancelRequest(target);
    }

    /**
     * Cancel any in-flight Picasso request that targets an {@link ImageView}, so a load started
     * elsewhere cannot overwrite a drawable the caller is about to set on that view.
     *
     * <p>Needed by the DeArrow thumbnail swap. {@link #loadDeArrowThumbnailInto} loads through an
     * off-view {@link Target} precisely so the view's own load is left alone, which means Picasso
     * never associates the two requests and never auto-cancels the original. That original
     * completes by calling {@code setImageDrawable} unconditionally -- it does not check whether
     * the drawable changed underneath it -- so a DeArrow frame applied first (prefetched frames
     * come from the memory cache and are delivered synchronously) would be silently painted over
     * a few hundred milliseconds later, while the badge still reads "active". Cancelling costs
     * nothing here: the replacement bitmap is already decoded, and the next bind re-issues the
     * original load.</p>
     *
     * @param view the view whose pending request should be dropped; a view with no request in
     *             flight is a no-op
     */
    public static void cancelInto(final ImageView view) {
        picassoInstance.cancelRequest(view);
    }

    /**
     * Warm Picasso's cache with a DeArrow thumbnail ahead of binding, without a target view.
     *
     * <p>Uses the same {@code transform} as {@link #loadDeArrowThumbnailInto} so the cache key
     * matches: a later {@code loadDeArrowThumbnailInto(url, target)} then resolves from cache with
     * no network round-trip and no cold server-side generation. No-op when images are disabled or
     * the URL is blank.</p>
     *
     * @param url the DeArrow thumbnail-generator URL
     */
    public static void prefetchDeArrowThumbnail(final String url) {
        if (!shouldLoadImages || isBlank(url)) {
            return;
        }
        picassoInstance.load(url)
                .transform(transformation)
                .fetch();
    }

    public static RequestCreator loadOrigin(final String url) {
        return loadImageDefault(url, R.drawable.dummy_thumbnail_playlist);
    }


    public static void loadNotificationIcon(final String url,
                                            final Consumer<Bitmap> bitmapConsumer) {
        loadImageDefault(url, R.drawable.ic_pipepipe)
                .into(new Target() {
                    @Override
                    public void onBitmapLoaded(final Bitmap bitmap, final Picasso.LoadedFrom from) {
                        bitmapConsumer.accept(bitmap);
                    }

                    @Override
                    public void onBitmapFailed(final Exception e, final Drawable errorDrawable) {
                        bitmapConsumer.accept(null);
                    }

                    @Override
                    public void onPrepareLoad(final Drawable placeHolderDrawable) {
                        // Nothing to do
                    }
                });
    }


    private static RequestCreator loadImageDefault(final String url, final int placeholderResId) {
        if (!shouldLoadImages || isBlank(url)) {
            return picassoInstance
                    .load((String) null)
                    .placeholder(placeholderResId) // show placeholder when no image should load
                    .error(placeholderResId);
        } else {
            return picassoInstance
                    .load(url)
                    .error(placeholderResId); // don't show placeholder while loading, only on error
        }
    }
}
