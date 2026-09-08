package com.mk.androidtransfer;

import android.app.Activity;
import android.app.Application;
import android.os.Build;
import android.os.Bundle;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.mk.androidtransfer.network.PeerServer;

import java.io.File;

/**
 * App 一打开就能被别人找到。
 *
 * <p>原来的模型是：一台必须先点进「我要收」，另一台才发现得了它。
 * 于是「我要发」那一步经常什么都扫不到 —— 而用户完全不知道
 * 还得让对面先做一个动作，只觉得这东西连不上。
 *
 * <p>现在换成：App 在前台就监听 9600 并广播自己，任何一屏都行。
 * 对面点你一下，这里弹「XXX 想连过来」，同意就开始收，界面自己跳到接收页。
 *
 * <p>退到后台就停：手机不是常驻服务器，让它在后台一直挂着监听既费电，
 * 也不是用户预期里会发生的事。
 */
public final class PeerHost implements Application.ActivityLifecycleCallbacks {

    private static PeerHost instance;

    private final Application app;
    private final PeerServer server;
    private Activity current;
    private int started;
    /** 用户点了「停止接收」。这时候不再监听，直到他重新开始。 */
    private boolean paused;

    /**
     * 接收界面。收到的每一件事都要转给它 —— 这就是「我在发，
     * 对面却一直显示等待」的根子：连接是常驻监听接下的，
     * 而界面盯着的是另一个服务，两边各说各话。
     */
    public interface Ui {
        void onState(boolean running, String error);

        void onKnock(String who);

        void onPeerConnected(String name);

        void onFileProgress(String name, long received, long total);

        void onFileReceived(String name, long size, File file);
    }

    private Ui ui;

    private PeerHost(Application app) {
        this.app = app;
        this.server = new PeerServer(app);
    }

    public static synchronized PeerHost install(Application app) {
        if (instance == null) {
            instance = new PeerHost(app);
            app.registerActivityLifecycleCallbacks(instance);
        }
        return instance;
    }

    public static PeerHost get() {
        return instance;
    }

    /** 接收界面上来接手：之后的敲门、进度、落盘都转给它。 */
    public void setUi(Ui ui) {
        this.ui = ui;
        if (ui != null) {
            ui.onState(server.isRunning(), null);
        }
    }

    public void clearUi(Ui who) {
        if (ui == who) {
            ui = null;
        }
    }

    /** 用户点了「停止接收」。 */
    public void pause() {
        paused = true;
        server.stop();
    }

    /** 用户点了「我要收」/「继续接收」。 */
    public void resume() {
        paused = false;
        startServing();
    }

    public boolean isRunning() {
        return server.isRunning();
    }

    public String pairingCode() {
        return server.getPairingCode();
    }

    public int boundPort() {
        return server.getBoundPort();
    }

    public String localIp() {
        return server.localIp();
    }

    public File inboxDir() {
        return server.inboxDir();
    }

    public void approve() {
        server.approve();
    }

    public void deny() {
        server.deny();
    }

    private void startServing() {
        if (paused || server.isRunning()) {
            return;
        }
        server.start(deviceName(), new PeerServer.Listener() {
            @Override
            public void onStateChanged(boolean running, String error) {
                if (ui != null) {
                    ui.onState(running, error);
                }
            }

            @Override
            public void onFileProgress(String name, long received, long total) {
                if (ui != null) {
                    ui.onFileProgress(name, received, total);
                }
            }

            @Override
            public void onFileReceived(String name, long size, File file) {
                if (ui != null) {
                    ui.onFileReceived(name, size, file);
                }
            }

            @Override
            public void onPeerConnected(String name) {
                if (ui != null) {
                    ui.onPeerConnected(name);
                }
            }

            @Override
            public void onKnock(String who) {
                // 接收界面开着就让它自己弹（它还要把页面切到接收态）；
                // 在别的界面时由这里弹一个全局的
                if (ui != null) {
                    ui.onKnock(who);
                } else {
                    askApproval(who);
                }
            }
        });
    }

    /**
     * 有人在敲门。把关就在这一下 —— 设备名是对面自己报的，
     * 真正决定开不开门的是拿着这台手机的人。
     */
    private void askApproval(String who) {
        Activity a = current;
        if (a == null || a.isFinishing()) {
            server.deny();
            return;
        }
        new com.google.android.material.dialog.MaterialAlertDialogBuilder(a)
                .setTitle(a.getString(R.string.peer_knock_title, who))
                .setMessage(R.string.peer_knock_body)
                .setCancelable(false)
                .setNegativeButton(R.string.peer_knock_deny, (d, w) -> server.deny())
                .setPositiveButton(R.string.peer_knock_allow, (d, w) -> {
                    server.approve();
                    // 同意之后直接把接收页亮出来，东西进来时用户看得见
                    a.startActivity(new android.content.Intent(a, PeerActivity.class)
                            .putExtra(PeerActivity.EXTRA_TAKE_OVER, true));
                })
                .show();
    }

    private String deviceName() {
        String n = Build.MODEL;
        return n == null || n.isEmpty() ? "Android" : n;
    }

    // ------------------------------------------------------- 前台就监听

    @Override
    public void onActivityStarted(@NonNull Activity activity) {
        started++;
        current = activity;
        startServing();
    }

    @Override
    public void onActivityResumed(@NonNull Activity activity) {
        current = activity;
    }

    @Override
    public void onActivityStopped(@NonNull Activity activity) {
        started--;
        if (started <= 0) {
            started = 0;
            server.stop();
        }
    }

    @Override
    public void onActivityCreated(@NonNull Activity a, @Nullable Bundle b) {
    }

    @Override
    public void onActivityPaused(@NonNull Activity a) {
    }

    @Override
    public void onActivitySaveInstanceState(@NonNull Activity a, @NonNull Bundle b) {
    }

    @Override
    public void onActivityDestroyed(@NonNull Activity a) {
    }
}
