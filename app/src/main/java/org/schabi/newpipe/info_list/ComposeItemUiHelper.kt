package org.schabi.newpipe.info_list

import android.content.Context
import android.graphics.Bitmap
import android.view.MotionEvent
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DragHandle
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.input.pointer.pointerInteropFilter
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.preference.PreferenceManager
import com.squareup.picasso.Picasso
import com.squareup.picasso.RequestCreator
import com.squareup.picasso.Target
import org.schabi.newpipe.R
import org.schabi.newpipe.database.LocalItem
import org.schabi.newpipe.database.playlist.PlaylistMetadataEntry
import org.schabi.newpipe.database.playlist.PlaylistStreamEntry
import org.schabi.newpipe.database.playlist.model.PlaylistRemoteEntity
import org.schabi.newpipe.database.stream.StreamStatisticsEntry
import org.schabi.newpipe.extractor.InfoItem
import org.schabi.newpipe.extractor.InfoItem.InfoType
import org.schabi.newpipe.extractor.channel.ChannelInfoItem
import org.schabi.newpipe.extractor.playlist.PlaylistInfoItem
import org.schabi.newpipe.extractor.stream.StreamInfoItem
import org.schabi.newpipe.extractor.stream.StreamType
import org.schabi.newpipe.extractor.NewPipe
import org.schabi.newpipe.local.history.HistoryRecordManager
import org.schabi.newpipe.util.Localization
import org.schabi.newpipe.util.PicassoHelper
import org.schabi.newpipe.util.ThemeHelper
import java.time.format.DateTimeFormatter
import java.util.concurrent.TimeUnit

@Composable
fun PipePipeComposeTheme(
    context: Context,
    content: @Composable () -> Unit
) {
    val colorScheme = remember(context) {
        resolveComposeColorScheme(context)
    }

    MaterialTheme(colorScheme = colorScheme, content = content)
}

private fun resolveComposeColorScheme(context: Context): ColorScheme {
    val background = Color(ThemeHelper.resolveColorFromAttr(context, R.attr.windowBackground))
    val onSurface = Color(ThemeHelper.resolveColorFromAttr(context, android.R.attr.textColorPrimary))
    val onSurfaceVariant = Color(ThemeHelper.resolveColorFromAttr(context, android.R.attr.textColorSecondary))
    val surfaceVariant = Color(ThemeHelper.resolveColorFromAttr(context, R.attr.card_item_background_color))
    val outline = Color(ThemeHelper.resolveColorFromAttr(context, R.attr.border_color))
    val contrastBackground = Color(ThemeHelper.resolveColorFromAttr(context, R.attr.contrast_background_color))
    val resources = context.resources
    val preferences = PreferenceManager.getDefaultSharedPreferences(context)
    val selectedTheme = preferences.getString(
        context.getString(R.string.theme_key),
        resources.getString(R.string.default_theme_value)
    )
    val isLight = when (selectedTheme) {
        resources.getString(R.string.light_theme_key) -> true
        resources.getString(R.string.auto_device_theme_key) -> !ThemeHelper.isDeviceDarkThemeEnabled(context)
        else -> false
    }

    return if (isLight) {
        lightColorScheme(
            primary = onSurface,
            onPrimary = background,
            primaryContainer = surfaceVariant,
            onPrimaryContainer = onSurface,
            secondary = onSurface,
            onSecondary = background,
            secondaryContainer = surfaceVariant,
            onSecondaryContainer = onSurface,
            background = background,
            onBackground = onSurface,
            surface = background,
            onSurface = onSurface,
            surfaceVariant = surfaceVariant,
            onSurfaceVariant = onSurfaceVariant,
            outline = outline,
            outlineVariant = contrastBackground,
            scrim = Color.Black
        )
    } else {
        darkColorScheme(
            primary = onSurface,
            onPrimary = background,
            primaryContainer = surfaceVariant,
            onPrimaryContainer = onSurface,
            secondary = onSurface,
            onSecondary = background,
            secondaryContainer = surfaceVariant,
            onSecondaryContainer = onSurface,
            background = background,
            onBackground = onSurface,
            surface = background,
            onSurface = onSurface,
            surfaceVariant = surfaceVariant,
            onSurfaceVariant = onSurfaceVariant,
            outline = outline,
            outlineVariant = contrastBackground,
            scrim = Color.Black
        )
    }
}

@Composable
private fun rememberPicassoBitmap(
    key: Any?,
    request: () -> RequestCreator
): Bitmap? {
    // Keyed, not bare. ComposeView.setContent does NOT rebuild the composition, so on a recycled
    // holder an unkeyed remember still holds the PREVIOUS item's bitmap when the new item first
    // composes, and DisposableEffect only clears it a frame later, in the effect phase. Keying
    // makes the reset part of composition itself.
    var bitmap by remember(key) { mutableStateOf<Bitmap?>(null) }

    DisposableEffect(key) {
        bitmap = null
        val target = object : Target {
            override fun onBitmapLoaded(loadedBitmap: Bitmap, from: Picasso.LoadedFrom) {
                bitmap = loadedBitmap
            }

            override fun onBitmapFailed(e: Exception?, errorDrawable: android.graphics.drawable.Drawable?) {
                bitmap = null
            }

            override fun onPrepareLoad(placeHolderDrawable: android.graphics.drawable.Drawable?) {
            }
        }
        request().into(target)
        // Two jobs. Capturing `target` here is what keeps it alive at all: Picasso holds targets
        // weakly, so without a strong reference a GC mid-flight silently drops the image. The
        // cancel then drops the superseded request when the effect restarts. Note this fires on
        // REBIND (when `key` changes), not on scroll-away: ComposeInfoItemHolder uses
        // DisposeOnViewTreeLifecycleDestroyed, so a recycled holder's composition stays alive and
        // keeps its target until it is bound to something else. That is bounded by the view pool.
        onDispose { PicassoHelper.cancelTarget(target) }
    }

    return bitmap
}

@Composable
private fun RemoteImage(
    modifier: Modifier,
    contentScale: ContentScale,
    bitmap: Bitmap?
) {
    if (bitmap != null) {
        Image(
            bitmap = bitmap.asImageBitmap(),
            contentDescription = null,
            modifier = modifier,
            contentScale = contentScale
        )
    } else {
        Box(
            modifier = modifier.background(MaterialTheme.colorScheme.surfaceVariant)
        )
    }
}

data class ComposeItemState(
    val title: String,
    val subtitle: String?,
    val details: String?,
    val imageUrl: String?,
    val durationText: String?,
    val showLiveBadge: Boolean,
    val showPaidBadge: Boolean,
    val progress: Float?,
    val playlistCount: String?,
    val isChannel: Boolean,
    /**
     * Service and URL of the underlying video, for DeArrow. Left at the defaults for anything that
     * is not a single video (a channel or playlist row), which switches DeArrow off for that item.
     */
    val serviceId: Int = NO_SERVICE_ID,
    val url: String? = null
)

/** Service ID for an item DeArrow can never apply to; see [ComposeItemState.serviceId]. */
private const val NO_SERVICE_ID = -1

/** Matches the View sites' DeArrow thumbnail swap, so the change reads the same in both UIs. */
private const val THUMBNAIL_CROSSFADE_MS = 200

fun buildInfoItemState(
    context: Context,
    item: InfoItem,
    historyRecordManager: HistoryRecordManager?
): ComposeItemState? {
    return when (item.infoType) {
        InfoType.STREAM -> {
            item as StreamInfoItem
            val state = historyRecordManager?.loadStreamState(item)?.blockingGet()?.firstOrNull()
            val details = buildString {
                if (item.viewCount >= 0) {
                    append(
                        when (item.streamType) {
                            StreamType.AUDIO_LIVE_STREAM -> Localization.listeningCount(context, item.viewCount)
                            StreamType.LIVE_STREAM -> Localization.shortWatchingCount(context, item.viewCount)
                            else -> Localization.shortViewCount(context, item.viewCount)
                        }
                    )
                }
                val uploadDate = item.uploadDate?.let {
                    Localization.relativeTime(it.offsetDateTime())
                } ?: item.textualUploadDate
                if (!uploadDate.isNullOrEmpty()) {
                    if (isNotEmpty()) {
                        append(" • ")
                    }
                    append(uploadDate)
                }
            }.ifEmpty { null }
            ComposeItemState(
                title = item.name ?: "",
                subtitle = item.uploaderName,
                details = details,
                imageUrl = item.thumbnailUrl,
                durationText = when {
                    item.requiresMembership() -> context.getString(R.string.paid_video)
                    item.duration > 0 -> Localization.getDurationString(item.duration)
                    item.streamType == StreamType.LIVE_STREAM
                            || item.streamType == StreamType.AUDIO_LIVE_STREAM -> context.getString(R.string.duration_live).uppercase()
                    else -> null
                },
                showLiveBadge = item.streamType == StreamType.LIVE_STREAM
                        || item.streamType == StreamType.AUDIO_LIVE_STREAM,
                showPaidBadge = item.requiresMembership(),
                progress = if (state != null && item.duration > 0) {
                    TimeUnit.MILLISECONDS.toSeconds(state.progressMillis).toFloat() / item.duration.toFloat()
                } else {
                    null
                },
                playlistCount = null,
                isChannel = false,
                serviceId = item.serviceId,
                url = item.url
            )
        }
        InfoType.PLAYLIST -> {
            item as PlaylistInfoItem
            ComposeItemState(
                title = item.name,
                subtitle = item.uploaderName,
                details = null,
                imageUrl = item.thumbnailUrl,
                durationText = null,
                showLiveBadge = false,
                showPaidBadge = false,
                progress = null,
                playlistCount = Localization.localizeStreamCountMini(context, item.streamCount),
                isChannel = false
            )
        }
        InfoType.CHANNEL -> {
            item as ChannelInfoItem
            val details = buildString {
                if (item.subscriberCount >= 0) {
                    append(Localization.shortSubscriberCount(context, item.subscriberCount))
                }
                if (item.streamCount >= 0) {
                    if (isNotEmpty()) {
                        append(" • ")
                    }
                    append(Localization.localizeStreamCount(context, item.streamCount))
                }
            }.ifEmpty { null }
            ComposeItemState(
                title = item.name,
                subtitle = item.description,
                details = details,
                imageUrl = item.thumbnailUrl,
                durationText = null,
                showLiveBadge = false,
                showPaidBadge = false,
                progress = null,
                playlistCount = null,
                isChannel = true
            )
        }
        else -> null
    }
}

fun buildLocalItemState(
    context: Context,
    item: LocalItem,
    dateTimeFormatter: DateTimeFormatter?
): ComposeItemState? {
    return when (item.localItemType) {
        // Both stream rows carry serviceId + url so DeArrow reaches local playlists and history,
        // exactly as it does for their View-based holders.
        LocalItem.LocalItemType.PLAYLIST_STREAM_ITEM -> {
            item as PlaylistStreamEntry
            ComposeItemState(
                title = item.streamEntity.title,
                subtitle = null,
                details = Localization.concatenateStrings(
                    item.streamEntity.uploader,
                    NewPipe.getNameOfService(item.streamEntity.serviceId)
                ),
                imageUrl = item.streamEntity.thumbnailUrl,
                durationText = if (item.streamEntity.duration > 0) {
                    Localization.getDurationString(item.streamEntity.duration)
                } else {
                    null
                },
                showLiveBadge = false,
                showPaidBadge = false,
                progress = if (item.progressMillis > 0 && item.streamEntity.duration > 0) {
                    TimeUnit.MILLISECONDS.toSeconds(item.progressMillis).toFloat() / item.streamEntity.duration.toFloat()
                } else {
                    null
                },
                playlistCount = null,
                isChannel = false,
                serviceId = item.streamEntity.serviceId,
                url = item.streamEntity.url
            )
        }
        LocalItem.LocalItemType.STATISTIC_STREAM_ITEM -> {
            item as StreamStatisticsEntry
            ComposeItemState(
                title = item.streamEntity.title,
                subtitle = item.streamEntity.uploader,
                details = Localization.concatenateStrings(
                    Localization.shortViewCount(context, item.watchCount),
                    dateTimeFormatter?.format(item.latestAccessDate)
                ),
                imageUrl = item.streamEntity.thumbnailUrl,
                durationText = if (item.streamEntity.duration > 0) {
                    Localization.getDurationString(item.streamEntity.duration)
                } else {
                    null
                },
                showLiveBadge = false,
                showPaidBadge = false,
                progress = if (item.progressMillis > 0 && item.streamEntity.duration > 0) {
                    TimeUnit.MILLISECONDS.toSeconds(item.progressMillis).toFloat() / item.streamEntity.duration.toFloat()
                } else {
                    null
                },
                playlistCount = null,
                isChannel = false,
                serviceId = item.streamEntity.serviceId,
                url = item.streamEntity.url
            )
        }
        LocalItem.LocalItemType.PLAYLIST_LOCAL_ITEM -> {
            item as PlaylistMetadataEntry
            ComposeItemState(
                title = item.name,
                subtitle = null,
                details = null,
                imageUrl = item.thumbnailUrl,
                durationText = null,
                showLiveBadge = false,
                showPaidBadge = false,
                progress = null,
                playlistCount = Localization.localizeStreamCountMini(context, item.streamCount),
                isChannel = false
            )
        }
        LocalItem.LocalItemType.PLAYLIST_REMOTE_ITEM -> {
            item as PlaylistRemoteEntity
            ComposeItemState(
                title = item.name,
                subtitle = item.uploader,
                details = null,
                imageUrl = item.thumbnailUrl,
                durationText = null,
                showLiveBadge = false,
                showPaidBadge = false,
                progress = null,
                playlistCount = Localization.localizeStreamCountMini(context, item.streamCount ?: -1L),
                isChannel = false
            )
        }
    }
}

@Composable
fun CommonItem(
    state: ComposeItemState,
    isGridLayout: Boolean,
    isCardLayout: Boolean,
    showDragHandle: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
    onLongClick: (() -> Unit)? = null,
    onDragStart: (() -> Unit)? = null
) {
    if (state.isChannel) {
        if (isGridLayout || isCardLayout) {
            ChannelGridItem(
                state = state,
                modifier = modifier,
                onClick = onClick,
                onLongClick = onLongClick
            )
        } else {
            ChannelListItem(
                state = state,
                modifier = modifier,
                onClick = onClick,
                onLongClick = onLongClick
            )
        }
    } else {
        // Resolved once per item and shared by the thumbnail, the title and the badge. A playlist
        // row carries no serviceId/url, so this is inert for it.
        val dearrow = rememberDeArrowItemState(state.serviceId, state.url)
        if (isGridLayout || isCardLayout) {
            StreamOrPlaylistGridItem(
                state = state,
                dearrow = dearrow,
                modifier = modifier,
                onClick = onClick,
                onLongClick = onLongClick,
                showDragHandle = showDragHandle,
                onDragStart = onDragStart,
                isCardLayout = isCardLayout
            )
        } else {
            StreamOrPlaylistListItem(
                state = state,
                dearrow = dearrow,
                modifier = modifier,
                onClick = onClick,
                onLongClick = onLongClick,
                showDragHandle = showDragHandle,
                onDragStart = onDragStart
            )
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ChannelListItem(
    state: ComposeItemState,
    modifier: Modifier,
    onClick: () -> Unit,
    onLongClick: (() -> Unit)?
) {
    val context = LocalContext.current
    val bitmap = rememberPicassoBitmap(state.imageUrl) {
        PicassoHelper.loadScaledDownThumbnail(context, state.imageUrl)
    }

    Row(
        modifier = modifier
            .fillMaxWidth()
            .height(70.dp)
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
            .padding(horizontal = 12.dp, vertical = 5.dp)
    ) {
        RemoteImage(
            modifier = Modifier
                .size(60.dp)
                .clip(CircleShape),
            contentScale = ContentScale.Crop,
            bitmap = bitmap
        )

        Spacer(modifier = Modifier.width(16.dp))

        Column(
            modifier = Modifier
                .weight(1f)
                .fillMaxHeight(),
            verticalArrangement = Arrangement.Center
        ) {
            Text(
                text = state.title,
                style = TextStyle(fontSize = 14.sp, fontWeight = FontWeight.Medium),
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            if (!state.subtitle.isNullOrEmpty()) {
                Spacer(modifier = Modifier.height(6.dp))
                Text(
                    text = state.subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }
            if (!state.details.isNullOrEmpty()) {
                Spacer(modifier = Modifier.height(6.dp))
                Text(
                    text = state.details,
                    style = MaterialTheme.typography.bodySmall,
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ChannelGridItem(
    state: ComposeItemState,
    modifier: Modifier,
    onClick: () -> Unit,
    onLongClick: (() -> Unit)?
) {
    val context = LocalContext.current
    val bitmap = rememberPicassoBitmap(state.imageUrl) {
        PicassoHelper.loadScaledDownThumbnail(context, state.imageUrl)
    }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
            .padding(horizontal = 12.dp, vertical = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        RemoteImage(
            modifier = Modifier
                .size(60.dp)
                .clip(CircleShape),
            contentScale = ContentScale.Crop,
            bitmap = bitmap
        )

        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = state.title,
            style = TextStyle(fontSize = 14.sp, fontWeight = FontWeight.Medium),
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center
        )
        if (!state.details.isNullOrEmpty()) {
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = state.details,
                style = MaterialTheme.typography.bodySmall,
                fontSize = 11.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                textAlign = TextAlign.Center
            )
        }
    }
}

@Composable
private fun OverlayBadge(
    text: String,
    backgroundColor: Color,
    textColor: Color,
    modifier: Modifier = Modifier
) {
    Text(
        text = text,
        color = textColor,
        fontSize = 10.sp,
        lineHeight = 18.sp,
        modifier = modifier
            .background(backgroundColor, RoundedCornerShape(4.dp))
            .padding(horizontal = 4.dp, vertical = 1.dp)
    )
}

@Composable
private fun ThumbnailBox(
    state: ComposeItemState,
    dearrow: DeArrowItemState? = null,
    width: Dp? = null,
    height: Dp? = null,
    useAspectRatio: Boolean,
    rounded: Dp,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val bitmap = rememberPicassoBitmap(state.imageUrl) {
        if (state.playlistCount != null) {
            PicassoHelper.loadPlaylistThumbnail(state.imageUrl)
        } else {
            PicassoHelper.loadScaledDownThumbnail(context, state.imageUrl)
        }
    }
    // Decoded off-view, so a frame the generator has not produced yet (204 No Content) leaves the
    // original on screen instead of blanking the row.
    val deArrowBitmap = rememberDeArrowThumbnail(dearrow?.effectiveThumbnailUrl)
    if (dearrow != null) {
        TrackDeArrowThumbnail(dearrow, deArrowBitmap)
    }

    Box(
        modifier = modifier
            .then(if (width != null) Modifier.width(width) else Modifier)
            .then(if (height != null) Modifier.height(height) else Modifier)
            .then(if (useAspectRatio) Modifier.aspectRatio(16f / 9f) else Modifier)
            .clip(RoundedCornerShape(rounded))
    ) {
        Crossfade(
            targetState = deArrowBitmap ?: bitmap,
            animationSpec = tween(THUMBNAIL_CROSSFADE_MS),
            label = "thumbnail"
        ) { shown ->
            RemoteImage(
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop,
                bitmap = shown
            )
        }

        if (dearrow != null && dearrow.gatedIn) {
            DeArrowBadge(
                state = dearrow,
                modifier = Modifier.align(Alignment.TopStart)
            )
        }

        if (state.showLiveBadge) {
            OverlayBadge(
                text = stringResource(R.string.duration_live).uppercase(),
                backgroundColor = Color.Red.copy(alpha = 0.7f),
                textColor = Color.White,
                modifier = Modifier.align(Alignment.BottomEnd)
            )
        } else if (!state.durationText.isNullOrEmpty()) {
            OverlayBadge(
                text = state.durationText,
                backgroundColor = Color(0x99000000),
                textColor = Color.White,
                modifier = Modifier.align(Alignment.BottomEnd)
            )
        }

        if (state.showPaidBadge) {
            OverlayBadge(
                text = stringResource(R.string.paid_video).uppercase(),
                backgroundColor = Color(0xFFFFD700),
                textColor = Color.Black,
                modifier = Modifier.align(Alignment.TopEnd)
            )
        }

        state.progress?.takeIf { it > 0f }?.let {
            LinearProgressIndicator(
                progress = it.coerceIn(0f, 1f),
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .fillMaxWidth()
                    .height(3.dp),
                color = Color.Red,
                trackColor = Color(0x33FFFFFF)
            )
        }

        state.playlistCount?.let {
            Box(
                modifier = Modifier
                    .fillMaxHeight()
                    .fillMaxWidth(0.25f)
                    .align(Alignment.CenterEnd)
                    .background(Color(0x80000000)),
                contentAlignment = Alignment.Center
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.PlayArrow,
                        contentDescription = null,
                        tint = Color.White
                    )
                    Text(
                        text = it,
                        color = Color.White,
                        fontWeight = FontWeight.Bold,
                        style = MaterialTheme.typography.bodySmall,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalComposeUiApi::class)
@Composable
private fun DragHandle(
    onDragStart: (() -> Unit)?,
    modifier: Modifier = Modifier
) {
    Icon(
        imageVector = Icons.Default.DragHandle,
        contentDescription = stringResource(R.string.detail_drag_description),
        modifier = modifier.pointerInteropFilter {
            if (it.actionMasked == MotionEvent.ACTION_DOWN) {
                onDragStart?.invoke()
            }
            false
        },
        tint = MaterialTheme.colorScheme.onSurfaceVariant
    )
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun StreamOrPlaylistListItem(
    state: ComposeItemState,
    dearrow: DeArrowItemState?,
    modifier: Modifier,
    onClick: () -> Unit,
    onLongClick: (() -> Unit)?,
    showDragHandle: Boolean,
    onDragStart: (() -> Unit)?
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .height(84.dp)
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
            .padding(horizontal = 12.dp, vertical = 6.dp)
    ) {
        ThumbnailBox(
            state = state,
            dearrow = dearrow,
            width = 120.dp,
            height = 70.dp,
            useAspectRatio = false,
            rounded = 4.dp,
            modifier = Modifier.padding(vertical = 1.dp)
        )

        Spacer(modifier = Modifier.width(8.dp))

        Column(
            modifier = Modifier
                .weight(1f)
                .fillMaxHeight(),
            verticalArrangement = Arrangement.SpaceEvenly
        ) {
            DeArrowAwareTitle(
                title = state.title,
                dearrow = dearrow,
                style = TextStyle(fontSize = 13.5.sp),
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 2
            )
            state.subtitle?.let {
                Text(
                    text = it,
                    style = MaterialTheme.typography.bodySmall,
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            state.details?.let {
                Text(
                    text = it,
                    style = MaterialTheme.typography.bodySmall,
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }

        if (showDragHandle) {
            DragHandle(
                onDragStart = onDragStart,
                modifier = Modifier
                    .align(Alignment.CenterVertically)
                    .padding(8.dp)
            )
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun StreamOrPlaylistGridItem(
    state: ComposeItemState,
    dearrow: DeArrowItemState?,
    modifier: Modifier,
    onClick: () -> Unit,
    onLongClick: (() -> Unit)?,
    showDragHandle: Boolean,
    onDragStart: (() -> Unit)?,
    isCardLayout: Boolean
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
            .padding(horizontal = if (isCardLayout) 2.dp else 12.dp, vertical = if (isCardLayout) 8.dp else 12.dp)
    ) {
        ThumbnailBox(
            state = state,
            dearrow = dearrow,
            useAspectRatio = true,
            rounded = 8.dp,
            modifier = Modifier.fillMaxWidth()
        )

        Spacer(modifier = Modifier.height(8.dp))
        Box(modifier = Modifier.fillMaxWidth()) {
            DeArrowAwareTitle(
                title = state.title,
                dearrow = dearrow,
                style = TextStyle(fontSize = 13.5.sp, fontWeight = FontWeight.Medium),
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 2,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(end = if (showDragHandle) 28.dp else 0.dp)
            )

            if (showDragHandle) {
                DragHandle(
                    onDragStart = onDragStart,
                    modifier = Modifier.align(Alignment.TopEnd)
                )
            }
        }

        state.subtitle?.let {
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = it,
                style = MaterialTheme.typography.bodySmall,
                fontSize = 11.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
        state.details?.let {
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                text = it,
                style = MaterialTheme.typography.bodySmall,
                fontSize = 11.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}
