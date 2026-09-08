package com.mk.androidtransfer.widget;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.SweepGradient;
import android.os.SystemClock;
import android.util.AttributeSet;
import android.view.MotionEvent;
import android.view.View;
import android.view.animation.LinearInterpolator;

import androidx.annotation.Nullable;

import com.mk.androidtransfer.R;
import com.mk.androidtransfer.model.ServerInfo;

import java.util.ArrayList;
import java.util.List;

/**
 * 雷达扫描动画。硬件加速绘制，避免软件层阴影把主线程打满。
 */
public class RadarScanView extends View {

    private Paint radarPaint;
    private Paint circlePaint;
    private Paint gridLinePaint;
    private Paint computerPaint;
    private Paint computerFillPaint;
    private Paint serverPulsePaint;
    private Paint serverLabelPaint;
    private Paint serverLabelBgPaint;
    private Bitmap laptopArt;
    private Bitmap phoneArt;
    private Bitmap androidArt;
    private final RectF labelRect = new RectF();
    private float centerX, centerY;
    private float radius;
    private float currentAngle = 0;
    private ValueAnimator radarAnimator;
    private float pulseRadius = 0;
    private float glowAlpha = 0.3f;
    private long animStartMs;

    private final List<ServerDot> serverDots = new ArrayList<>();
    private OnServerDotClickListener onServerDotClickListener;

    private int COLOR_RADAR_START;
    private int COLOR_RADAR_END;
    private int COLOR_CIRCLE;
    private int COLOR_SERVER_DOT;
    private int COLOR_SERVER_PULSE;
    private String serverNamePrefix;
    private String serverShortPrefix;

    public interface OnServerDotClickListener {
        void onServerDotClick(ServerDot serverDot);
    }

    public static class ServerDot {
        public String serverName;
        public String ip;
        public int port;
        public String engine;
        public float angle;
        public float distance;
        public long timestamp;
        public float scale = 0f;
        public float labelScale = 0f;
        public float x, y;
        public float labelX, labelY, labelWidth, labelHeight;

        public ServerDot(String serverName, String ip, int port, String engine,
                         float angle, float distance) {
            this.serverName = serverName;
            this.ip = ip;
            this.port = port;
            this.engine = engine == null ? "" : engine;
            this.angle = angle;
            this.distance = distance;
            this.timestamp = System.currentTimeMillis();
        }

        public ServerInfo toServerInfo() {
            return new ServerInfo(serverName, ip, port, engine);
        }
    }

    public RadarScanView(Context context) {
        super(context);
        init(context);
    }

    public RadarScanView(Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);
        init(context);
    }

    public RadarScanView(Context context, @Nullable AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        init(context);
    }

    private void init(Context context) {
        setLayerType(LAYER_TYPE_HARDWARE, null);
        COLOR_RADAR_START = context.getColor(R.color.radar_blue_sweep_start);
        COLOR_RADAR_END = context.getColor(R.color.radar_blue_sweep_end);
        COLOR_CIRCLE = context.getColor(R.color.radar_blue_circle);
        COLOR_SERVER_DOT = context.getColor(R.color.radar_blue_server_dot);
        COLOR_SERVER_PULSE = context.getColor(R.color.radar_blue_server_pulse);
        serverNamePrefix = context.getString(R.string.server_name_prefix);
        serverShortPrefix = context.getString(R.string.server_short_prefix);

        radarPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        radarPaint.setStyle(Paint.Style.FILL);

        circlePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        circlePaint.setStyle(Paint.Style.STROKE);
        circlePaint.setStrokeWidth(2f);
        circlePaint.setColor(COLOR_CIRCLE);

        gridLinePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        gridLinePaint.setStyle(Paint.Style.STROKE);
        gridLinePaint.setStrokeWidth(1f);
        gridLinePaint.setColor(COLOR_CIRCLE);
        gridLinePaint.setAlpha(100);

        serverPulsePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        serverPulsePaint.setStyle(Paint.Style.STROKE);
        serverPulsePaint.setStrokeWidth(2.4f);
        serverPulsePaint.setColor(COLOR_SERVER_PULSE);

        computerPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        computerPaint.setStyle(Paint.Style.STROKE);
        computerPaint.setStrokeWidth(2.6f);
        computerPaint.setStrokeJoin(Paint.Join.ROUND);
        computerPaint.setStrokeCap(Paint.Cap.ROUND);
        computerPaint.setColor(COLOR_SERVER_DOT);

        computerFillPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        computerFillPaint.setStyle(Paint.Style.FILL);
        computerFillPaint.setColor(COLOR_SERVER_DOT);

        serverLabelPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        serverLabelPaint.setTextSize(40f);
        serverLabelPaint.setColor(context.getColor(R.color.text_high_emphasis));
        serverLabelPaint.setTextAlign(Paint.Align.CENTER);
        serverLabelPaint.setFakeBoldText(true);
        serverLabelPaint.setShadowLayer(8f, 0, 2f, Color.argb(160, 0, 0, 0));

        serverLabelBgPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        serverLabelBgPaint.setStyle(Paint.Style.FILL);
        serverLabelBgPaint.setColor(Color.TRANSPARENT);

        laptopArt = BitmapFactory.decodeResource(getResources(), R.drawable.art_laptop);
        phoneArt = BitmapFactory.decodeResource(getResources(), R.drawable.art_phone);
        androidArt = BitmapFactory.decodeResource(getResources(), R.drawable.art_android);
    }

    private void drawComputer(Canvas canvas, float x, float y) {
        float w = 22f;
        float h = 14f;
        labelRect.set(x - w / 2f, y - h / 2f - 3f, x + w / 2f, y + h / 2f - 3f);
        computerFillPaint.setAlpha(36);
        canvas.drawRoundRect(labelRect, 3f, 3f, computerFillPaint);
        computerPaint.setAlpha(230);
        canvas.drawRoundRect(labelRect, 3f, 3f, computerPaint);
        canvas.drawLine(x, y + h / 2f - 1f, x, y + h / 2f + 6f, computerPaint);
        canvas.drawLine(x - 11f, y + h / 2f + 6f, x + 11f, y + h / 2f + 6f, computerPaint);
    }

    private void drawDevice(Canvas canvas, float x, float y, Bitmap art, float size) {
        if (art == null) {
            drawComputer(canvas, x, y);
            return;
        }
        labelRect.set(x - size / 2f, y - size / 2f,
                x + size / 2f, y + size / 2f);
        canvas.drawBitmap(art, null, labelRect, null);
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        centerX = w / 2f;
        centerY = h / 2f;
        radius = Math.min(w, h) / 2f;
        updateShader();
    }

    private void updateShader() {
        Shader shader = new SweepGradient(centerX, centerY,
                new int[]{COLOR_RADAR_END, COLOR_RADAR_START, COLOR_RADAR_END},
                new float[]{0f, 0.18f, 0.42f});
        radarPaint.setShader(shader);
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);

        float[] circleRadii = {radius * 0.20f, radius * 0.40f, radius * 0.60f, radius * 0.80f, radius * 0.95f};
        for (int i = 0; i < circleRadii.length; i++) {
            circlePaint.setStrokeWidth(i == circleRadii.length - 1 ? 2.5f : 1.6f);
            circlePaint.setAlpha(i == circleRadii.length - 1 ? 90 : 48);
            canvas.drawCircle(centerX, centerY, circleRadii[i], circlePaint);
        }

        gridLinePaint.setAlpha(42);
        canvas.drawLine(centerX, centerY - radius * 0.95f, centerX, centerY + radius * 0.95f, gridLinePaint);
        canvas.drawLine(centerX - radius * 0.95f, centerY, centerX + radius * 0.95f, centerY, gridLinePaint);

        float diagonalLength = radius * 0.95f * 0.707f;
        canvas.drawLine(centerX - diagonalLength, centerY - diagonalLength,
                centerX + diagonalLength, centerY + diagonalLength, gridLinePaint);
        canvas.drawLine(centerX - diagonalLength, centerY + diagonalLength,
                centerX + diagonalLength, centerY - diagonalLength, gridLinePaint);

        drawServerDots(canvas);

        canvas.save();
        canvas.rotate(currentAngle, centerX, centerY);
        radarPaint.setAlpha((int) (90 + glowAlpha * 80));
        canvas.drawCircle(centerX, centerY, radius, radarPaint);
        canvas.restore();

        // Android 端雷达中心代表本机，不能再用电脑图标。
        drawDevice(canvas, centerX, centerY, androidArt, 64f);
    }

    private void drawServerDots(Canvas canvas) {
        for (ServerDot dot : serverDots) {
            double angleRad = Math.toRadians(dot.angle);
            float x = centerX + (float) (Math.cos(angleRad) * radius * dot.distance);
            float y = centerY + (float) (Math.sin(angleRad) * radius * dot.distance);
            dot.x = x;
            dot.y = y;

            if (dot.scale < 1.0f) {
                dot.scale = Math.min(1.0f, dot.scale + 0.05f);
            }
            if (dot.scale >= 0.8f && dot.labelScale < 1.0f) {
                dot.labelScale = Math.min(1.0f, dot.labelScale + 0.08f);
            }

            float pulse = (float) ((Math.sin(SystemClock.elapsedRealtime() / 420.0 + dot.angle) + 1) * 0.5);
            float ring = 18 + pulse * 16;
            serverPulsePaint.setAlpha((int) ((1f - pulse) * 90 + 30));
            canvas.drawCircle(x, y, ring * dot.scale, serverPulsePaint);
            serverPulsePaint.setAlpha((int) ((1f - pulse) * 50));
            canvas.drawCircle(x, y, (ring + 10) * dot.scale, serverPulsePaint);

            canvas.save();
            canvas.scale(dot.scale, dot.scale, x, y);
            drawDevice(canvas, x, y, iconFor(dot), 58f);
            canvas.restore();

            if (dot.scale >= 0.8f && dot.labelScale > 0f) {
                drawServerLabel(canvas, dot, x, y);
            }
        }
    }

    private void drawServerLabel(Canvas canvas, ServerDot dot, float dotX, float dotY) {
        String displayName = displayNameOf(dot);
        float textWidth = serverLabelPaint.measureText(displayName);
        float padding = 24f;
        float labelWidth = textWidth + padding * 2;
        float labelHeight = 64f;
        float labelX = dotX;
        float labelY = dotY - 45f;
        if (labelY - labelHeight / 2 < centerY - radius) {
            labelY = dotY + 45f;
        }

        // 靠边的设备名以前会被画到屏幕外（只剩半个名字），这里把标签收回可视范围内
        float margin = 12f;
        float half = labelWidth / 2f;
        if (labelX - half < margin) {
            labelX = margin + half;
        }
        if (labelX + half > getWidth() - margin) {
            labelX = getWidth() - margin - half;
        }

        float t = dot.labelScale;
        float easedScale = t * t * ((1.70158f + 1f) * t - 1.70158f) + 1f;
        if (easedScale < 0) easedScale = 0;
        if (easedScale > 1.2f) easedScale = 1.0f;

        float scaledWidth = labelWidth * easedScale;
        float scaledHeight = labelHeight * easedScale;
        dot.labelX = labelX - labelWidth / 2;
        dot.labelY = labelY - labelHeight / 2;
        dot.labelWidth = labelWidth;
        dot.labelHeight = labelHeight;

        labelRect.set(
                labelX - scaledWidth / 2,
                labelY - scaledHeight / 2,
                labelX + scaledWidth / 2,
                labelY + scaledHeight / 2
        );

        int alphaValue = (int) (dot.labelScale * 255);
        serverLabelPaint.setAlpha(alphaValue);
        Paint.FontMetrics fm = serverLabelPaint.getFontMetrics();
        float textY = labelY - (fm.ascent + fm.descent) / 2;
        canvas.drawText(displayName, labelX, textY, serverLabelPaint);
        serverLabelPaint.setAlpha(255);
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        if (event.getAction() == MotionEvent.ACTION_DOWN) {
            float touchX = event.getX();
            float touchY = event.getY();

            for (ServerDot dot : serverDots) {
                if (touchX >= dot.labelX && touchX <= dot.labelX + dot.labelWidth &&
                        touchY >= dot.labelY && touchY <= dot.labelY + dot.labelHeight) {
                    if (onServerDotClickListener != null) {
                        onServerDotClickListener.onServerDotClick(dot);
                    }
                    return true;
                }

                float distance = (float) Math.sqrt(
                        Math.pow(touchX - dot.x, 2) + Math.pow(touchY - dot.y, 2)
                );
                if (distance <= 96 * Math.max(dot.scale, 0.6f)) {
                    if (onServerDotClickListener != null) {
                        onServerDotClickListener.onServerDotClick(dot);
                    }
                    return true;
                }
            }
        }
        return super.onTouchEvent(event);
    }

    private String displayNameOf(ServerDot dot) {
        String name = dot.serverName;
        if (name != null && name.startsWith(serverNamePrefix)) {
            name = name.replace(serverNamePrefix + " ", serverShortPrefix);
        }
        if (name == null) {
            return "";
        }
        // 名字太长时截断：雷达最多也就放得下这么宽，硬画只会糊成一片
        float max = getWidth() * 0.62f;
        if (max > 0 && serverLabelPaint.measureText(name) > max) {
            while (name.length() > 4 && serverLabelPaint.measureText(name + "…") > max) {
                name = name.substring(0, name.length() - 1);
            }
            name = name + "…";
        }
        return name;
    }

    public void setOnServerDotClickListener(OnServerDotClickListener listener) {
        this.onServerDotClickListener = listener;
    }

    public void startScanning() {
        if (radarAnimator != null && radarAnimator.isRunning()) {
            return;
        }
        animStartMs = android.os.SystemClock.uptimeMillis();
        radarAnimator = ValueAnimator.ofFloat(0f, 360f);
        radarAnimator.setDuration(2400);
        radarAnimator.setRepeatCount(ValueAnimator.INFINITE);
        radarAnimator.setInterpolator(new LinearInterpolator());
        radarAnimator.addUpdateListener(animation -> {
            currentAngle = (float) animation.getAnimatedValue();
            long elapsed = android.os.SystemClock.uptimeMillis() - animStartMs;
            pulseRadius = (elapsed % 1500) / 1500f * 30f;
            glowAlpha = 0.3f + 0.2f * (0.5f + 0.5f * (float) Math.sin(elapsed / 2000f * Math.PI * 2));
            postInvalidateOnAnimation();
        });
        radarAnimator.start();
    }

    public void stopScanning() {
        if (radarAnimator != null) {
            radarAnimator.cancel();
            radarAnimator = null;
        }
    }

    public void addServerDot(ServerInfo serverInfo) {
        for (ServerDot dot : serverDots) {
            if (dot.ip.equals(serverInfo.getIp())) {
                return;
            }
        }

        float angle = (float) (Math.random() * 360);
        float distance = 0.4f + (float) (Math.random() * 0.5f);
        serverDots.add(new ServerDot(
                serverInfo.getName(),
                serverInfo.getIp(),
                serverInfo.getPort(),
                serverInfo.getEngine(),
                angle,
                distance
        ));
        invalidate();
    }

    public void updateServerDot(ServerInfo serverInfo) {
        for (ServerDot dot : serverDots) {
            if (dot.ip.equals(serverInfo.getIp()) && dot.port == serverInfo.getPort()) {
                dot.serverName = serverInfo.getName();
                dot.engine = serverInfo.getEngine();
                invalidate();
                return;
            }
        }
    }

    private Bitmap iconFor(ServerDot dot) {
        if ("android".equalsIgnoreCase(dot.engine) || "direct".equalsIgnoreCase(dot.engine)) {
            // direct = Wi-Fi 直连上发现的手机，还没入网，图标和安卓机一样
            return androidArt;
        }
        if ("swift".equalsIgnoreCase(dot.engine)) {
            return phoneArt;
        }
        // 9600 是手机服务端的固定端口，供旧版本或探测失败时兜底。
        return dot.port == 9600 ? phoneArt : laptopArt;
    }

    public void removeServerDot(String ip) {
        serverDots.removeIf(dot -> dot.ip.equals(ip));
        invalidate();
    }

    public void clearServerDots() {
        serverDots.clear();
        invalidate();
    }

    public int getServerDotCount() {
        return serverDots.size();
    }

    public boolean isScanning() {
        return radarAnimator != null && radarAnimator.isRunning();
    }

    @Override
    protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        stopScanning();
    }
}
