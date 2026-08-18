package com.mk.androidtransfer.view;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PathMeasure;
import android.graphics.RadialGradient;
import android.graphics.RectF;
import android.graphics.Shader;

import com.mk.androidtransfer.R;

/**
 * 统一的传输场景画法：设备当物体，能量用沿线光带，不用粒子。
 */
final class TransferScenePainter {

    private static final int SCREEN_TOP = Color.rgb(22, 36, 52);
    private static final int SCREEN_BOT = Color.rgb(10, 16, 24);
    private static final int BODY = Color.WHITE;
    private static final int RIM = Color.rgb(226, 234, 242);
    private static final int SHADOW = Color.argb(26, 28, 52, 82);

    final int accent;
    final int teal;
    final float density;

    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint stroke = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint beam = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF rect = new RectF();
    private final Path segment = new Path();
    private final float[] pos = new float[2];
    private final float[] tan = new float[2];

    private Shader phoneScreenShader;
    private Shader laptopScreenShader;
    private float cachedPhoneL, cachedPhoneT, cachedPhoneR, cachedPhoneB;
    private float cachedLaptopL, cachedLaptopT, cachedLaptopR, cachedLaptopB;

    TransferScenePainter(Context context) {
        density = context.getResources().getDisplayMetrics().density;
        accent = context.getColor(R.color.radar_blue_primary);
        teal = context.getColor(R.color.primary);

        fill.setStyle(Paint.Style.FILL);
        stroke.setStyle(Paint.Style.STROKE);
        stroke.setStrokeCap(Paint.Cap.ROUND);
        stroke.setStrokeJoin(Paint.Join.ROUND);
        beam.setStyle(Paint.Style.STROKE);
        beam.setStrokeCap(Paint.Cap.ROUND);
        beam.setStrokeJoin(Paint.Join.ROUND);
    }

    float dp(float value) {
        return value * density;
    }

    private final Shader[] ambientShaders = new Shader[2];
    private final float[] ambientX = new float[2];
    private final float[] ambientY = new float[2];
    private final float[] ambientR = new float[2];
    private final int[] ambientColor = new int[2];

    void drawAmbient(Canvas canvas, int slot, float cx, float cy, float radius, int color, float breathe) {
        int i = Math.max(0, Math.min(slot, 1));
        if (ambientShaders[i] == null || cx != ambientX[i] || cy != ambientY[i]
                || radius != ambientR[i] || color != ambientColor[i]) {
            ambientX[i] = cx;
            ambientY[i] = cy;
            ambientR[i] = radius;
            ambientColor[i] = color;
            ambientShaders[i] = new RadialGradient(
                    cx, cy, radius,
                    new int[]{Color.argb(42, Color.red(color), Color.green(color), Color.blue(color)),
                            Color.TRANSPARENT},
                    new float[]{0.12f, 1f},
                    Shader.TileMode.CLAMP
            );
        }
        fill.setShader(ambientShaders[i]);
        fill.setAlpha((int) (170 + 70 * breathe));
        canvas.drawCircle(cx, cy, radius, fill);
        fill.setShader(null);
        fill.setAlpha(255);
    }

    void drawShadow(Canvas canvas, float cx, float cy, float rx, float ry) {
        fill.setColor(SHADOW);
        rect.set(cx - rx, cy - ry, cx + rx, cy + ry);
        canvas.drawOval(rect, fill);
    }

    void drawGuide(Canvas canvas, Path path, int color, float width, float alpha) {
        stroke.setShader(null);
        stroke.setColor(color);
        stroke.setStrokeWidth(width);
        stroke.setAlpha((int) (alpha * 255));
        canvas.drawPath(path, stroke);
        stroke.setAlpha(255);
    }

    /**
     * 沿路径走一段渐亮光带。span 为路径长度比例，约 0.2。
     */
    void drawBeam(Canvas canvas, PathMeasure measure, float progress, float span,
                  int color, float width) {
        float length = measure.getLength();
        if (length <= 1f) {
            return;
        }
        float head = ((progress % 1f) + 1f) % 1f * length;
        float tail = head - length * span;
        drawBeamSegment(canvas, measure, Math.max(0f, tail), head, color, width);
        if (tail < 0f) {
            drawBeamSegment(canvas, measure, length + tail, length, color, width);
        }
    }

    private void drawBeamSegment(Canvas canvas, PathMeasure measure, float start, float end,
                                 int color, float width) {
        if (end - start < 2f) {
            return;
        }
        segment.reset();
        measure.getSegment(start, end, segment, true);
        measure.getPosTan(end, pos, tan);
        beam.setShader(null);
        beam.setColor(color);
        beam.setStrokeWidth(width);
        beam.setAlpha(210);
        canvas.drawPath(segment, beam);
        beam.setAlpha(255);

        fill.setColor(Color.WHITE);
        fill.setAlpha(200);
        canvas.drawCircle(pos[0], pos[1], width * 0.42f, fill);
        fill.setAlpha(255);
    }

    void drawPhone(Canvas canvas, float cx, float cy, float w, float h,
                   float time, float breathe, int accentColor) {
        float radius = w * 0.22f;
        drawShadow(canvas, cx, cy + h * 0.42f, w * 0.42f, dp(6f));

        rect.set(cx - w / 2f, cy - h / 2f, cx + w / 2f, cy + h / 2f);
        fill.setColor(BODY);
        canvas.drawRoundRect(rect, radius, radius, fill);

        stroke.setColor(accentColor);
        stroke.setStrokeWidth(dp(1.35f));
        stroke.setAlpha((int) (110 + 50 * breathe));
        canvas.drawRoundRect(rect, radius, radius, stroke);
        stroke.setAlpha(255);

        // 内沿，给一点厚度
        rect.inset(dp(1.1f), dp(1.1f));
        stroke.setColor(RIM);
        stroke.setStrokeWidth(dp(0.8f));
        stroke.setAlpha(180);
        canvas.drawRoundRect(rect, radius * 0.86f, radius * 0.86f, stroke);

        // 顶边高光
        stroke.setColor(Color.WHITE);
        stroke.setAlpha(70);
        stroke.setStrokeWidth(dp(1f));
        canvas.drawLine(cx - w * 0.22f, cy - h / 2f + dp(2.4f),
                cx + w * 0.22f, cy - h / 2f + dp(2.4f), stroke);

        rect.set(cx - w / 2f + dp(3.2f), cy - h / 2f + dp(3.2f),
                cx + w / 2f - dp(3.2f), cy + h / 2f - dp(3.2f));
        ensurePhoneScreen(rect.left, rect.top, rect.right, rect.bottom);
        fill.setShader(phoneScreenShader);
        canvas.drawRoundRect(rect, radius * 0.7f, radius * 0.7f, fill);
        fill.setShader(null);

        float islandW = w * 0.32f;
        float islandH = Math.max(dp(3.2f), h * 0.035f);
        rect.set(cx - islandW / 2f, cy - h / 2f + dp(7.2f),
                cx + islandW / 2f, cy - h / 2f + dp(7.2f) + islandH);
        fill.setColor(Color.BLACK);
        canvas.drawRoundRect(rect, islandH, islandH, fill);

        drawScreenRows(canvas, cx, cy - h * 0.08f, w * 0.56f, h * 0.34f, time, accentColor, teal);

        float barW = w * 0.22f;
        rect.set(cx - barW / 2f, cy + h / 2f - dp(9.2f),
                cx + barW / 2f, cy + h / 2f - dp(7.1f));
        fill.setColor(Color.WHITE);
        fill.setAlpha(55);
        canvas.drawRoundRect(rect, dp(1.6f), dp(1.6f), fill);
        fill.setAlpha(255);
    }

    void drawLaptop(Canvas canvas, float cx, float cy, float screenW, float screenH, float deckH,
                    float time, float breathe, int accentColor) {
        float screenRadius = dp(5.5f);
        float screenTop = cy - screenH / 2f - deckH * 0.38f;
        float screenLeft = cx - screenW / 2f;
        float deckW = screenW * 1.16f;
        float deckTop = screenTop + screenH - dp(1.1f);

        drawShadow(canvas, cx, deckTop + deckH + dp(2f), deckW * 0.42f, dp(6f));

        rect.set(screenLeft, screenTop, screenLeft + screenW, screenTop + screenH);
        fill.setColor(BODY);
        canvas.drawRoundRect(rect, screenRadius, screenRadius, fill);
        stroke.setColor(accentColor);
        stroke.setStrokeWidth(dp(1.35f));
        stroke.setAlpha((int) (110 + 50 * breathe));
        canvas.drawRoundRect(rect, screenRadius, screenRadius, stroke);
        stroke.setAlpha(255);

        rect.inset(dp(3.8f), dp(3.8f));
        ensureLaptopScreen(rect.left, rect.top, rect.right, rect.bottom);
        fill.setShader(laptopScreenShader);
        canvas.drawRoundRect(rect, screenRadius * 0.55f, screenRadius * 0.55f, fill);
        fill.setShader(null);

        fill.setColor(Color.rgb(40, 52, 66));
        canvas.drawCircle(cx, screenTop + dp(6.2f), dp(1.5f), fill);

        drawScreenRows(canvas, cx, screenTop + screenH * 0.38f, screenW * 0.62f, screenH * 0.36f,
                time, accentColor, teal);

        rect.set(cx - deckW / 2f, deckTop, cx + deckW / 2f, deckTop + deckH);
        fill.setColor(BODY);
        canvas.drawRoundRect(rect, dp(3.2f), dp(3.2f), fill);
        stroke.setColor(accentColor);
        stroke.setAlpha(100);
        stroke.setStrokeWidth(dp(1.15f));
        canvas.drawRoundRect(rect, dp(3.2f), dp(3.2f), stroke);
        stroke.setAlpha(255);

        float padW = deckW * 0.26f;
        float padH = deckH * 0.36f;
        rect.set(cx - padW / 2f, deckTop + deckH * 0.4f,
                cx + padW / 2f, deckTop + deckH * 0.4f + padH);
        fill.setColor(accentColor);
        fill.setAlpha(22);
        canvas.drawRoundRect(rect, dp(1.4f), dp(1.4f), fill);
        fill.setAlpha(255);
    }

    void drawCable(Canvas canvas, Path path, float width, int color, float alpha) {
        stroke.setShader(null);
        stroke.setStrokeCap(Paint.Cap.ROUND);
        stroke.setColor(color);
        stroke.setStrokeWidth(width + dp(2.2f));
        stroke.setAlpha((int) (alpha * 40));
        canvas.drawPath(path, stroke);
        stroke.setColor(color);
        stroke.setStrokeWidth(width);
        stroke.setAlpha((int) (alpha * 200));
        canvas.drawPath(path, stroke);
        stroke.setAlpha(255);
    }

    void drawPlug(Canvas canvas, float x, float y, float w, float h, int color, float breathe) {
        rect.set(x - w / 2f, y - h / 2f, x + w / 2f, y + h / 2f);
        fill.setColor(color);
        fill.setAlpha((int) (150 + 50 * breathe));
        canvas.drawRoundRect(rect, h * 0.35f, h * 0.35f, fill);
        fill.setColor(Color.WHITE);
        fill.setAlpha(230);
        canvas.drawCircle(x, y, Math.min(w, h) * 0.18f, fill);
        fill.setAlpha(255);
    }

    private void drawScreenRows(Canvas canvas, float cx, float cy, float areaW, float areaH,
                                float time, int colorA, int colorB) {
        float rowH = Math.max(dp(2.6f), areaH * 0.16f);
        float gap = Math.max(dp(3.4f), areaH * 0.12f);
        float top = cy - (rowH * 3 + gap * 2) / 2f;
        float[] widths = {0.92f, 0.64f, 0.78f};
        for (int i = 0; i < 3; i++) {
            float pulse = 0.55f + 0.45f * (0.5f + 0.5f * (float) Math.sin(time * 1.15f + i * 0.85f));
            float width = areaW * widths[i];
            rect.set(cx - areaW / 2f, top + i * (rowH + gap),
                    cx - areaW / 2f + width, top + i * (rowH + gap) + rowH);
            fill.setColor(i == 1 ? colorB : colorA);
            fill.setAlpha((int) (55 + 70 * pulse));
            canvas.drawRoundRect(rect, rowH, rowH, fill);
        }
        fill.setAlpha(255);
    }

    private void ensurePhoneScreen(float l, float t, float r, float b) {
        if (phoneScreenShader != null
                && l == cachedPhoneL && t == cachedPhoneT && r == cachedPhoneR && b == cachedPhoneB) {
            return;
        }
        cachedPhoneL = l;
        cachedPhoneT = t;
        cachedPhoneR = r;
        cachedPhoneB = b;
        phoneScreenShader = new LinearGradient(l, t, l, b, SCREEN_TOP, SCREEN_BOT, Shader.TileMode.CLAMP);
    }

    private void ensureLaptopScreen(float l, float t, float r, float b) {
        if (laptopScreenShader != null
                && l == cachedLaptopL && t == cachedLaptopT && r == cachedLaptopR && b == cachedLaptopB) {
            return;
        }
        cachedLaptopL = l;
        cachedLaptopT = t;
        cachedLaptopR = r;
        cachedLaptopB = b;
        laptopScreenShader = new LinearGradient(l, t, l, b, SCREEN_TOP, SCREEN_BOT, Shader.TileMode.CLAMP);
    }
}
