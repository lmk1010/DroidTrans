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
 * 首页 / 上传页：手机与电脑之间的无线传输。
 * 能量用沿线光带，不用粒子。
 */
public class DataTransferAnimationView extends View {

    private final TransferScenePainter scene;
    private final Path forwardPath = new Path();
    private final Path reversePath = new Path();
    private final PathMeasure forwardMeasure = new PathMeasure();
    private final PathMeasure reverseMeasure = new PathMeasure();

    private ValueAnimator animator;
    private long startMs;

    private float phoneX, phoneY, phoneW, phoneH;
    private float laptopX, laptopY, screenW, screenH, deckH;
    private float glowR;

    public DataTransferAnimationView(Context context) {
        super(context);
        scene = new TransferScenePainter(context);
        init();
    }

    public DataTransferAnimationView(Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);
        scene = new TransferScenePainter(context);
        init();
    }

    public DataTransferAnimationView(Context context, @Nullable AttributeSet attrs, int defStyleAttr) {
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

        float scale = Math.min(w / scene.dp(240f), h / scene.dp(168f));
        scale = Math.max(0.62f, Math.min(scale, 2.1f));

        phoneW = scene.dp(46f) * scale;
        phoneH = scene.dp(94f) * scale;
        screenW = scene.dp(102f) * scale;
        screenH = scene.dp(64f) * scale;
        deckH = scene.dp(14f) * scale;

        float gap = scene.dp(36f) * scale;
        float contentW = phoneW + gap + screenW * 1.16f;
        float originX = (w - contentW) / 2f;
        float midY = h * 0.52f;

        phoneX = originX + phoneW / 2f;
        phoneY = midY;
        laptopX = originX + phoneW + gap + screenW * 1.16f / 2f;
        laptopY = midY - deckH * 0.12f;
        glowR = phoneH * 0.78f;

        float phonePortX = phoneX + phoneW / 2f + scene.dp(2f);
        float phonePortY = phoneY;
        float laptopPortX = laptopX - screenW / 2f - scene.dp(2f);
        float laptopPortY = laptopY - deckH * 0.32f;
        float arch = Math.min(h * 0.14f, scene.dp(22f) * scale);

        forwardPath.reset();
        forwardPath.moveTo(phonePortX, phonePortY);
        forwardPath.cubicTo(
                phonePortX + (laptopPortX - phonePortX) * 0.35f, phonePortY - arch,
                laptopPortX - (laptopPortX - phonePortX) * 0.3f, laptopPortY - arch,
                laptopPortX, laptopPortY
        );
        reversePath.reset();
        reversePath.moveTo(laptopPortX, laptopPortY + scene.dp(5f) * scale);
        reversePath.cubicTo(
                laptopPortX - (laptopPortX - phonePortX) * 0.3f, laptopPortY + arch * 0.7f,
                phonePortX + (laptopPortX - phonePortX) * 0.35f, phonePortY + arch * 0.7f,
                phonePortX, phonePortY + scene.dp(4f) * scale
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

        scene.drawAmbient(canvas, 0, phoneX, phoneY, glowR, scene.accent, breathe);
        scene.drawAmbient(canvas, 1, laptopX, laptopY, glowR * 1.05f, scene.teal, breathe);

        scene.drawGuide(canvas, forwardPath, scene.accent, scene.dp(1.15f), 0.16f);
        scene.drawGuide(canvas, reversePath, scene.teal, scene.dp(1.15f), 0.12f);

        scene.drawBeam(canvas, forwardMeasure, t * 0.28f, 0.22f, scene.accent, beamW);
        scene.drawBeam(canvas, reverseMeasure, t * 0.24f + 0.45f, 0.2f, scene.teal, beamW * 0.88f);

        scene.drawPhone(canvas, phoneX, phoneY, phoneW, phoneH, t, breathe, scene.accent);
        scene.drawLaptop(canvas, laptopX, laptopY, screenW, screenH, deckH, t, breathe, scene.teal);
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
