package com.mk.androidtransfer;

import android.app.Application;

/**
 * 进程入口。这里只做一件事：让这台手机在 App 打开时就能被别人找到。
 * 见 {@link PeerHost}。
 */
public class App extends Application {

    @Override
    public void onCreate() {
        super.onCreate();
        PeerHost.install(this);
    }
}
