package org.schabi.newpipe.info_list

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.InlineTextContent
import androidx.compose.foundation.text.appendInlineContent
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.Placeholder
import androidx.compose.ui.text.PlaceholderVerticalAlign
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.squareup.picasso.Picasso
import com.squareup.picasso.Target
import io.reactivex.rxjava3.android.schedulers.AndroidSchedulers
import org.schabi.newpipe.R
import org.schabi.newpipe.util.PicassoHelper
import org.schabi.newpipe.util.dearrow.DeArrowService
import org.schabi.newpipe.util.dearrow.DeArrowSettings
import org.schabi.newpipe.util.dearrow.DeArrowSettingsWatcher
import org.schabi.newpipe.util.dearrow.DeArrowThumbnailSelector
import org.schabi.newpipe.util.dearrow.DeArrowThumbnailUrl
import org.schabi.newpipe.util.dearrow.DeArrowTitleFormatter
import org.schabi.newpipe.util.dearrow.DeArrowVideoIds

/**
 * DeArrow decoration for the experimental Compose item UI.
 *
 * <p>The Compose holders ([org.schabi.newpipe.info_list.holder.ComposeInfoItemHolder] and
 * [org.schabi.newpipe.local.holder.ComposeLocalItemHolder]) replace every View-based holder when
 * "use experimental new UI" is on, which took DeArrow off the channel page, search, related videos,
 * remote playlists, history and local playlists in one step -- those surfaces all route through
 * [InfoListAdapter] / [org.schabi.newpipe.local.LocalItemListAdapter]. Only the subscription feed
 * kept working, because it is built on a Groupie adapter that the flag does not touch.</p>
 *
 * <p>This is the Compose equivalent of [org.schabi.newpipe.util.dearrow.DeArrowItemController], and
 * deliberately keeps its semantics rather than inventing new ones:</p>
 *
 *  - the original title and thumbnail are what the item renders until a replacement really lands;
 *  - a DeArrow frame is decoded off-view first, so the generator's `204 No Content` ("frame not
 *    produced yet") leaves the original thumbnail alone instead of blanking the row;
 *  - the corner badge is faded and non-clickable while nothing has landed, so taps fall through to
 *    opening the video, and only becomes tappable once there is something to toggle back from;
 *  - preference changes reach rows that are already on screen.
 *
 * <p>What Compose does differently is where the state lives. The View controller has to hold its
 * own views and re-run its bind by hand; here a preference change bumps
 * [DeArrowSettingsSignal] and every composition that read it recomposes, rebuilding the state and
 * with it the branding subscription. There is nothing to restore, because the originals were never
 * overwritten -- they are simply what the composable draws when there is no replacement.</p>
 */

/** Recomposition counter bumped on every DeArrow preference change; see [DeArrowSettingsWatcher]. */
private object DeArrowSettingsSignal {
    private val generation = mutableIntStateOf(0)

    init {
        // The watcher fires its observers on the main thread, so this is a plain snapshot write.
        // Registration is for the life of the process, which is correct: the object holds no
        // context, view or composition, only an Int.
        DeArrowSettingsWatcher.addObserver { generation.intValue++ }
    }

    /** Read from a composition to re-run it whenever a DeArrow preference changes. */
    val current: Int
        get() = generation.intValue
}

/**
 * Per-item DeArrow state: what the API offered, what actually landed, and which of the two the
 * badge is currently showing.
 */
@Stable
class DeArrowItemState internal constructor(
    internal val videoId: String?,
    internal val titlesEnabled: Boolean,
    internal val thumbnailsEnabled: Boolean,
    internal val autoFormatTitles: Boolean,
    internal val markReplacedTitles: Boolean,
    internal val randomThumbnailFallback: Boolean
) {
    /** The replacement title once resolved, independent of the badge toggle. */
    internal var replacementTitle by mutableStateOf<String?>(null)

    /**
     * The DeArrow frame URL once resolved. Not evidence of a frame: the generator answers 204 for
     * the majority of the random frames it has not produced yet, which never decodes.
     */
    internal var replacementThumbnailUrl by mutableStateOf<String?>(null)

    /** Sticky once a frame has really decoded, so a toggle back to DeArrow cannot strand the user. */
    internal var thumbnailLanded by mutableStateOf(false)

    /** Whether the badge has been tapped back to the original title and thumbnail. */
    var showingOriginal by mutableStateOf(false)

    /** Whether DeArrow applies here at all: a YouTube video with at least one replacement on. */
    val gatedIn: Boolean get() = videoId != null

    /** Whether a replacement really landed, so the badge may light up and offer the toggle. */
    val hasReplacement: Boolean get() = replacementTitle != null || thumbnailLanded

    /** The replacement title to draw, or `null` to keep the item's own. */
    val effectiveTitle: String? get() = if (showingOriginal) null else replacementTitle

    /** The DeArrow frame to attempt right now, or `null` to show the original. */
    val effectiveThumbnailUrl: String? get() = if (showingOriginal) null else replacementThumbnailUrl
}

/**
 * Resolve DeArrow branding for one item, keyed by its identity and by the current preferences.
 *
 * Pass `serviceId = -1` / `url = null` for anything that is not a video (a playlist or channel row):
 * the result is an inert state that fetches nothing and shows no badge.
 */
@Composable
fun rememberDeArrowItemState(serviceId: Int, url: String?): DeArrowItemState {
    val context = LocalContext.current
    // Subscribes this composition to preference changes: a toggle rebuilds the state below, and
    // with it the branding subscription, for every item currently on screen.
    val generation = DeArrowSettingsSignal.current

    val state = remember(serviceId, url, generation) {
        val titles = DeArrowSettings.isTitleReplacementEnabled(context)
        val thumbnails = DeArrowSettings.isThumbnailReplacementEnabled(context) &&
            PicassoHelper.getShouldLoadImages()
        DeArrowItemState(
            videoId = if (titles || thumbnails) DeArrowVideoIds.of(serviceId, url) else null,
            titlesEnabled = titles,
            thumbnailsEnabled = thumbnails,
            autoFormatTitles = DeArrowSettings.isAutoFormatTitlesEnabled(context),
            markReplacedTitles = DeArrowSettings.isMarkReplacedTitlesEnabled(context),
            randomThumbnailFallback = DeArrowSettings.isRandomThumbnailFallbackEnabled(context)
        )
    }

    DisposableEffect(state) {
        val videoId = state.videoId
        if (videoId == null) {
            return@DisposableEffect onDispose { }
        }
        // getBranding is a cold Maybe that shares one bucket fetch across every subscriber, so a
        // screenful of rows from the same hash prefix costs a single request. Errors and misses
        // both resolve to "no replacement", which is exactly the original the row already shows.
        val disposable = DeArrowService.getInstance().getBranding(videoId)
            .observeOn(AndroidSchedulers.mainThread())
            .subscribe({ branding ->
                if (state.titlesEnabled) {
                    state.replacementTitle =
                        DeArrowTitleFormatter.selectTitle(branding, state.autoFormatTitles)
                }
                if (state.thumbnailsEnabled) {
                    val time = DeArrowThumbnailSelector
                        .selectTime(branding, state.randomThumbnailFallback)
                    if (!time.isNaN()) {
                        state.replacementThumbnailUrl = DeArrowThumbnailUrl.build(videoId, time)
                    }
                }
            }, { /* Decorative: on any error the row keeps the original title and thumbnail. */ })
        onDispose { disposable.dispose() }
    }

    return state
}

/**
 * Decode the DeArrow frame for [url] without ever touching the view that shows the original.
 *
 * Returns `null` until (and unless) a real frame decodes, so the caller keeps drawing the original
 * on a 204, a 5xx or a cancelled load. The target is captured by `onDispose` and therefore held
 * strongly for the life of the effect -- Picasso keeps targets weakly and would otherwise let this
 * one be collected mid-flight.
 */
@Composable
internal fun rememberDeArrowThumbnail(url: String?): Bitmap? {
    // Keyed for the same reason as rememberPicassoBitmap: a recycled holder reuses its composition,
    // so an unkeyed remember would carry the previous item's frame into the new item's first frame.
    var bitmap by remember(url) { mutableStateOf<Bitmap?>(null) }

    DisposableEffect(url) {
        bitmap = null
        if (url == null) {
            return@DisposableEffect onDispose { }
        }
        val target = object : Target {
            override fun onBitmapLoaded(loadedBitmap: Bitmap, from: Picasso.LoadedFrom) {
                bitmap = loadedBitmap
            }

            override fun onBitmapFailed(
                e: Exception?,
                errorDrawable: android.graphics.drawable.Drawable?
            ) {
                bitmap = null
            }

            override fun onPrepareLoad(placeHolderDrawable: android.graphics.drawable.Drawable?) {
            }
        }
        PicassoHelper.loadDeArrowThumbnailInto(url, target)
        onDispose { PicassoHelper.cancelTarget(target) }
    }

    return bitmap
}

/** Mark a decoded frame as landed, so the badge stays tappable across a toggle back and forth. */
@Composable
internal fun TrackDeArrowThumbnail(state: DeArrowItemState, bitmap: Bitmap?) {
    LaunchedEffect(state, bitmap) {
        if (bitmap != null) {
            state.thumbnailLanded = true
        }
    }
}

/**
 * The interactive DeArrow corner badge, mirroring the View sites': 22dp at the thumbnail's
 * top-start, faded and non-clickable until a replacement lands, then lit and toggling both the
 * title and the thumbnail. The tappable area is grown to [BADGE_TOUCH_TARGET] without changing the
 * visible size, which is what the View sites use a `TouchDelegate` for.
 */
@Composable
internal fun DeArrowBadge(state: DeArrowItemState, modifier: Modifier = Modifier) {
    val active = state.hasReplacement
    Box(
        modifier = modifier
            .size(BADGE_TOUCH_TARGET)
            // Non-clickable while faded, so a tap there opens the video like the rest of the row.
            .then(
                if (active) {
                    Modifier.clickable { state.showingOriginal = !state.showingOriginal }
                } else {
                    Modifier
                }
            )
    ) {
        Image(
            painter = painterResource(
                if (state.showingOriginal) {
                    R.drawable.ic_dearrow_badge_off
                } else {
                    R.drawable.ic_dearrow_badge_active
                }
            ),
            contentDescription = stringResource(R.string.dearrow),
            contentScale = ContentScale.Fit,
            modifier = Modifier
                .padding(BADGE_MARGIN)
                .size(BADGE_SIZE)
                .alpha(if (active) 1f else BADGE_FADED_ALPHA)
                .clip(CircleShape)
                .background(BADGE_BACKGROUND)
                .padding(BADGE_PADDING)
        )
    }
}

/**
 * An item title that shows the DeArrow replacement when one is active, prefixed with the marker
 * icon if the user asked for replaced titles to be marked.
 */
@Composable
internal fun DeArrowAwareTitle(
    title: String,
    dearrow: DeArrowItemState?,
    style: TextStyle,
    color: Color,
    maxLines: Int,
    modifier: Modifier = Modifier
) {
    val replacement = dearrow?.effectiveTitle
    val marked = replacement != null && dearrow.markReplacedTitles
    val fontSize = if (style.fontSize != TextUnit.Unspecified) style.fontSize else DEFAULT_MARK_SIZE
    Text(
        text = if (marked) markedTitle(replacement) else AnnotatedString(replacement ?: title),
        inlineContent = if (marked) markerInlineContent(fontSize, color) else emptyMap(),
        style = style,
        color = color,
        maxLines = maxLines,
        overflow = TextOverflow.Ellipsis,
        modifier = modifier
    )
}

private fun markedTitle(replacement: String): AnnotatedString = buildAnnotatedString {
    appendInlineContent(TITLE_MARK_ID, " ")
    append(" ")
    append(replacement)
}

@Composable
private fun markerInlineContent(fontSize: TextUnit, tint: Color): Map<String, InlineTextContent> =
    mapOf(
        TITLE_MARK_ID to InlineTextContent(
            Placeholder(fontSize, fontSize, PlaceholderVerticalAlign.TextCenter)
        ) {
            Image(
                painter = painterResource(R.drawable.ic_stars),
                contentDescription = null,
                colorFilter = ColorFilter.tint(tint),
                modifier = Modifier.fillMaxSize()
            )
        }
    )

private const val TITLE_MARK_ID = "dearrow_mark"
private const val BADGE_FADED_ALPHA = 0.45f
private val BADGE_SIZE = 22.dp
private val BADGE_MARGIN = 3.dp
private val BADGE_PADDING = 3.dp
private val BADGE_TOUCH_TARGET = 40.dp
private val BADGE_BACKGROUND = Color(0x64000000)
private val DEFAULT_MARK_SIZE = 14.sp
