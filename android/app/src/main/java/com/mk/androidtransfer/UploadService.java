package com.mk.androidtransfer;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.IBinder;
import android.os.PowerManager;

import androidx.core.app.NotificationCompat;

/**
 * 上传期间的前台服务。
 *
 * <p>上传原本完全跑在 {@link UploadProgressActivity} 的线程池里：一旦锁屏、切到别的 App，
 * 系统会压制后台进程的网络和 CPU，传到一半就停住。这个服务把上传期间的进程钉成前台优先级，
 * 并持一个 partial wakelock，同时把进度放到通知栏——用户不用守着这个页面。
 *
 * <p>它不负责传输逻辑，只负责「让传输能一直跑」和「在外面能看到进度」。
 */
public class UploadService extends Service {

    private static final String CHANNEL_ID = "droidtrans_upload";
    private static final int NOTIFICATION_ID = 4101;

    private static final String ACTION_START = "com.mk.androidtransfer.UPLOAD_START";
    private static final String ACTION_UPDATE = "com.mk.androidtransfer.UPLOAD_UPDATE";
    private static final String ACTION_DONE = "com.mk.androidtransfer.UPLOAD_DONE";

    private static final String EXTRA_DONE = "done";
    private static final String EXTRA_TOTAL = "total";
    private static final String EXTRA_TEXT = "text";
    private static final String EXTRA_ONGOING = "ongoing";

    private PowerManager.WakeLock wakeLock;

    public static void start(Context context, int total) {
        Intent i = new Intent(context, UploadService.class)
                .setAction(ACTION_START)
                .putExtra(EXTRA_TOTAL, total);
        androidx.core.content.ContextCompat.startForegroundService(context, i);
    }

    public static void update(Context context, int done, int total, String text) {
        Intent i = new Intent(context, UploadService.class)
                .setAction(ACTION_UPDATE)
                .putExtra(EXTRA_DONE, done)
                .putExtra(EXTRA_TOTAL, total)
                .putExtra(EXTRA_TEXT, text);
        androidx.core.content.ContextCompat.startForegroundService(context, i);
    }

    /** 传完了：留一条可划掉的结果通知，然后退出前台。 */
    public static void finish(Context context, String text) {
        Intent i = new Intent(context, UploadService.class)
                .setAction(ACTION_DONE)
                .putExtra(EXTRA_TEXT, text);
        androidx.core.content.ContextCompat.startForegroundService(context, i);
    }

    public static void stop(Context context) {
        context.stopService(new Intent(context, UploadService.class));
    }

    @Override
    public void onCreate() {
        super.onCreate();
        createChannel();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        String action = intent != null ? intent.getAction() : ACTION_START;
        if (ACTION_DONE.equals(action)) {
            String text = intent.getStringExtra(EXTRA_TEXT);
            NotificationManager nm = getSystemService(NotificationManager.class);
            if (nm != null) {
                nm.notify(NOTIFICATION_ID, build(text, 0, 0, false));
            }
            releaseWakeLock();
            stopForeground(STOP_FOREGROUND_DETACH);
            stopSelf();
            return START_NOT_STICKY;
        }

        int done = intent != null ? intent.getIntExtra(EXTRA_DONE, 0) : 0;
        int total = intent != null ? intent.getIntExtra(EXTRA_TOTAL, 0) : 0;
        String text = intent != null ? intent.getStringExtra(EXTRA_TEXT) : null;
        if (text == null) {
            text = getString(R.string.notif_upload_preparing);
        }
        startForeground(NOTIFICATION_ID, build(text, done, total, true));
        acquireWakeLock();
        // 进程被系统回收后不自动重启：上传状态在 Activity 里，重启一个空服务没有意义
        return START_NOT_STICKY;
    }

    @Override
    public void onDestroy() {
        releaseWakeLock();
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    private Notification build(String text, int done, int total, boolean ongoing) {
        Intent open = new Intent(this, UploadProgressActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        PendingIntent pi = PendingIntent.getActivity(
                this, 0, open, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        NotificationCompat.Builder b = new NotificationCompat.Builder(this, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.stat_sys_upload)
                .setContentTitle(getString(ongoing ? R.string.notif_uploading : R.string.notif_upload_done))
                .setContentText(text)
                .setContentIntent(pi)
                .setOnlyAlertOnce(true)
                .setOngoing(ongoing)
                .setPriority(NotificationCompat.PRIORITY_LOW);
        if (ongoing && total > 0) {
            b.setProgress(total, Math.min(done, total), false);
        }
        return b.build();
    }

    private void createChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            return;
        }
        NotificationManager nm = getSystemService(NotificationManager.class);
        if (nm == null || nm.getNotificationChannel(CHANNEL_ID) != null) {
            return;
        }
        NotificationChannel ch = new NotificationChannel(
                CHANNEL_ID, getString(R.string.notif_channel_upload), NotificationManager.IMPORTANCE_LOW);
        ch.setDescription(getString(R.string.notif_channel_upload_desc));
        ch.setShowBadge(false);
        nm.createNotificationChannel(ch);
    }

    private void acquireWakeLock() {
        if (wakeLock != null && wakeLock.isHeld()) {
            return;
        }
        PowerManager pm = getSystemService(PowerManager.class);
        if (pm == null) {
            return;
        }
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "DroidTrans:upload");
        wakeLock.setReferenceCounted(false);
        // 兜底 2 小时，防止异常路径下没释放
        wakeLock.acquire(2 * 60 * 60 * 1000L);
    }

    private void releaseWakeLock() {
        if (wakeLock != null && wakeLock.isHeld()) {
            wakeLock.release();
        }
        wakeLock = null;
    }
}
