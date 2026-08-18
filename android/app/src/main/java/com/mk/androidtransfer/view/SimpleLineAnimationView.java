package com.mk.androidtransfer.view;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Path;
import android.graphics.PathMeasure;
import android.os.SystemClock;
import android.util.AttributeSet;
import android.view.View;
import android.view.animation.LinearInterpolator;

import androidx.annotation.Nullable;

/**
 * 手机互传：两台手机对传。风格与首页传输动画一致。
 */
public class SimpleLineAnimationView extends View {

    private final TransferScenePainter scene;
    private final Path forwardPath = new Path();
    private final Path reversePath = new Path();
    private final PathMeasure forwardMeasure = new PathMeasure();
    private final PathMeasure reverseMeasure = new PathMeasure();

    private ValueAnimator animator;
    private long startMs;

    private float leftX, rightX, phoneY, phoneW, phoneH, glowR;

    public SimpleLineAnimationView(Context context) {
        super(context);
        scene = new TransferScenePainter(context);
        init();
    }

    public SimpleLineAnimationView(Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);
        scene = new TransferScenePainter(context);
        init();
    }

    public SimpleLineAnimationView(Context context, @Nullable AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        scene = new TransferScenePainter(context);
        init();
    }

    private void init() {
        setLayerType(LAYER_TYPE_HARDWARE, null);
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        if (w <= 0 || h <= 0) {
            return;
        }

        float scale = Math.min(w / scene.dp(260f), h / scene.dp(180f));
        scale = Math.max(0.7f, Math.min(scale, 2.2f));

        phoneW = scene.dp(48f) * scale;
        phoneH = scene.dp(98f) * scale;
        glowR = phoneH * 0.78f;
        phoneY = h * 0.5f;

        float gap = scene.dp(72f) * scale;
        float contentW = phoneW * 2f + gap;
        float originX = (w - contentW) / 2f;
        leftX = originX + phoneW / 2f;
        rightX = originX + phoneW + gap + phoneW / 2f;

        float leftPortX = leftX + phoneW / 2f + scene.dp(2f);
        float rightPortX = rightX - phoneW / 2f - scene.dp(2f);
        float arch = Math.min(h * 0.12f, scene.dp(20f) * scale);

        forwardPath.reset();
        forwardPath.moveTo(leftPortX, phoneY - scene.dp(8f));
        forwardPath.cubicTo(
                leftPortX + (rightPortX - leftPortX) * 0.34f, phoneY - arch,
                rightPortX - (rightPortX - leftPortX) * 0.34f, phoneY - arch,
                rightPortX, phoneY - scene.dp(8f)
        );
        reversePath.reset();
        reversePath.moveTo(rightPortX, phoneY + scene.dp(8f));
        reversePath.cubicTo(
                rightPortX - (rightPortX - leftPortX) * 0.34f, phoneY + arch,
                leftPortX + (rightPortX - leftPortX) * 0.34f, phoneY + arch,
                leftPortX, phoneY + scene.dp(8f)
        );
        forwardMeasure.setPath(forwardPath, false);
        reverseMeasure.setPath(reversePath, false);
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        if (getWidth() == 0 || getHeight() == 0 || forwardMeasure.getLength() == 0) {
            return;
        }

        float t = (SystemClock.uptimeMillis() - startMs) / 1000f;
        float breathe = 0.5f + 0.5f * (float) Math.sin(t * 1.05f);
        float beamW = Math.max(scene.dp(2.4f), phoneW * 0.055f);

        scene.drawAmbient(canvas, 0, leftX, phoneY, glowR, scene.accent, breathe);
        scene.drawAmbient(canvas, 1, rightX, phoneY, glowR, scene.teal, breathe);

        scene.drawGuide(canvas, forwardPath, scene.accent, scene.dp(1.15f), 0.16f);
        scene.drawGuide(canvas, reversePath, scene.teal, scene.dp(1.15f), 0.12f);
        scene.drawBeam(canvas, forwardMeasure, t * 0.28f, 0.22f, scene.accent, beamW);
        scene.drawBeam(canvas, reverseMeasure, t * 0.24f + 0.5f, 0.2f, scene.teal, beamW * 0.88f);

        scene.drawPhone(canvas, leftX, phoneY, phoneW, phoneH, t, breathe, scene.accent);
        scene.drawPhone(canvas, rightX, phoneY, phoneW, phoneH, t + 0.35f, breathe, scene.teal);
    }

    public void startAnimation() {
        if (animator != null && animator.isRunning()) {
            return;
        }
        startMs = SystemClock.uptimeMillis();
        animator = ValueAnimator.ofFloat(0f, 1f);
        animator.setDuration(1000);
        animator.setRepeatCount(ValueAnimator.INFINITE);
        animator.setInterpolator(new LinearInterpolator());
        animator.addUpdateListener(animation -> postInvalidateOnAnimation());
        animator.start();
    }

    public void stopAnimation() {
        if (animator != null) {
            animator.cancel();
            animator = null;
        }
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        startAnimation();
    }

    @Override
    protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        stopAnimation();
    }
}
