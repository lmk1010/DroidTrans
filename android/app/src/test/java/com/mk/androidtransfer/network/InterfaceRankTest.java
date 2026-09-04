package com.mk.androidtransfer.network;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * 该报哪个网卡的地址。
 *
 * <p>连着 Wi-Fi 又开着热点是很常见的（用手机流量给对方共享网络）。
 * 这时两个网卡都是 up 的，按枚举顺序随便挑一个，就可能把对方
 * 根本到不了的 Wi-Fi 地址报出去 —— 手输地址那条兜底路直接断掉，
 * 而且不会有任何报错，用户只会觉得「填了地址还是连不上」。
 */
public class InterfaceRankTest {

    @Test
    public void hotspotOutranksWifi() {
        assertTrue("同时开着热点和 Wi-Fi 时，报出去的必须是热点那个地址",
                PeerServer.interfaceRank("ap0") < PeerServer.interfaceRank("wlan0"));
        assertTrue(PeerServer.interfaceRank("softap0") < PeerServer.interfaceRank("wlan0"));
        assertTrue(PeerServer.interfaceRank("swlan0") < PeerServer.interfaceRank("wlan0"));
    }

    /** 蜂窝地址对面连不过来，报出去等于给用户一个死地址。 */
    @Test
    public void cellularIsNeverReported() {
        assertEquals(-1, PeerServer.interfaceRank("rmnet_data0"));
        assertEquals(-1, PeerServer.interfaceRank("pdp_ip0"));
        assertEquals(-1, PeerServer.interfaceRank("dummy0"));
        assertEquals(-1, PeerServer.interfaceRank(null));
    }
}
