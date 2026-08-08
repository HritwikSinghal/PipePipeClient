package org.schabi.newpipe.util.dearrow;

import androidx.annotation.Nullable;

import org.schabi.newpipe.extractor.ServiceList;
import org.schabi.newpipe.extractor.exceptions.ParsingException;
import org.schabi.newpipe.extractor.services.youtube.linkHandler.YoutubeStreamLinkHandlerFactory;

/**
 * Resolves the video ID that DeArrow branding is keyed by.
 *
 * <p>This is the fork's single point of contact with the extractor, deliberately: DeArrow is a
 * display-time decoration and must not reach any further into extractor internals than the public
 * link-handler API. Three callers need the same answer -- the per-item
 * {@link DeArrowItemController}, the page-level {@link DeArrowPrefetcher} and the list-filter
 * {@link DeArrowTitleMatcher} -- so the parsing, the YouTube-only gate and the failure modes live
 * here once.</p>
 */
public final class DeArrowVideoIds {
    private DeArrowVideoIds() {
    }

    /**
     * The DeArrow video ID for an item.
     *
     * @param serviceId the item's service ID; DeArrow only covers YouTube, so anything else
     *                  resolves to {@code null}
     * @param url       the item's URL
     * @return the video ID, or {@code null} if this is not a parseable YouTube stream URL
     */
    @Nullable
    public static String of(final int serviceId, @Nullable final String url) {
        if (url == null || serviceId != ServiceList.YouTube.getServiceId()) {
            return null;
        }
        try {
            final String id = YoutubeStreamLinkHandlerFactory.getInstance().getId(url);
            return (id == null || id.isEmpty()) ? null : id;
        } catch (final ParsingException | IllegalArgumentException e) {
            return null;
        }
    }
}
