package com.mk.androidtransfer.network;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * 接收端二维码的格式。iOS 端 PeerLink.swift 是同一份格式，
 * 两边对不上的表现是「扫了没反应」——最难查的那类问题，所以这里逐字段钉住。
 */
public class PeerLinkTest {

    @Test
    public void roundTripsHotspotCredentials() {
        String qr = PeerLink.encode("192.168.49.1", 9600, "123456",
                "Pixel 7", "DroidTrans-3f2", "8a91 c07b");
        PeerLink link = PeerLink.parse(qr);

        assertEquals("192.168.49.1", link.host);
        assertEquals(9600, link.port);
        assertEquals("123456", link.code);
        assertEquals("Pixel 7", link.name);
        assertEquals("DroidTrans-3f2", link.ssid);
        // 密码里的空格必须原样回来，差一个字符就连不上那个热点
        assertEquals("8a91 c07b", link.password);
        assertTrue(link.hasHotspot());
        assertEquals("http://192.168.49.1:9600", link.baseUrl());
    }

    @Test
    public void noHotspotFieldsWhenNotSharing() {
        PeerLink link = PeerLink.parse(
                PeerLink.encode("10.0.0.8", 9600, "654321", "iPhone", null, null));
        assertFalse(link.hasHotspot());
        assertEquals("", link.ssid);
        assertEquals("iPhone", link.name);
    }

    @Test
    public void acceptsDesktopQrFromOlderVersions() {
        // 电脑端一直发的就是这个形状，不带端口以外的东西
        PeerLink link = PeerLink.parse("http://192.168.1.5:9500/?c=187931");
        assertEquals("192.168.1.5", link.host);
        assertEquals(9500, link.port);
        assertEquals("187931", link.code);
        assertEquals("", link.name);
        assertFalse(link.hasHotspot());
    }

    @Test
    public void bareAddressFallsBackToDesktopPort() {
        PeerLink link = PeerLink.parse("192.168.1.5");
        assertEquals("192.168.1.5", link.host);
        assertEquals(9500, link.port);
    }

    @Test
    public void rejectsGarbage() {
        assertNull(PeerLink.parse(""));
        assertNull(PeerLink.parse(null));
    }
}
