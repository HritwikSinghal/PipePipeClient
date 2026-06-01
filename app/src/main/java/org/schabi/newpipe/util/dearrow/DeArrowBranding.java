package org.schabi.newpipe.util.dearrow;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Immutable DeArrow branding for a single video: its candidate titles and thumbnails plus the
 * fallback random time and (optional) video duration.
 */
public final class DeArrowBranding {
    private final List<DeArrowTitle> titles;
    private final List<DeArrowThumbnail> thumbnails;
    private final double randomTime;
    private final Double videoDuration;

    public DeArrowBranding(final List<DeArrowTitle> titles,
                           final List<DeArrowThumbnail> thumbnails,
                           final double randomTime,
                           final Double videoDuration) {
        this.titles = titles == null
                ? Collections.emptyList()
                : Collections.unmodifiableList(new ArrayList<>(titles));
        this.thumbnails = thumbnails == null
                ? Collections.emptyList()
                : Collections.unmodifiableList(new ArrayList<>(thumbnails));
        this.randomTime = randomTime;
        this.videoDuration = videoDuration;
    }

    public List<DeArrowTitle> getTitles() {
        return titles;
    }

    public List<DeArrowThumbnail> getThumbnails() {
        return thumbnails;
    }

    public double getRandomTime() {
        return randomTime;
    }

    /**
     * The video duration in seconds as reported by DeArrow, or {@code null} if unknown.
     *
     * @return the duration in seconds, or {@code null}
     */
    public Double getVideoDuration() {
        return videoDuration;
    }
}
