package org.schabi.newpipe.util.dearrow;

import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.drawable.Drawable;
import android.text.style.ImageSpan;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

/**
 * An {@link ImageSpan} that centres its drawable vertically on the text line.
 *
 * <p>Needed because {@link android.text.style.DynamicDrawableSpan#ALIGN_CENTER} is only available
 * from API 29, while the app's minSdk is 21.</p>
 */
public final class CenteredImageSpan extends ImageSpan {

    public CenteredImageSpan(@NonNull final Drawable drawable) {
        super(drawable, ALIGN_BOTTOM);
    }

    @Override
    public int getSize(@NonNull final Paint paint, final CharSequence text, final int start,
                       final int end, @Nullable final Paint.FontMetricsInt fm) {
        final Rect bounds = getDrawable().getBounds();
        if (fm != null) {
            final Paint.FontMetricsInt pfm = paint.getFontMetricsInt();
            final int fontHeight = pfm.descent - pfm.ascent;
            final int centerY = pfm.ascent + fontHeight / 2;
            final int half = bounds.height() / 2;
            fm.ascent = centerY - half;
            fm.top = fm.ascent;
            fm.bottom = centerY + half;
            fm.descent = fm.bottom;
        }
        return bounds.width();
    }

    @Override
    public void draw(@NonNull final Canvas canvas, final CharSequence text, final int start,
                     final int end, final float x, final int top, final int y, final int bottom,
                     @NonNull final Paint paint) {
        final Drawable drawable = getDrawable();
        final Paint.FontMetricsInt pfm = paint.getFontMetricsInt();
        final int fontCenter = y + (pfm.descent + pfm.ascent) / 2;
        final int transY = fontCenter - drawable.getBounds().height() / 2;
        canvas.save();
        canvas.translate(x, transY);
        drawable.draw(canvas);
        canvas.restore();
    }
}
