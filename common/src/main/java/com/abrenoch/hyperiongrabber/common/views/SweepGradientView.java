package com.abrenoch.hyperiongrabber.common.views;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.SweepGradient;
import androidx.annotation.Nullable;
import android.util.AttributeSet;
import android.view.View;

/**
 * Created by nino on 26-5-18.
 */

public class SweepGradientView extends View{

    private static final long FRAME_DELAY_MS = 33L; // ~30 fps instead of unbounded 60 fps
    private static final float ROTATE_STEP = 2f;    // keeps the same visual speed at half the frame rate

    Paint paint;
    final int[] colors = {Color.RED, Color.MAGENTA, Color.BLUE, Color.CYAN, Color.GREEN, Color.YELLOW, Color.RED};
    final float[] positions = {0f, 1/6f, 2/6f, 3/6f, 4/6f, 5/6f, 6/6f};

    SweepGradient gradient;

    Rect viewBounds = new Rect();

    Matrix gradientMatrix = new Matrix();

    float rotate = 0f;
    private int lastWidth = -1;
    private int lastHeight = -1;

    public SweepGradientView(Context context) {
        this(context, null);
    }

    public SweepGradientView(Context context, @Nullable AttributeSet attrs) {
        this(context, attrs, 0);
    }

    public SweepGradientView(Context context, @Nullable AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);

        paint = new Paint();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        if (gradient == null) return;

        rotate = (rotate + ROTATE_STEP) % 360f;
        gradientMatrix.setRotate(rotate, viewBounds.width() / 2f, viewBounds.height() / 2f);
        gradient.setLocalMatrix(gradientMatrix);

        canvas.drawRect(viewBounds, paint);

        if (isShown() && getWindowVisibility() == VISIBLE) {
            postInvalidateDelayed(FRAME_DELAY_MS);
        }
    }

    @Override
    protected void onVisibilityChanged(View changedView, int visibility) {
        super.onVisibilityChanged(changedView, visibility);
        if (visibility == VISIBLE && isShown() && getWindowVisibility() == VISIBLE) {
            invalidate();
        }
    }

    @Override
    protected void onWindowVisibilityChanged(int visibility) {
        super.onWindowVisibilityChanged(visibility);
        if (visibility == VISIBLE && isShown()) {
            invalidate();
        }
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        super.onMeasure(widthMeasureSpec, heightMeasureSpec);
        int w = getMeasuredWidth();
        int h = getMeasuredHeight();
        if (w != lastWidth || h != lastHeight) {
            lastWidth = w;
            lastHeight = h;
            viewBounds.set(0, 0, w, h);
            gradient = new SweepGradient(viewBounds.centerX(), viewBounds.centerY(), colors, positions);
            paint.setShader(gradient);
        }
    }
}
