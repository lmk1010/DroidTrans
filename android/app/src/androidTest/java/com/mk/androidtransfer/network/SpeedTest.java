package com.mk.androidtransfer.network;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import android.os.Bundle;
import android.util.Log;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import androidx.test.rule.GrantPermissionRule;

import org.junit.Assume;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.IOException;
import java.io.InputStream;

/**
 * 真机上的实测速率。不给参数就跳过。
 *
 * <pre>
 *   ANDROID_SERIAL=<真机> ./gradlew connectedDebugAndroidTest \
 *     -Pandroid.testInstrumentationRunnerArguments.class=com.mk.androidtransfer.network.SpeedTest \
 *     -Pandroid.testInstrumentationRunnerArguments.host=192.168.10.15:9500 \
 *     -Pandroid.testInstrumentationRunnerArguments.code=187931
 * </pre>
 *
 * <p>发的是**内存里现造的字节流**，不在用户手机上落一个几十兆的临时文件；
 * 走的是生产代码那条 {@link FastTransferClient}，通道也由
 * {@link ProtocolSelector} 自己选，测出来的才是用户真会遇到的速率。
 */
@RunWith(AndroidJUnit4.class)
public class SpeedTest {

    private static final String TAG = "DroidTransSpeed";

    @Rule
    public GrantPermissionRule photos = android.os.Build.VERSION.SDK_INT >= 33
            ? GrantPermissionRule.grant(android.Manifest.permission.READ_MEDIA_IMAGES)
            : GrantPermissionRule.grant(android.Manifest.permission.READ_EXTERNAL_STORAGE);

    private Context ctx() {
        return InstrumentationRegistry.getInstrumentation().getTargetContext();
    }

    @Test
    public void uploadsToPeer() throws Exception {
        Bundle args = InstrumentationRegistry.getArguments();
        String host = args.getString("host");
        Assume.assumeNotNull(host);
        String base = "http://" + host;
        long mb = Long.parseLong(args.getString("mb", "64"));

        // 配对：电脑给六位码，手机（对面是接收端）走敲门
        String code = args.getString("code");
        if (code != null && !code.isEmpty()) {
            assertTrue("配对没通过，码对不对？", Pairing.pair(ctx(), base, code));
        } else {
            assertTrue("敲门没被同意", Pairing.knock(ctx(), base));
        }
        String token = Pairing.token(ctx(), Pairing.normalize(base));
        assertNotNull("没拿到令牌", token);
        RetrofitClient.setToken(token);

        ProtocolSelector.Choice choice = ProtocolSelector.select(base);
        Log.i(TAG, "通道 = " + choice.protocol + " → " + choice.host
                + " http:" + choice.httpPort + " tcp:" + choice.tcpPort);

        final long size = mb * 1024 * 1024;
        final String name = "droidtrans-speedtest-" + mb + "m.bin";
        long t0 = System.nanoTime();
        FastTransferClient.sendGenerated(choice, name, size, new Zeros(size), deviceId(),
                RetrofitClient.getInstance(base).getOkHttpClient(), null);
        double sec = (System.nanoTime() - t0) / 1e9;
        double mbps = mb / sec;

        Log.i(TAG, String.format(java.util.Locale.US,
                "RESULT %s %d MB in %.2f s = %.1f MB/s (%.0f Mbps)",
                choice.protocol, mb, sec, mbps, mbps * 8));
        assertTrue("传完了但用时为 0，测出来的数不可信", sec > 0);
    }

    /**
     * 连着发好几个，看是不是「只有第一个顺畅」。
     *
     * <p>用户实测反馈：第一次传很顺，之后就卡住或者被对面中断。
     * 一次只发一个文件的用例永远测不到这种事 —— 它每次都是「第一次」。
     */
    @Test
    public void sendsSeveralInARow() throws Exception {
        Bundle args = InstrumentationRegistry.getArguments();
        String host = args.getString("host");
        Assume.assumeNotNull(host);
        String base = "http://" + host;
        int rounds = Integer.parseInt(args.getString("rounds", "5"));
        long mb = Long.parseLong(args.getString("mb", "8"));

        String code = args.getString("code");
        if (code != null && !code.isEmpty()) {
            assertTrue(Pairing.pair(ctx(), base, code));
        } else {
            assertTrue(Pairing.knock(ctx(), base));
        }
        RetrofitClient.setToken(Pairing.token(ctx(), Pairing.normalize(base)));

        StringBuilder report = new StringBuilder();
        for (int i = 1; i <= rounds; i++) {
            ProtocolSelector.Choice choice = ProtocolSelector.select(base);
            String name = "round-" + i + "-" + System.currentTimeMillis() + ".bin";
            long size = mb * 1024 * 1024;
            long t0 = System.nanoTime();
            String outcome;
            try {
                FastTransferClient.sendGenerated(choice, name, size, new Zeros(size),
                        deviceId(), RetrofitClient.getInstance(base).getOkHttpClient(), null);
                double sec = (System.nanoTime() - t0) / 1e9;
                outcome = String.format(java.util.Locale.US, "第 %d 次 %s %.1fs %.1fMB/s",
                        i, choice.protocol, sec, mb / sec);
            } catch (Exception e) {
                double sec = (System.nanoTime() - t0) / 1e9;
                outcome = String.format(java.util.Locale.US, "第 %d 次 %s 第 %.1fs 失败：%s",
                        i, choice.protocol, sec, String.valueOf(e.getMessage()));
            }
            Log.i(TAG, "RESULT " + outcome);
            report.append(outcome).append('\n');
        }
        Log.i(TAG, "RESULT 连发汇总\n" + report);
    }

    /** 现造的字节流。内容不重要，量重要 —— 不落盘就不动用户的存储。 */
    private static final class Zeros extends InputStream {
        private final byte[] block = new byte[64 * 1024];
        private long left;

        Zeros(long total) {
            left = total;
            // 全 0 会被某些链路压掉，填点花样，测出来的才是真的吞吐
            for (int i = 0; i < block.length; i++) {
                block[i] = (byte) (i * 31 + 7);
            }
        }

        @Override
        public int read() {
            if (left <= 0) {
                return -1;
            }
            left--;
            return block[0] & 0xff;
        }

        @Override
        public int read(byte[] b, int off, int len) throws IOException {
            if (left <= 0) {
                return -1;
            }
            int n = (int) Math.min(Math.min(len, block.length), left);
            System.arraycopy(block, 0, b, off, n);
            left -= n;
            return n;
        }
    }

    private String deviceId() {
        return "speedtest";
    }

    /** 顺带把一张真照片也传过去 —— 用户实际会做的就是这件事。 */
    @Test
    public void uploadsARealPhoto() throws Exception {
        Bundle args = InstrumentationRegistry.getArguments();
        String host = args.getString("host");
        Assume.assumeNotNull(host);
        String base = "http://" + host;

        String code = args.getString("code");
        if (code != null && !code.isEmpty()) {
            assertTrue(Pairing.pair(ctx(), base, code));
        } else {
            assertTrue(Pairing.knock(ctx(), base));
        }
        RetrofitClient.setToken(Pairing.token(ctx(), Pairing.normalize(base)));

        String[] cols = {android.provider.MediaStore.Images.Media._ID,
                android.provider.MediaStore.Images.Media.DISPLAY_NAME,
                android.provider.MediaStore.Images.Media.SIZE};
        try (android.database.Cursor c = ctx().getContentResolver().query(
                android.provider.MediaStore.Images.Media.EXTERNAL_CONTENT_URI, cols,
                null, null,
                android.provider.MediaStore.Images.Media.DATE_ADDED + " DESC LIMIT 1")) {
            assertNotNull(c);
            Assume.assumeTrue("这台手机相册里没有照片", c.moveToFirst());
            long id = c.getLong(0);
            String name = c.getString(1);
            long size = c.getLong(2);
            android.net.Uri uri = android.content.ContentUris.withAppendedId(
                    android.provider.MediaStore.Images.Media.EXTERNAL_CONTENT_URI, id);

            ProtocolSelector.Choice choice = ProtocolSelector.select(base);
            try (InputStream in = ctx().getContentResolver().openInputStream(uri)) {
                assertNotNull(in);
                long t0 = System.nanoTime();
                FastTransferClient.sendGenerated(choice, name, size, in, deviceId(),
                        RetrofitClient.getInstance(base).getOkHttpClient(), null);
                double sec = (System.nanoTime() - t0) / 1e9;
                Log.i(TAG, String.format(java.util.Locale.US,
                        "RESULT photo %s %d 字节 in %.2f s", name, size, sec));
            }
        }
    }

    private void unusedKeepEqualsImport() {
        assertEquals(1, 1);
    }
}
