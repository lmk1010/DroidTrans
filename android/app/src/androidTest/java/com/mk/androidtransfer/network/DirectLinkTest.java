package com.mk.androidtransfer.network;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import android.os.Build;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import androidx.test.rule.GrantPermissionRule;

import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 直连那条路在「这台机器根本开不了」的时候该怎么表现。
 *
 * <p>模拟器没有 Wi-Fi 直连的射频，厂商 ROM 里也有一大票会直接拒绝 ——
 * 那正是这组用例要盯的：<b>不许崩，也不许把一个错误码甩给用户</b>，
 * 必须落到一句人话上，否则用户面对的是一个点了没反应的按钮。
 */
@RunWith(AndroidJUnit4.class)
public class DirectLinkTest {

    @Rule
    public GrantPermissionRule permissions = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
            ? GrantPermissionRule.grant(android.Manifest.permission.NEARBY_WIFI_DEVICES)
            : GrantPermissionRule.grant(android.Manifest.permission.ACCESS_FINE_LOCATION);

    @Test
    public void hotspotAlwaysSettlesOnSomethingSayable() throws Exception {
        final CountDownLatch done = new CountDownLatch(1);
        final AtomicReference<String> failure = new AtomicReference<>();
        final AtomicReference<String> ssid = new AtomicReference<>();

        final DirectHotspot hotspot = new DirectHotspot(
                InstrumentationRegistry.getInstrumentation().getTargetContext());
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() ->
                hotspot.start("测试机", PeerServer.PORT, new DirectHotspot.Callback() {
                    @Override
                    public void onStarted(String s, String password) {
                        ssid.set(s);
                        done.countDown();
                    }

                    @Override
                    public void onFailed(String msg) {
                        failure.set(msg);
                        done.countDown();
                    }

                    @Override
                    public void onStopped() {
                    }
                }));

        // 两条路（Wi-Fi Direct → LocalOnlyHotspot）都试完也该有个结论
        assertTrue("按了直连之后什么都没发生，用户面对的是一个死按钮",
                done.await(45, TimeUnit.SECONDS));
        try {
            if (ssid.get() != null) {
                assertFalse("热点起来了却没有名字，二维码里就没法带入网信息",
                        ssid.get().isEmpty());
            } else {
                String msg = failure.get();
                assertNotNull(msg);
                assertFalse("失败要给一句人话", msg.trim().isEmpty());
                // 「reason 2」这种直接甩给用户等于没说
                assertFalse("别把错误码当提示语", msg.matches(".*\\breason\\s*\\d+.*"));
            }
        } finally {
            InstrumentationRegistry.getInstrumentation().runOnMainSync(hotspot::stop);
        }
    }

    /** 没有 P2P 的机器上，发现那一路要安静地退场，不能把整屏拖崩。 */
    @Test
    public void finderDegradesQuietly() throws Exception {
        final DirectFinder finder = new DirectFinder(
                InstrumentationRegistry.getInstrumentation().getTargetContext());
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() ->
                finder.start(new DirectFinder.Listener() {
                    @Override
                    public void onFound(String name, String address, int port) {
                    }

                    @Override
                    public void onConnected(String host, int port) {
                    }

                    @Override
                    public void onFailed(String msg) {
                    }
                }));
        Thread.sleep(2000);
        InstrumentationRegistry.getInstrumentation().runOnMainSync(finder::stop);
    }
}
