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
import org.schabi.newpipe.extractor.ServiceList;
import org.schabi.newpipe.extractor.exceptions.ParsingException;
import org.schabi.newpipe.extractor.services.youtube.linkHandler.YoutubeStreamLinkHandlerFactory;
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
    private Disposable disposable;
    private boolean showingOriginal;

    private String originalTitle;
    private String originalThumbUrl;
    private String replacementTitle;
    private String replacementThumbUrl;

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
        originalTitle = newOriginalTitle;
        originalThumbUrl = newOriginalThumbUrl;
        replacementTitle = null;
        replacementThumbUrl = null;
        showingOriginal = false;

        // Hide the badge until gated-in (also clears any recycled state).
        if (badgeView != null) {
            badgeView.setOnClickListener(null);
            badgeView.setClickable(false);
            badgeView.setVisibility(View.GONE);
            clearBadgeTouchTarget();
        }

        final Context context = newTitleView.getContext();
        final boolean titlesOn = DeArrowSettings.isTitleReplacementEnabled(context);
        final boolean thumbsEffective = thumbnailView != null
                && PicassoHelper.getShouldLoadImages()
                && DeArrowSettings.isThumbnailReplacementEnabled(context);

        String videoId = null;
        if (url != null && (titlesOn || thumbsEffective)
                && serviceId == ServiceList.YouTube.getServiceId()) {
            try {
                videoId = YoutubeStreamLinkHandlerFactory.getInstance().getId(url);
            } catch (final ParsingException | IllegalArgumentException e) {
                videoId = null;
            }
        }

        boundVideoId = videoId;
        if (videoId == null) {
            return;
        }

        // Gated-in: show the badge faded (pending result).
        if (badgeView != null) {
            showFadedBadge();
        }

        final String requestedVideoId = videoId;
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
                    replacementTitle = titlesOn
                            ? DeArrowTitleFormatter.selectTitle(branding, autoFormat) : null;
                    replacementThumbUrl = thumbsEffective
                            ? resolveThumbUrl(requestedVideoId, branding, randomThumbFallback)
                            : null;
                    final boolean hasData =
                            replacementTitle != null || replacementThumbUrl != null;
                    if (DEBUG) {
                        Log.d(TAG, "resolve " + requestedVideoId + " in "
                                + (System.currentTimeMillis() - startMs) + "ms title="
                                + (replacementTitle != null) + " thumb="
                                + (replacementThumbUrl != null));
                    }
                    if (hasData) {
                        render();
                        if (badgeView != null) {
                            showActiveBadge();
                        }
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

    /** Render the current toggle state for whichever replacements are effective. */
    private void render() {
        if (replacementTitle != null) {
            if (showingOriginal) {
                titleView.setText(originalTitle);
            } else {
                setMarkedTitle(titleView, replacementTitle);
            }
        }
        if (replacementThumbUrl != null && thumbnailView != null) {
            if (showingOriginal) {
                PicassoHelper.loadScaledDownThumbnail(
                        thumbnailView.getContext(), originalThumbUrl).into(thumbnailView);
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
        final String idAtRender = boundVideoId;
        cancelPendingThumbnail();
        final Target thumbTarget = new Target() {
            @Override
            public void onBitmapLoaded(final Bitmap bitmap, final Picasso.LoadedFrom from) {
                pendingThumbnailTarget = null;
                // Swap only if this site is still bound to the same item and still showing DeArrow.
                if (!showingOriginal && idAtRender != null && idAtRender.equals(boundVideoId)) {
                    crossfadeInDeArrowThumbnail(target, bitmap);
                }
            }

            @Override
            public void onBitmapFailed(final Exception e, final Drawable errorDrawable) {
                // Frame unavailable (e.g. 204 not-generated yet, or a 5xx) -> keep the original.
                pendingThumbnailTarget = null;
                if (DEBUG) {
                    Log.d(TAG, "thumb " + idAtRender + " unavailable, keeping original: " + e);
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
        PicassoHelper.loadDeArrowThumbnailInto(replacementThumbUrl, thumbTarget);
    }

    /**
     * Swap the decoded DeArrow frame into the view with a short cross-fade from whatever the view
     * currently shows (normally the original thumbnail), so the change reads as a gentle switch
     * rather than an abrupt pop.
     *
     * <p>The animation lives entirely inside a {@link TransitionDrawable} -- it never touches the
     * View's alpha -- so it is safe across RecyclerView recycling: the next bind simply replaces
     * the drawable, and no view can be left stranded mid-fade. When the view has nothing to fade
     * from (no original loaded yet) the frame is set directly.</p>
     */
    private static void crossfadeInDeArrowThumbnail(final ImageView view, final Bitmap bitmap) {
        final Drawable from = view.getDrawable();
        final Drawable to = new BitmapDrawable(view.getResources(), bitmap);
        if (from == null) {
            view.setImageDrawable(to);
            return;
        }
        final TransitionDrawable transition = new TransitionDrawable(new Drawable[]{from, to});
        transition.setCrossFadeEnabled(true);
        view.setImageDrawable(transition);
        transition.startTransition(THUMBNAIL_CROSSFADE_DURATION_MS);
    }

    /** Cancel any in-flight DeArrow thumbnail load so a late frame cannot touch a recycled view. */
    private void cancelPendingThumbnail() {
        if (pendingThumbnailTarget != null) {
            PicassoHelper.cancelDeArrowThumbnail(pendingThumbnailTarget);
            pendingThumbnailTarget = null;
        }
    }

    private void toggle() {
        showingOriginal = !showingOriginal;
        render();
        showActiveBadge();
    }

    private void showFadedBadge() {
        badgeView.setVisibility(View.VISIBLE);
        badgeView.setAlpha(BADGE_FADED_ALPHA);
        badgeView.setClickable(false);
        badgeView.setOnClickListener(null);
        badgeView.setImageResource(R.drawable.ic_dearrow_badge_active);
        // Non-clickable: drop the enlarged hit area so taps here fall through to open the video.
        clearBadgeTouchTarget();
    }

    private void showActiveBadge() {
        badgeView.setVisibility(View.VISIBLE);
        badgeView.setAlpha(1f);
        badgeView.setClickable(true);
        badgeView.setImageResource(showingOriginal
                ? R.drawable.ic_dearrow_badge_off : R.drawable.ic_dearrow_badge_active);
        badgeView.setOnClickListener(v -> toggle());
        expandBadgeTouchTarget();
    }

    /**
     * Enlarge the badge's tappable area via a {@link TouchDelegate} on its parent, without changing
     * the visible badge size. Posted because the badge's position is only known after layout.
     */
    private void expandBadgeTouchTarget() {
        final ImageView badge = badgeView;
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
        final ViewParent parent = badgeView.getParent();
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
     * Cancel any in-flight fetch and clear bound state. Call this when a view site is torn down
     * (a dismissed dialog, a destroyed fragment view) so a late result cannot touch a dead view.
     */
    public void dispose() {
        if (disposable != null) {
            disposable.dispose();
            disposable = null;
        }
        cancelPendingThumbnail();
        boundVideoId = null;
    }
}
