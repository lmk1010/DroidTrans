package com.mk.androidtransfer.network;

import java.io.UnsupportedEncodingException;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.util.HashMap;
import java.util.Map;

/**
 * 接收端二维码里的那串东西。
 *
 * <p>沿用电脑端一直在用的写法，扫码这条路才不用分两套解析：
 * <pre>
 *   http://192.168.49.1:9600/?c=123456&amp;n=Pixel%207&amp;s=DroidTrans-3f2&amp;k=8a91c07b
 * </pre>
 *
 * <ul>
 *   <li>host / port —— 接收端实际绑上的地址，端口不写死，9600 被占时会是别的</li>
 *   <li><b>c</b> 六位配对码：组播被拦、敲门弹框没看到时的兜底</li>
 *   <li><b>n</b> 设备名，扫完先给用户看一眼「你要连的是这台」</li>
 *   <li><b>s/k</b> 直连热点的 SSID 和密码 —— <b>有它才谈得上「扫一下就连上」</b>。
 *       没有路由器的场合，扫码的人本来要先退出 App、去设置里找那个热点、
 *       手输一串密码，回来还得重新扫一次。</li>
 * </ul>
 *
 * <p>没有 s/k 的码照常能用，就是少了自动入网这一步；老版本 App 扫这个码
 * 也只会看到它认识的 host/port/c，多出来的字段它不认识，直接忽略。
 */
public final class PeerLink {

    public final String host;
    public final int port;
    public final String code;
    public final String name;
    /** 直连热点的 SSID；空表示这个码不带入网信息。 */
    public final String ssid;
    public final String password;

    private PeerLink(String host, int port, String code, String name,
                     String ssid, String password) {
        this.host = host;
        this.port = port;
        this.code = code == null ? "" : code;
        this.name = name == null ? "" : name;
        this.ssid = ssid == null ? "" : ssid;
        this.password = password == null ? "" : password;
    }

    public boolean hasHotspot() {
        return !ssid.isEmpty();
    }

    public String baseUrl() {
        return "http://" + host + ":" + port;
    }

    /** 接收端生成自己的码。ssid/password 传 null 表示这次不开直连热点。 */
    public static String encode(String host, int port, String code, String name,
                                String ssid, String password) {
        StringBuilder b = new StringBuilder("http://")
                .append(host).append(':').append(port)
                .append("/?c=").append(enc(code));
        if (name != null && !name.isEmpty()) {
            b.append("&n=").append(enc(name));
        }
        if (ssid != null && !ssid.isEmpty()) {
            b.append("&s=").append(enc(ssid));
            b.append("&k=").append(enc(password == null ? "" : password));
        }
        return b.toString();
    }

    /** 扫到的内容解析成一条链接；不是卓传的码返回 null。 */
    public static PeerLink parse(String payload) {
        if (payload == null) {
            return null;
        }
        String s = payload.trim();
        if (s.isEmpty()) {
            return null;
        }
        int scheme = s.indexOf("://");
        if (scheme >= 0) {
            s = s.substring(scheme + 3);
        }
        String query = "";
        int q = s.indexOf('?');
        if (q >= 0) {
            query = s.substring(q + 1);
            s = s.substring(0, q);
        }
        int slash = s.indexOf('/');
        if (slash >= 0) {
            s = s.substring(0, slash);
        }
        if (s.isEmpty()) {
            return null;
        }

        String host = s;
        // 端口不写就按电脑端的 9500 算 —— 电脑的码历来就是那个样子。
        // 用最后一个冒号切，IPv6 字面量（含多个冒号）才不会被切坏。
        int port = 9500;
        int colon = s.lastIndexOf(':');
        if (colon > 0 && s.indexOf(']') < colon) {
            try {
                port = Integer.parseInt(s.substring(colon + 1));
                host = s.substring(0, colon);
            } catch (NumberFormatException ignored) {
                // 不是端口，那整段就是主机名
            }
        }
        Map<String, String> params = split(query);
        return new PeerLink(host, port,
                params.get("c"), params.get("n"),
                params.get("s"), params.get("k"));
    }

    private static Map<String, String> split(String query) {
        Map<String, String> out = new HashMap<>();
        for (String pair : query.split("&")) {
            if (pair.isEmpty()) {
                continue;
            }
            int eq = pair.indexOf('=');
            if (eq <= 0) {
                continue;
            }
            out.put(pair.substring(0, eq), dec(pair.substring(eq + 1)));
        }
        return out;
    }

    private static String enc(String s) {
        try {
            return URLEncoder.encode(s == null ? "" : s, "UTF-8");
        } catch (UnsupportedEncodingException e) {
            return s == null ? "" : s;
        }
    }

    private static String dec(String s) {
        try {
            return URLDecoder.decode(s, "UTF-8");
        } catch (Exception e) {
            // 码里有不合法的转义，原样返回总比整条码作废强
            return s;
        }
    }
}
