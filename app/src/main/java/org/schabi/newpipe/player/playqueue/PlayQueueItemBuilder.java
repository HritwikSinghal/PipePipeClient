package org.schabi.newpipe.player.playqueue;

import android.content.Context;
import android.view.MotionEvent;
import android.view.View;

import org.schabi.newpipe.extractor.NewPipe;
import org.schabi.newpipe.util.Localization;
import org.schabi.newpipe.util.PicassoHelper;

public class PlayQueueItemBuilder {
    private static final String TAG = PlayQueueItemBuilder.class.toString();
    private OnSelectedListener onItemClickListener;

    public PlayQueueItemBuilder(final Context context) {
    }

    public void setOnSelectedListener(final OnSelectedListener listener) {
        this.onItemClickListener = listener;
    }

    public void buildStreamInfoItem(final PlayQueueItemHolder holder, final PlayQueueItem item) {
        // Always reset the baseline, even for an item whose metadata has not resolved yet: on a
        // recycled holder the view still shows the previous row's text, which the DeArrow apply()
        // below would otherwise snapshot as this item's original title.
        holder.itemVideoTitleView.setText(item.getTitle() == null ? "" : item.getTitle());
        holder.deArrowController.apply(holder.itemVideoTitleView,
                item.getServiceId(), item.getUrl());
        holder.itemAdditionalDetailsView.setText(Localization.concatenateStrings(item.getUploader(),
                NewPipe.getNameOfService(item.getServiceId())));

        if (item.getDuration() > 0) {
            holder.itemDurationView.setText(Localization.getDurationString(item.getDuration()));
        } else {
            holder.itemDurationView.setVisibility(View.GONE);
        }

        PicassoHelper.loadScaledDownThumbnail(holder.itemThumbnailView.getContext(), item.getThumbnailUrl()).into(holder.itemThumbnailView);

        holder.itemRoot.setOnClickListener(view -> {
            if (onItemClickListener != null) {
                onItemClickListener.selected(item, view);
            }
        });

        holder.itemRoot.setOnLongClickListener(view -> {
            if (onItemClickListener != null) {
                onItemClickListener.held(item, view);
                return true;
            }
            return false;
        });

        holder.itemHandle.setOnTouchListener(getOnTouchListener(holder));
    }

    private View.OnTouchListener getOnTouchListener(final PlayQueueItemHolder holder) {
        return (view, motionEvent) -> {
            view.performClick();
            if (motionEvent.getActionMasked() == MotionEvent.ACTION_DOWN
                    && onItemClickListener != null) {
                onItemClickListener.onStartDrag(holder);
            }
            return false;
        };
    }

    public interface OnSelectedListener {
        void selected(PlayQueueItem item, View view);

        void held(PlayQueueItem item, View view);

        void onStartDrag(PlayQueueItemHolder viewHolder);
    }
}
