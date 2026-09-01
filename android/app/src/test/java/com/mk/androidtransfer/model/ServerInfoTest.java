package com.mk.androidtransfer.model;

import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class ServerInfoTest {
    @Test
    public void androidEngineIdentifiesModelWithoutPhoneName() {
        ServerInfo info = new ServerInfo("PLK110", "192.168.10.13", 36107, "android");

        assertTrue(info.isAndroidPhone());
        assertTrue(info.isPhone());
    }

    @Test
    public void swiftEngineIdentifiesIPhone() {
        ServerInfo info = new ServerInfo("iPhone", "192.168.10.14", 9600, "swift");

        assertTrue(info.isIPhone());
        assertTrue(info.isPhone());
    }

    @Test
    public void goEngineRemainsDesktop() {
        ServerInfo info = new ServerInfo("卓传电脑", "192.168.10.20", 9500, "go");

        assertFalse(info.isAndroidPhone());
        assertFalse(info.isIPhone());
        assertFalse(info.isPhone());
    }
}
