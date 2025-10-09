package com.mk.androidtransfer.widget;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.SweepGradient;
import android.graphics.Shader;
import android.util.AttributeSet;
import android.view.View;
import android.view.animation.LinearInterpolator;

import androidx.annotation.Nullable;

/**
 * 雷达扫描动画View
 * Material Design风格的雷达扫描效果
 */
public class RadarScanView extends View {

    private Paint radarPaint;
    private Paint circlePaint;
    private float centerX, centerY;
    private float radius;
    private float currentAngle = 0;
    private ValueAnimator radarAnimator;

    // Material Design配色 - 青绿色系
    private static final int COLOR_RADAR_START = Color.parseColor("#4D00BFA5"); // 40% opacity primary
    private static final int COLOR_RADAR_END = Color.parseColor("#0000BFA5"); // 0% opacity
    private static final int COLOR_CIRCLE = Color.parseColor("#1A00BFA5"); // 10% opacity

    public RadarScanView(Context context) {
        super(context);
        init();
    }

    public RadarScanView(Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);
        init();
    }

    public RadarScanView(Context context, @Nullable AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        init();
    }

    private void init() {
        // 初始化雷达扫描画笔
        radarPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        radarPaint.setStyle(Paint.Style.FILL);

        // 初始化圆圈画笔
        circlePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        circlePaint.setStyle(Paint.Style.STROKE);
        circlePaint.setStrokeWidth(2);
        circlePaint.setColor(COLOR_CIRCLE);
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        centerX = w / 2f;
        centerY = h / 2f;
        radius = Math.min(w, h) / 2f;

        // 创建扫描渐变
        updateShader();
    }

    private void updateShader() {
        // 创建扫描渐变效果
        Shader shader = new SweepGradient(centerX, centerY,
                new int[]{COLOR_RADAR_END, COLOR_RADAR_START, COLOR_RADAR_END},
                new float[]{0f, 0.2f, 0.3f});
        radarPaint.setShader(shader);
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);

        // 绘制同心圆
        float[] circleRadii = {radius * 0.3f, radius * 0.6f, radius * 0.9f};
        for (float r : circleRadii) {
            canvas.drawCircle(centerX, centerY, r, circlePaint);
        }

        // 保存画布状态
        canvas.save();

        // 旋转画布以产生扫描效果
        canvas.rotate(currentAngle, centerX, centerY);

        // 绘制雷达扫描区域
        canvas.drawCircle(centerX, centerY, radius, radarPaint);

        // 恢复画布
        canvas.restore();
    }

    /**
     * 开始雷达扫描动画
     */
    public void startScanning() {
        if (radarAnimator != null && radarAnimator.isRunning()) {
            return;
        }

        radarAnimator = ValueAnimator.ofFloat(0f, 360f);
        radarAnimator.setDuration(3000); // 3秒一圈
        radarAnimator.setRepeatCount(ValueAnimator.INFINITE);
        radarAnimator.setInterpolator(new LinearInterpolator());
        radarAnimator.addUpdateListener(animation -> {
            currentAngle = (float) animation.getAnimatedValue();
            invalidate();
        });
        radarAnimator.start();
    }

    /**
     * 停止雷达扫描动画
     */
    public void stopScanning() {
        if (radarAnimator != null) {
            radarAnimator.cancel();
            radarAnimator = null;
        }
    }

    /**
     * 检查是否正在扫描
     */
    public boolean isScanning() {
        return radarAnimator != null && radarAnimator.isRunning();
    }

    @Override
    protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        stopScanning();
    }
}
