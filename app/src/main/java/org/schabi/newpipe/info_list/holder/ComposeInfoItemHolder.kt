package org.schabi.newpipe.info_list.holder

import android.view.ViewGroup
import androidx.compose.runtime.key
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import org.schabi.newpipe.extractor.InfoItem
import org.schabi.newpipe.info_list.CommonItem
import org.schabi.newpipe.info_list.InfoItemBuilder
import org.schabi.newpipe.info_list.ItemViewMode
import org.schabi.newpipe.info_list.PipePipeComposeTheme
import org.schabi.newpipe.info_list.buildInfoItemState
import org.schabi.newpipe.local.history.HistoryRecordManager
import org.schabi.newpipe.util.ThemeHelper

class ComposeInfoItemHolder(
    private val infoItemBuilder: InfoItemBuilder,
    parent: ViewGroup,
    private val itemViewMode: ItemViewMode
) : InfoItemHolder(
    infoItemBuilder,
    ComposeView(parent.context).apply {
        layoutParams = ViewGroup.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        )
        setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
    }
) {

    private val composeView = itemView as ComposeView

    override fun updateFromItem(infoItem: InfoItem, historyRecordManager: HistoryRecordManager) {
        val state = buildInfoItemState(composeView.context, infoItem, historyRecordManager)
        if (state == null) {
            // Clear rather than return: this holder is recycled, so leaving the old content in
            // place would show the previous item's row. InfoListAdapter.getItemViewType keeps
            // unsupported types away from here, and this makes that safe rather than load-bearing.
            composeView.setContent { }
            return
        }
        composeView.setContent {
            PipePipeComposeTheme(composeView.context) {
                // key() on the item's identity, because setContent reuses this holder's composition
                // rather than rebuilding it: without this, every remembered value in the subtree --
                // including the thumbnail bitmaps and Crossfade's Transition -- carries over to the
                // next video bound here, and the row animates away from the previous thumbnail.
                key(infoItem.url ?: infoItem.name) {
                    CommonItem(
                        state = state,
                        isGridLayout = ThemeHelper.isGrid(itemViewMode),
                        isCardLayout = itemViewMode == ItemViewMode.CARD,
                        showDragHandle = false,
                        onClick = {
                            when (infoItem) {
                                is org.schabi.newpipe.extractor.stream.StreamInfoItem -> infoItemBuilder.getOnStreamSelectedListener()?.selected(infoItem)
                                is org.schabi.newpipe.extractor.channel.ChannelInfoItem -> infoItemBuilder.getOnChannelSelectedListener()?.selected(infoItem)
                                is org.schabi.newpipe.extractor.playlist.PlaylistInfoItem -> infoItemBuilder.getOnPlaylistSelectedListener()?.selected(infoItem)
                            }
                        },
                        onLongClick = {
                            when (infoItem) {
                                is org.schabi.newpipe.extractor.stream.StreamInfoItem -> infoItemBuilder.getOnStreamSelectedListener()?.held(infoItem)
                                is org.schabi.newpipe.extractor.channel.ChannelInfoItem -> infoItemBuilder.getOnChannelSelectedListener()?.held(infoItem)
                                is org.schabi.newpipe.extractor.playlist.PlaylistInfoItem -> infoItemBuilder.getOnPlaylistSelectedListener()?.held(infoItem)
                            }
                        }
                    )
                }
            }
        }
    }
}
