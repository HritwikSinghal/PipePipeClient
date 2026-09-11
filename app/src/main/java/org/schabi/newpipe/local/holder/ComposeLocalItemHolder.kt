package org.schabi.newpipe.local.holder

import android.view.ViewGroup
import androidx.compose.runtime.key
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import org.schabi.newpipe.database.LocalItem
import org.schabi.newpipe.database.playlist.PlaylistMetadataEntry
import org.schabi.newpipe.database.playlist.PlaylistStreamEntry
import org.schabi.newpipe.database.playlist.model.PlaylistRemoteEntity
import org.schabi.newpipe.database.stream.StreamStatisticsEntry
import org.schabi.newpipe.info_list.CommonItem
import org.schabi.newpipe.info_list.PipePipeComposeTheme
import org.schabi.newpipe.info_list.buildLocalItemState
import org.schabi.newpipe.info_list.ItemViewMode
import org.schabi.newpipe.local.LocalItemBuilder
import org.schabi.newpipe.local.history.HistoryRecordManager
import org.schabi.newpipe.util.ThemeHelper
import java.time.format.DateTimeFormatter

class ComposeLocalItemHolder(
    private val localItemBuilder: LocalItemBuilder,
    parent: ViewGroup,
    private val itemViewMode: ItemViewMode,
    private val showDragHandle: Boolean
) : LocalItemHolder(
    localItemBuilder,
    ComposeView(parent.context).apply {
        layoutParams = ViewGroup.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        )
        setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
    }
) {

    private val composeView = itemView as ComposeView

    override fun updateFromItem(
        item: LocalItem,
        historyRecordManager: HistoryRecordManager,
        dateTimeFormatter: DateTimeFormatter
    ) {
        val state = buildLocalItemState(composeView.context, item, dateTimeFormatter)
        if (state == null) {
            // Unreachable today -- buildLocalItemState covers every LocalItemType -- but this
            // holder is recycled, so returning without clearing would leave the previous row's
            // content on screen. Matches ComposeInfoItemHolder.
            composeView.setContent { }
            return
        }
        composeView.setContent {
            PipePipeComposeTheme(composeView.context) {
                // See ComposeInfoItemHolder: setContent reuses this holder's composition, so the
                // subtree's remembered state has to be discarded when a different item binds here.
                key(itemKey(item)) {
                    CommonItem(
                        state = state,
                        isGridLayout = ThemeHelper.isGrid(itemViewMode),
                        isCardLayout = itemViewMode == ItemViewMode.CARD,
                        showDragHandle = showDragHandle,
                        onClick = { localItemBuilder.getOnItemSelectedListener()?.selected(item) },
                        onLongClick = { localItemBuilder.getOnItemSelectedListener()?.held(item) },
                        onDragStart = {
                            if (showDragHandle) {
                                localItemBuilder.getOnItemSelectedListener()?.drag(item, this@ComposeLocalItemHolder)
                            }
                        }
                    )
                }
            }
        }
    }

    /** A stable identity for a local item, for the composition key above. */
    private fun itemKey(item: LocalItem): String = when (item) {
        is PlaylistStreamEntry -> item.streamEntity.url
        is StreamStatisticsEntry -> item.streamEntity.url
        is PlaylistRemoteEntity -> item.url
        is PlaylistMetadataEntry -> "local-playlist:" + item.uid
        else -> item.toString()
    }
}
