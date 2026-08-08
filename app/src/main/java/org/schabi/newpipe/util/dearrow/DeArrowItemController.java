package org.schabi.newpipe.util.dearrow;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Rect;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.TransitionDrawable;
import android.text.Spannable;
import android.text.SpannableStringBuilder;
import android.util.Log;
import android.util.TypedValue;
import android.view.TouchDelegate;
import android.view.View;
import android.view.ViewParent;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.Nullable;
import androidx.appcompat.content.res.AppCompatResources;
import androidx.core.graphics.drawable.DrawableCompat;

import com.squareup.picasso.Picasso;
import com.squareup.picasso.Target;

import org.schabi.newpipe.BuildConfig;
import org.schabi.newpipe.R;
import org.schabi.newpipe.util.PicassoHelper;

import io.reactivex.rxjava3.android.schedulers.AndroidSchedulers;
import io.reactivex.rxjava3.disposables.Disposable;

/**
 * Applies DeArrow replacements (title and/or thumbnail) to a view site, safely across
 * RecyclerView recycling, and drives the interactive corner badge.
 *
 * <p>One instance is owned per holder or per non-recycled view site. The caller sets the original
 * title/thumbnail synchronously, then calls {@link #apply}. A single branding fetch resolves both
 * replacements; a {@code boundVideoId} guard drops results that arrive after the holder has been
 * recycled onto a different item.</p>
 *
 * <p><b>Title-only sites</b> (player, dialog, queue) call the 3-arg {@link #apply(TextView, int,
 * String)} and behave exactly as before: a replacement title (optionally star-marked) with no
 * thumbnail swap, no badge, and no toggle.</p>
 *
 * <p><b>Full sites</b> (lists, video detail) call the 7-arg overload and additionally get a
 * thumbnail swap and an always-present corner badge: faded + non-clickable while no replacement
 * exists (taps fall through to open the video), active + clickable once data resolves. Tapping the
 * badge toggles title and thumbnail together between the DeArrow and original versions.</p>
 *
 * <p>The preferences are read at bind time, so a live controller registers itself with
 * {@link DeArrowSettingsWatcher} and re-runs its bind when one of them changes -- otherwise a site
 * already on screen would keep showing a replacement the user had just switched off.</p>
 */
public final class DeArrowItemController {
    private static final float BADGE_FADED_ALPHA = 0.45f;
    // The badge icon is small (~22dp); grow its tappable area on each side so it clears the ~48dp
    // recommended touch target without changing the visible badge size (via a TouchDelegate).
    private static final float BADGE_TOUCH_EXPANSION_DP = 12f;
    // A short cross-fade when the DeArrow frame replaces the original, so the swap reads as a
    // gentle switch rather than an abrupt pop. Matches Android's standard "short" animation feel.
    private static final int THUMBNAIL_CROSSFADE_DURATION_MS = 200;

    // Diagnostic logging, compiled out of release builds (BuildConfig.DEBUG == false there).
    private static final String TAG = "DeArrowPerf";
    private static final boolean DEBUG = BuildConfig.DEBUG;

    private String boundVideoId;
    // The last apply() arguments that are not otherwise retained, so onSettingsChanged() can re-run
    // the bind against the new preferences without the caller being involved.
    private int boundServiceId;
    private String boundUrl;
    private Disposable disposable;
    private boolean showingOriginal;

    // Whether a replacement was actually applied to the view, as opposed to merely being offered
    // by the API. The badge may only claim "active" once something really changed: a replacement
    // thumbnail URL is no evidence of a thumbnail, because the DeArrow generator answers 204 No
    // Content for the vast majority of the random frames it has not produced yet (see
    // swapInDeArrowThumbnail), which fails to decode and leaves the original in place.
    private boolean titleReplaced;
    private boolean thumbnailReplaced;

    private String originalTitle;
    private String originalThumbUrl;
    private String replacementTitle;
    private String replacementThumbUrl;

    // All three are null before the first apply() and again after dispose(), which releases the
    // view site rather than letting this controller outlive it holding the whole tree.
    @Nullable
    private TextView titleView;
    @Nullable
    private ImageView thumbnailView;
    @Nullable
    private ImageView badgeView;
    // Strong reference to the in-flight DeArrow thumbnail load: Picasso holds targets weakly, so
    // without this the request would be GC'd before the frame arrives.
    @Nullable
    private Target pendingThumbnailTarget;

    /**
     * Title-only entry point (no thumbnail, no badge, no toggle). Behaves as the former
     * {@code DeArrowTitleApplier}.
     *
     * @param newTitleView the title view to update (its context drives the preference lookup)
     * @param serviceId    the item's service ID; DeArrow is YouTube-only
     * @param url          the item's URL; a null or unparseable URL keeps the original title
     */
    public void apply(final TextView newTitleView, final int serviceId, final String url) {
        final CharSequence current = newTitleView.getText();
        applyInternal(newTitleView, null, null, serviceId, url,
                current == null ? null : current.toString(), null);
    }

    /**
     * Full entry point: title + thumbnail + interactive corner badge.
     *
     * @param newTitleView        the title view (required)
     * @param newThumbnailView    the thumbnail view, or {@code null} to skip thumbnail handling
     * @param newBadgeView        the corner badge view, or {@code null} for no badge
     * @param serviceId           the item's service ID; DeArrow is YouTube-only
     * @param url                 the item's URL; a null or unparseable URL keeps the originals
     * @param newOriginalTitle    the original title (restored when the badge is toggled off)
     * @param newOriginalThumbUrl the original thumbnail URL (restored when toggled off)
     */
    public void apply(final TextView newTitleView,
                      @Nullable final ImageView newThumbnailView,
                      @Nullable final ImageView newBadgeView,
                      final int serviceId, final String url,
                      final String newOriginalTitle, final String newOriginalThumbUrl) {
        applyInternal(newTitleView, newThumbnailView, newBadgeView, serviceId, url,
                newOriginalTitle, newOriginalThumbUrl);
    }

    private void applyInternal(final TextView newTitleView,
                               @Nullable final ImageView newThumbnailView,
                               @Nullable final ImageView newBadgeView,
                               final int serviceId, final String url,
                               final String newOriginalTitle,
                               final String newOriginalThumbUrl) {
        if (disposable != null) {
            disposable.dispose();
            disposable = null;
        }
        cancelPendingThumbnail();
        titleView = newTitleView;
        thumbnailView = newThumbnailView;
        badgeView = newBadgeView;
        boundServiceId = serviceId;
        boundUrl = url;
        originalTitle = newOriginalTitle;
        originalThumbUrl = newOriginalThumbUrl;
        replacementTitle = null;
        replacementThumbUrl = null;
        showingOriginal = false;
        titleReplaced = false;
        thumbnailReplaced = false;

        // Registered whatever the outcome below, including for a site DeArrow cannot touch at all:
        // the preferences may change into a state where it can, and re-running a bind that resolves
        // to "nothing to do" costs nothing.
        DeArrowSettingsWatcher.register(this);

        // Hide the badge until gated-in (also clears any recycled state).
        hideBadge();

        final Context context = newTitleView.getContext();
        final boolean titlesOn = DeArrowSettings.isTitleReplacementEnabled(context);
        final boolean thumbsEffective = thumbnailView != null
                && PicassoHelper.getShouldLoadImages()
                && DeArrowSettings.isThumbnailReplacementEnabled(context);

        final String videoId = (titlesOn || thumbsEffective)
                ? DeArrowVideoIds.of(serviceId, url) : null;

        boundVideoId = videoId;
        if (videoId == null) {
            return;
        }

        // Gated-in: show the badge faded (pending result).
        showFadedBadge();

        final String requestedVideoId = videoId;
        // The master toggle is re-read when the fetch resolves; keep the application context for
        // that rather than capturing the view's, which would tie an Activity to the subscription.
        final Context prefContext = context.getApplicationContext();
        final boolean autoFormat = DeArrowSettings.isAutoFormatTitlesEnabled(context);
        final boolean randomThumbFallback =
                DeArrowSettings.isRandomThumbnailFallbackEnabled(context);
        final long startMs = System.currentTimeMillis();
        disposable = DeArrowService.getInstance().getBranding(requestedVideoId)
                .observeOn(AndroidSchedulers.mainThread())
                .subscribe(branding -> {
                    if (!requestedVideoId.equals(boundVideoId)) {
                        if (DEBUG) {
                            Log.d(TAG, "resolve " + requestedVideoId + " DROPPED (recycled) after "
                                    + (System.currentTimeMillis() - startMs) + "ms");
                        }
                        return;
                    }
                    // The gates above were read at bind time; the user may have switched DeArrow
                    // off while this fetch was in flight, in which case the original must be left
                    // exactly as it is. (A site that had already been replaced when the toggle
                    // flipped is reverted separately, by onSettingsChanged.)
                    if (!DeArrowSettings.isEnabled(prefContext)) {
                        if (DEBUG) {
                            Log.d(TAG, "resolve " + requestedVideoId + " DROPPED (disabled while "
                                    + "in flight)");
                        }
                        hideBadge();
                        return;
                    }
                    replacementTitle = titlesOn
                            ? DeArrowTitleFormatter.selectTitle(branding, autoFormat) : null;
                    replacementThumbUrl = thumbsEffective
                            ? resolveThumbUrl(requestedVideoId, branding, randomThumbFallback)
                            : null;
                    if (DEBUG) {
                        Log.d(TAG, "resolve " + requestedVideoId + " in "
                                + (System.currentTimeMillis() - startMs) + "ms title="
                                + (replacementTitle != null) + " thumb="
                                + (replacementThumbUrl != null));
                    }
                    if (replacementTitle != null || replacementThumbUrl != null) {
                        render();
                    }
                    // Only a replacement that actually landed may light the badge up. A title
                    // renders synchronously inside render(), so titleReplaced is already set; a
                    // thumbnail is asynchronous and most generator URLs never yield a frame, so a
                    // thumbnail-only item stays faded until onBitmapLoaded says otherwise (a
                    // cache hit resolves synchronously and is therefore already accounted for).
                    if (titleReplaced || thumbnailReplaced) {
                        showActiveBadge();
                    }
                    // else: badge stays faded + non-clickable.
                }, error -> {
                    if (DEBUG) {
                        Log.w(TAG, "resolve " + requestedVideoId + " ERROR: " + error);
                    }
                }, () -> {
                    if (DEBUG) {
                        Log.d(TAG, "resolve " + requestedVideoId + " EMPTY (no branding) after "
                                + (System.currentTimeMillis() - startMs) + "ms");
                    }
                });
    }

    @Nullable
    private static String resolveThumbUrl(final String videoId, final DeArrowBranding branding,
                                          final boolean allowRandomFallback) {
        final double time = DeArrowThumbnailSelector.selectTime(branding, allowRandomFallback);
        if (Double.isNaN(time)) {
            return null;
        }
        return DeArrowThumbnailUrl.build(videoId, time);
    }

    /**
     * Render the current toggle state for whichever replacements are effective. Null-safe against
     * a view site released by {@link #dispose}, so a late callback cannot touch a dead view.
     */
    private void render() {
        final TextView title = titleView;
        if (replacementTitle != null && title != null) {
            if (showingOriginal) {
                title.setText(originalTitle);
            } else {
                setMarkedTitle(title, replacementTitle);
                titleReplaced = true;
            }
        }
        final ImageView thumb = thumbnailView;
        if (replacementThumbUrl != null && thumb != null) {
            if (showingOriginal) {
                // Restoring: stop the DeArrow load from landing after the toggle, and keep the
                // frame on screen until the original has decoded rather than blanking the view.
                cancelPendingThumbnail();
                PicassoHelper.loadScaledDownThumbnailKeepingCurrent(thumb, originalThumbUrl);
            } else {
                swapInDeArrowThumbnail();
            }
        }
    }

    /**
     * Swap in the DeArrow thumbnail without ever disturbing the original on failure.
     *
     * <p>The DeArrow thumbnail generator returns {@code 204 No Content} ("not generated yet") for
     * frames it has not produced server-side. A 204 is an HTTP <em>success</em> with an empty body,
     * so loading the generator URL into the live {@link ImageView} -- or warming it with
     * {@code fetch()} and swapping on success -- treats the 204 as "loaded": it cancels the
     * still-in-flight original load (branding now resolves from the on-disk cache in a few ms,
     * usually before the original image) and then renders nothing, leaving the view blank. Instead
     * we load into an off-view {@link Target}, which never touches the live view and only delivers
     * a decoded {@link Bitmap} for a real frame; a 204/empty body (or any error) fails to decode
     * and routes to {@link Target#onBitmapFailed}, so the holder's original -- loaded
     * independently -- is left untouched.</p>
     */
    private void swapInDeArrowThumbnail() {
        final ImageView target = thumbnailView;
        final String url = replacementThumbUrl;
        if (target == null || url == null) {
            return;
        }
        final String idAtRender = boundVideoId;
        cancelPendingThumbnail();
        final Target thumbTarget = new Target() {
            @Override
            public void onBitmapLoaded(final Bitmap bitmap, final Picasso.LoadedFrom from) {
                pendingThumbnailTarget = null;
                // Swap only if this site is still bound to the same item and still showing DeArrow.
                if (!showingOriginal && idAtRender != null && idAtRender.equals(boundVideoId)) {
                    thumbnailReplaced = true;
                    showDeArrowThumbnail(target, bitmap);
                    // A frame really landed, so the badge has something to toggle back from.
                    showActiveBadge();
                }
            }

            @Override
            public void onBitmapFailed(final Exception e, final Drawable errorDrawable) {
                // Frame unavailable (e.g. 204 not-generated yet, or a 5xx) -> keep the original.
                pendingThumbnailTarget = null;
                if (DEBUG) {
                    Log.d(TAG, "thumb " + idAtRender + " unavailable, keeping original: " + e);
                }
                // Nothing was replaced after all: drop the badge back to faded + non-clickable
                // instead of letting it claim this item was de-clickbaited. Once any replacement
                // has landed the badge stays active -- a failure on a later toggle back to DeArrow
                // must not strand the user with no way to return.
                if (!titleReplaced && !thumbnailReplaced
                        && idAtRender != null && idAtRender.equals(boundVideoId)) {
                    showFadedBadge();
                }
            }

            @Override
            public void onPrepareLoad(final Drawable placeHolderDrawable) {
                // Deliberately a no-op: never clear the live view, so the original stays visible
                // until (and unless) a real DeArrow frame is decoded.
            }
        };
        // Hold a strong reference (Picasso keeps targets weakly) before kicking off the load.
        pendingThumbnailTarget = thumbTarget;
        PicassoHelper.loadDeArrowThumbnailInto(url, thumbTarget);
    }

    /**
     * Show the decoded DeArrow frame in the view with a short cross-fade from whatever the view
     * currently shows (normally the original thumbnail), so the change reads as a gentle switch
     * rather than an abrupt pop.
     *
     * <p>The animation lives entirely inside a {@link TransitionDrawable} -- it never touches the
     * View's alpha -- so it is safe across RecyclerView recycling: the next bind simply replaces
     * the drawable, and no view can be left stranded mid-fade. When the view has nothing to fade
     * from yet (no original loaded) the frame is set directly.</p>
     *
     * <p>The transition is pinned to the incoming frame's intrinsic size via
     * {@link FixedSizeTransitionDrawable}. A plain {@link TransitionDrawable} is a
     * {@link android.graphics.drawable.LayerDrawable}, whose intrinsic size is the per-dimension
     * max of its layers; when the original and the frame differ in aspect ratio (e.g. a 4:3
     * {@code hqdefault}/{@code sddefault} original vs a 16:9 generated frame) that synthetic size
     * matches neither image, so the ImageView's {@code scaleType} ({@code fitCenter} on the
     * video-detail header) would letterbox and stretch the result. Pinning the size to the frame
     * lets {@code scaleType} fit it from its true dimensions; the outgoing image is briefly drawn
     * to the same bounds while it fades out.</p>
     */
    private static void showDeArrowThumbnail(final ImageView view, final Bitmap bitmap) {
        // Claim the view first. The caller's original thumbnail load was started directly on this
        // view and is deliberately never associated with the off-view DeArrow Target, so Picasso
        // will not cancel it for us -- and when it completes it overwrites whatever is on the view
        // without checking. See PicassoHelper#cancelInto.
        PicassoHelper.cancelInto(view);
        final Drawable from = view.getDrawable();
        final Drawable to = new BitmapDrawable(view.getResources(), bitmap);
        if (from == null) {
            view.setImageDrawable(to);
            return;
        }
        final TransitionDrawable transition = new FixedSizeTransitionDrawable(
                new Drawable[]{from, to}, to.getIntrinsicWidth(), to.getIntrinsicHeight());
        transition.setCrossFadeEnabled(true);
        view.setImageDrawable(transition);
        transition.startTransition(THUMBNAIL_CROSSFADE_DURATION_MS);
        // A TransitionDrawable never releases layer 0 once the fade ends (unlike PicassoDrawable),
        // so leaving it in place would pin both bitmaps for the life of the binding -- expensive
        // in a grid against Picasso's default memory cache -- and would nest if the user toggled
        // again mid-fade. Once the fade is spent, swap in the frame on its own. The check keeps
        // this honest: a rebind or a toggle since then owns the view now and must not be undone.
        // Nothing here touches the controller, so a disposed one cannot be resurrected.
        view.postDelayed(() -> {
            if (view.getDrawable() == transition) {
                view.setImageDrawable(to);
            }
        }, THUMBNAIL_CROSSFADE_DURATION_MS);
    }

    /**
     * A {@link TransitionDrawable} that reports a fixed intrinsic size (the incoming frame's)
     * rather than the per-dimension max of its layers, so the ImageView's {@code scaleType} fits
     * the result from the new frame's true aspect ratio even when the outgoing image has a
     * different aspect. See {@link #showDeArrowThumbnail}.
     */
    private static final class FixedSizeTransitionDrawable extends TransitionDrawable {
        private final int intrinsicWidth;
        private final int intrinsicHeight;

        FixedSizeTransitionDrawable(final Drawable[] layers,
                                    final int intrinsicWidth, final int intrinsicHeight) {
            super(layers);
            this.intrinsicWidth = intrinsicWidth;
            this.intrinsicHeight = intrinsicHeight;
        }

        @Override
        public int getIntrinsicWidth() {
            return intrinsicWidth;
        }

        @Override
        public int getIntrinsicHeight() {
            return intrinsicHeight;
        }
    }

    /** Cancel any in-flight DeArrow thumbnail load so a late frame cannot touch a recycled view. */
    private void cancelPendingThumbnail() {
        if (pendingThumbnailTarget != null) {
            PicassoHelper.cancelDeArrowThumbnail(pendingThumbnailTarget);
            pendingThumbnailTarget = null;
        }
    }

    /**
     * Re-evaluate this site against the current DeArrow preferences, called by
     * {@link DeArrowSettingsWatcher} when the user changes one.
     *
     * <p>The originals are restored first because {@link #applyInternal} cannot do it: it is
     * normally called straight after the caller has set the original title and thumbnail itself, so
     * it only ever writes replacements over them. Reaching it with a replacement still on the view
     * -- which is the whole point here -- would leave that replacement in place whenever the new
     * preferences say there should be none.</p>
     */
    void onSettingsChanged() {
        final TextView title = titleView;
        if (title == null) {
            // Disposed, or never applied: there is no view site to re-evaluate.
            return;
        }
        restoreOriginals();
        applyInternal(title, thumbnailView, badgeView, boundServiceId, boundUrl,
                originalTitle, originalThumbUrl);
    }

    /**
     * Put the original title and thumbnail back on the view, undoing whatever this controller
     * replaced. A site that is already showing the originals -- never replaced, or toggled back by
     * the badge -- is left alone rather than reloading an image it is already displaying.
     */
    private void restoreOriginals() {
        if (showingOriginal) {
            return;
        }
        final TextView title = titleView;
        if (title != null && titleReplaced && originalTitle != null) {
            title.setText(originalTitle);
        }
        final ImageView thumb = thumbnailView;
        if (thumb != null && thumbnailReplaced) {
            // Stop a DeArrow frame that is still in flight from landing after the revert.
            cancelPendingThumbnail();
            PicassoHelper.loadScaledDownThumbnailKeepingCurrent(thumb, originalThumbUrl);
        }
    }

    private void toggle() {
        showingOriginal = !showingOriginal;
        render();
        showActiveBadge();
    }

    private void showFadedBadge() {
        final ImageView badge = badgeView;
        if (badge == null) {
            return;
        }
        badge.setVisibility(View.VISIBLE);
        badge.setAlpha(BADGE_FADED_ALPHA);
        badge.setImageResource(R.drawable.ic_dearrow_badge_active);
        // Non-clickable: drop the enlarged hit area so taps here fall through to open the video.
        releaseBadge();
    }

    private void showActiveBadge() {
        final ImageView badge = badgeView;
        if (badge == null) {
            return;
        }
        badge.setVisibility(View.VISIBLE);
        badge.setAlpha(1f);
        badge.setClickable(true);
        badge.setImageResource(showingOriginal
                ? R.drawable.ic_dearrow_badge_off : R.drawable.ic_dearrow_badge_active);
        badge.setOnClickListener(v -> toggle());
        expandBadgeTouchTarget();
    }

    /** Hide the badge and drop everything installed on it (see {@link #releaseBadge}). */
    private void hideBadge() {
        final ImageView badge = badgeView;
        if (badge == null) {
            return;
        }
        badge.setVisibility(View.GONE);
        releaseBadge();
    }

    /**
     * Drop everything this controller installed on the badge -- the click listener, clickability
     * and the enlarged hit area -- without touching its visibility.
     *
     * <p>The listener holds this controller, which in turn holds the whole view site, so it must
     * not outlive the binding. Clearing clickability also disarms the runnable posted by
     * {@link #expandBadgeTouchTarget}, which bails on a badge that is no longer clickable and so
     * cannot reinstate the hit area behind our back.</p>
     */
    private void releaseBadge() {
        final ImageView badge = badgeView;
        if (badge == null) {
            return;
        }
        badge.setOnClickListener(null);
        badge.setClickable(false);
        clearBadgeTouchTarget();
    }

    /**
     * Enlarge the badge's tappable area via a {@link TouchDelegate} on its parent, without changing
     * the visible badge size. Posted because the badge's position is only known after layout.
     */
    private void expandBadgeTouchTarget() {
        final ImageView badge = badgeView;
        if (badge == null) {
            return;
        }
        final ViewParent rawParent = badge.getParent();
        if (!(rawParent instanceof View)) {
            return;
        }
        final View parent = (View) rawParent;
        parent.post(() -> {
            // Bail if recycled to a different parent or no longer clickable since this was posted.
            if (badge.getParent() != parent || !badge.isClickable()) {
                return;
            }
            final Rect hitRect = new Rect();
            badge.getHitRect(hitRect);
            final int expand = Math.round(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP,
                    BADGE_TOUCH_EXPANSION_DP, parent.getResources().getDisplayMetrics()));
            hitRect.inset(-expand, -expand);
            parent.setTouchDelegate(new TouchDelegate(hitRect, badge));
        });
    }

    /** Remove any enlarged badge hit area so the corner reverts to the underlying view's clicks. */
    private void clearBadgeTouchTarget() {
        final ImageView badge = badgeView;
        if (badge == null) {
            return;
        }
        final ViewParent parent = badge.getParent();
        if (parent instanceof View) {
            ((View) parent).setTouchDelegate(null);
        }
    }

    private static void setMarkedTitle(final TextView view, final String replacement) {
        if (DeArrowSettings.isMarkReplacedTitlesEnabled(view.getContext())) {
            final CharSequence marked = buildMarkedTitle(view, replacement);
            if (marked != null) {
                view.setText(marked);
                return;
            }
        }
        view.setText(replacement);
    }

    private static CharSequence buildMarkedTitle(final TextView view, final String replacement) {
        final Drawable icon = AppCompatResources.getDrawable(
                view.getContext(), R.drawable.ic_stars);
        if (icon == null) {
            return null;
        }
        final Drawable marker = icon.mutate();
        final int size = Math.round(view.getTextSize());
        marker.setBounds(0, 0, size, size);
        DrawableCompat.setTint(marker, view.getCurrentTextColor());

        final SpannableStringBuilder builder = new SpannableStringBuilder();
        builder.append(" ");
        builder.setSpan(new CenteredImageSpan(marker), 0, 1, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
        builder.append(" ").append(replacement);
        return builder;
    }

    /**
     * Cancel any in-flight fetch and release the bound view site. Call this when a view site is
     * torn down (a dismissed dialog, a destroyed fragment view, a recycled holder) so a late
     * result cannot touch a dead view.
     *
     * <p>The view references are dropped too, because this controller routinely outlives the views
     * it was given: it is a {@code final} field of a Fragment or a Player that survives its own
     * view tree (bottom-sheet player, back stack, configuration change), and a retained title view
     * reaches the whole destroyed hierarchy through {@code getParent()}. Callers always re-supply
     * their views on the next {@link #apply}, so there is nothing to preserve here.</p>
     */
    public void dispose() {
        if (disposable != null) {
            disposable.dispose();
            disposable = null;
        }
        cancelPendingThumbnail();
        releaseBadge();
        DeArrowSettingsWatcher.unregister(this);
        boundVideoId = null;
        boundUrl = null;
        titleView = null;
        thumbnailView = null;
        badgeView = null;
        originalTitle = null;
        originalThumbUrl = null;
        replacementTitle = null;
        replacementThumbUrl = null;
        titleReplaced = false;
        thumbnailReplaced = false;
        showingOriginal = false;
    }
}
