package org.schabi.newpipe.util.dearrow;

import com.grack.nanojson.JsonObject;
import com.grack.nanojson.JsonParser;
import com.grack.nanojson.JsonParserException;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Parses a DeArrow branding-bucket response (a map of videoID to its branding) into POJOs.
 *
 * <p>The branding endpoint returns every video sharing the requested hash prefix, so one response
 * yields many {@link DeArrowBranding} entries keyed by videoID.</p>
 */
public final class DeArrowResponseParser {
    private DeArrowResponseParser() {
    }

    /**
     * Parse a branding-bucket JSON body into a videoID to branding map.
     *
     * @param json the raw JSON response body
     * @return a map from videoID to its parsed branding (empty if the bucket has no entries)
     * @throws JsonParserException if the body is not valid JSON
     */
    public static Map<String, DeArrowBranding> parseBucket(final String json)
            throws JsonParserException {
        final JsonObject root = JsonParser.object().from(json);
        final Map<String, DeArrowBranding> result = new HashMap<>();
        for (final Map.Entry<String, Object> entry : root.entrySet()) {
            if (entry.getValue() instanceof JsonObject) {
                result.put(entry.getKey(), parseBranding((JsonObject) entry.getValue()));
            }
        }
        return result;
    }

    private static DeArrowBranding parseBranding(final JsonObject obj) {
        final List<DeArrowTitle> titles = new ArrayList<>();
        for (final Object element : obj.getArray("titles")) {
            if (element instanceof JsonObject) {
                titles.add(parseTitle((JsonObject) element));
            }
        }

        final List<DeArrowThumbnail> thumbnails = new ArrayList<>();
        for (final Object element : obj.getArray("thumbnails")) {
            if (element instanceof JsonObject) {
                thumbnails.add(parseThumbnail((JsonObject) element));
            }
        }

        final double randomTime = obj.getDouble("randomTime", Double.NaN);
        final double durationRaw = obj.getDouble("videoDuration", Double.NaN);
        final Double videoDuration = Double.isNaN(durationRaw) ? null : durationRaw;

        return new DeArrowBranding(titles, thumbnails, randomTime, videoDuration);
    }

    private static DeArrowTitle parseTitle(final JsonObject obj) {
        return new DeArrowTitle(
                obj.getString("title", ""),
                obj.getBoolean("original", false),
                obj.getInt("votes", 0),
                obj.getBoolean("locked", false),
                obj.getString("UUID", ""));
    }

    private static DeArrowThumbnail parseThumbnail(final JsonObject obj) {
        return new DeArrowThumbnail(
                obj.getDouble("timestamp", Double.NaN),
                obj.getBoolean("original", false),
                obj.getInt("votes", 0),
                obj.getBoolean("locked", false),
                obj.getString("UUID", ""));
    }
}
