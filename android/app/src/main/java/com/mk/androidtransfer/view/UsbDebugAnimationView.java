package com.mk.androidtransfer.view;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Path;
import android.graphics.PathMeasure;
import android.os.SystemClock;
import android.util.AttributeSet;
import android.view.View;
import android.view.animation.LinearInterpolator;

import androidx.annotation.Nullable;

/**
 * USB 直连：手机与电脑用线缆相连。风格与首页传输动画一致。
 */
public class UsbDebugAnimationView extends View {

    private final TransferScenePainter scene;
    private final Path cablePath = new Path();
    private final PathMeasure cableMeasure = new PathMeasure();

    private ValueAnimator animator;
    private long startMs;
    private boolean connected;
    private float connectionAlpha;

    private float phoneX, phoneY, phoneW, phoneH;
    private float laptopX, laptopY, screenW, screenH, deckH;
    private float phonePortX, phonePortY, laptopPortX, laptopPortY, glowR;

    public UsbDebugAnimationView(Context context) {
        super(context);
        scene = new TransferScenePainter(context);
        init();
    }

    public UsbDebugAnimationView(Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);
        scene = new TransferScenePainter(context);
        init();
    }

    public UsbDebugAnimationView(Context context, @Nullable AttributeSet attrs, int defStyleAttr) {
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

        float scale = Math.min(w / scene.dp(240f), h / scene.dp(150f));
        scale = Math.max(0.62f, Math.min(scale, 1.8f));

        phoneW = scene.dp(42f) * scale;
        phoneH = scene.dp(86f) * scale;
        screenW = scene.dp(96f) * scale;
        screenH = scene.dp(58f) * scale;
        deckH = scene.dp(13f) * scale;
        glowR = phoneH * 0.72f;

        float gap = scene.dp(48f) * scale;
        float contentW = phoneW + gap + screenW * 1.16f;
        float originX = (w - contentW) / 2f;
        float midY = h * 0.52f;

        phoneX = originX + phoneW / 2f;
        phoneY = midY;
        laptopX = originX + phoneW + gap + screenW * 1.16f / 2f;
        laptopY = midY - deckH * 0.1f;

        phonePortX = phoneX + phoneW / 2f + scene.dp(3f);
        phonePortY = phoneY + phoneH * 0.18f;
        laptopPortX = laptopX - screenW / 2f - scene.dp(3f);
        laptopPortY = laptopY + screenH * 0.12f;

        float sag = Math.min(h * 0.16f, scene.dp(18f) * scale);
        float midX = (phonePortX + laptopPortX) / 2f;
        cablePath.reset();
        cablePath.moveTo(phonePortX, phonePortY);
        cablePath.cubicTo(
                midX - gap * 0.08f, phonePortY + sag,
                midX + gap * 0.08f, laptopPortY + sag,
                laptopPortX, laptopPortY
        );
        cableMeasure.setPath(cablePath, false);
    }

    public void setConnectedState(boolean connected) {
        this.connected = connected;
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        if (getWidth() == 0 || getHeight() == 0 || cableMeasure.getLength() == 0) {
            return;
        }

        float t = (SystemClock.uptimeMillis() - startMs) / 1000f;
        float breathe = 0.5f + 0.5f * (float) Math.sin(t * 1.05f);
        float target = connected ? 1f : 0.42f;
        connectionAlpha += (target - connectionAlpha) * 0.08f;

        int cableColor = Color.rgb(148, 163, 184);
        scene.drawAmbient(canvas, 0, phoneX, phoneY, glowR, scene.accent, breathe * connectionAlpha);
        scene.drawAmbient(canvas, 1, laptopX, laptopY, glowR * 1.05f, scene.teal, breathe * connectionAlpha);

        scene.drawCable(canvas, cablePath, scene.dp(3.4f), cableColor, 0.45f + 0.55f * connectionAlpha);
        float beamW = Math.max(scene.dp(2.6f), phoneW * 0.06f);
        scene.drawBeam(canvas, cableMeasure, t * (0.18f + 0.12f * connectionAlpha),
                0.24f, connected ? scene.teal : scene.accent, beamW);

        scene.drawPhone(canvas, phoneX, phoneY, phoneW, phoneH, t, breathe, scene.accent);
        scene.drawLaptop(canvas, laptopX, laptopY, screenW, screenH, deckH, t, breathe, scene.teal);
        scene.drawPlug(canvas, phonePortX, phonePortY, scene.dp(10f), scene.dp(6f), scene.accent, breathe);
        scene.drawPlug(canvas, laptopPortX, laptopPortY, scene.dp(10f), scene.dp(6f), scene.teal, breathe);
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
