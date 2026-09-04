package com.mk.androidtransfer.network;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

import org.junit.Test;

/**
 * 请求头解析。
 *
 * <p>盯的是断点续传依赖的那两样东西：查询串和 Content-Length。
 * 查询串原来在解析时就被直接扔掉了，/api/fast/offset 拿不到 name 和 size，
 * 就只会一律回 0 —— 表面上「续传功能有」，实际每次都从头传，
 * 而且不会报任何错。
 */
public class PeerHeadTest {

    private static PeerServer.Head parse(String req) throws IOException {
        return PeerServer.Head.read(
                new ByteArrayInputStream(req.getBytes(StandardCharsets.UTF_8)));
    }

    @Test
    public void keepsQueryParamsAndStillRoutesOnPathAlone() throws IOException {
        PeerServer.Head h = parse("GET /api/fast/offset?name=a.mp4&size=1234 HTTP/1.1\r\n"
                + "Host: x\r\n\r\n");
        assertEquals("/api/fast/offset", h.path);
        assertEquals("a.mp4", h.query("name"));
        assertEquals("1234", h.query("size"));
    }

    /** 文件名里有空格、中文、括号是常态，编码过的必须还原。 */
    @Test
    public void decodesPercentEscapes() throws IOException {
        PeerServer.Head h = parse("GET /api/fast/offset?name=%E7%85%A7%E7%89%87%20(2).jpg"
                + "&size=9 HTTP/1.1\r\n\r\n");
        assertEquals("照片 (2).jpg", h.query("name"));
    }

    @Test
    public void missingQueryIsNullNotCrash() throws IOException {
        PeerServer.Head h = parse("GET /api/health HTTP/1.1\r\n\r\n");
        assertEquals("/api/health", h.path);
        assertNull(h.query("name"));
    }

    /**
     * 续传时 Content-Length 只是剩下的那一段，X-File-Size 才是整个文件。
     * 收文件那边按前者收边，按后者判断收全没有 —— 两个数必须都读得出来。
     */
    @Test
    public void separatesChunkLengthFromWholeFileSize() throws IOException {
        PeerServer.Head h = parse("PUT /api/fast/put HTTP/1.1\r\n"
                + "Content-Length: 40\r\n"
                + "X-File-Size: 100\r\n"
                + "X-Start-Offset: 60\r\n\r\n");
        assertEquals(40, h.contentLength());
        assertEquals("100", h.header("X-File-Size"));
        assertEquals("60", h.header("x-start-offset"));
    }
}
