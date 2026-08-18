package com.mk.androidtransfer.usb;

import android.util.Log;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.zip.CRC32;

/**
 * USB文件传输协议
 * 定义数据包格式和通信协议
 */
public class UsbTransferProtocol {
    private static final String TAG = "UsbTransferProtocol";
    
    // 协议版本
    public static final int PROTOCOL_VERSION = 1;
    
    // 数据包类型
    public static final byte PACKET_TYPE_HANDSHAKE = 0x01;      // 握手
    public static final byte PACKET_TYPE_HANDSHAKE_ACK = 0x02;  // 握手确认
    public static final byte PACKET_TYPE_FILE_INFO = 0x03;      // 文件信息
    public static final byte PACKET_TYPE_FILE_DATA = 0x04;      // 文件数据
    public static final byte PACKET_TYPE_FILE_END = 0x05;       // 文件结束
    public static final byte PACKET_TYPE_ACK = 0x06;            // 确认
    public static final byte PACKET_TYPE_ERROR = 0x07;          // 错误
    public static final byte PACKET_TYPE_TRANSFER_COMPLETE = 0x08; // 传输完成
    
    // 数据包头部大小
    private static final int HEADER_SIZE = 16;
    
    // 魔数，用于识别协议
    private static final int MAGIC_NUMBER = 0x4D4B5446; // "MKTF"
    
    // 最大数据块大小（根据USB包大小调整）
    public static final int MAX_DATA_SIZE = 16384; // 16KB

    /**
     * 数据包基类
     */
    public static class Packet {
        public byte type;
        public int sequence;
        public byte[] data;
        
        public Packet(byte type, int sequence, byte[] data) {
            this.type = type;
            this.sequence = sequence;
            this.data = data;
        }
    }

    /**
     * 构建数据包
     * 格式: [魔数4字节][版本1字节][类型1字节][序列号4字节][数据长度4字节][CRC32 2字节][数据N字节]
     */
    public static byte[] buildPacket(byte type, int sequence, byte[] data) {
        int dataLength = (data != null) ? data.length : 0;
        int totalLength = HEADER_SIZE + dataLength;
        
        ByteBuffer buffer = ByteBuffer.allocate(totalLength);
        
        // 魔数
        buffer.putInt(MAGIC_NUMBER);
        
        // 版本
        buffer.put((byte) PROTOCOL_VERSION);
        
        // 类型
        buffer.put(type);
        
        // 序列号
        buffer.putInt(sequence);
        
        // 数据长度
        buffer.putInt(dataLength);
        
        // 预留CRC位置
        int crcPosition = buffer.position();
        buffer.putShort((short) 0);
        
        // 数据
        if (data != null && dataLength > 0) {
            buffer.put(data);
        }
        
        // 计算CRC32（仅对数据部分）
        if (data != null && dataLength > 0) {
            CRC32 crc = new CRC32();
            crc.update(data);
            short crcValue = (short) (crc.getValue() & 0xFFFF);
            buffer.putShort(crcPosition, crcValue);
        }
        
        return buffer.array();
    }

    /**
     * 解析数据包
     */
    public static Packet parsePacket(byte[] buffer, int length) throws IOException {
        if (length < HEADER_SIZE) {
            throw new IOException("数据包太小: " + length);
        }
        
        ByteBuffer byteBuffer = ByteBuffer.wrap(buffer, 0, length);
        
        // 检查魔数
        int magic = byteBuffer.getInt();
        if (magic != MAGIC_NUMBER) {
            throw new IOException("无效的魔数: 0x" + Integer.toHexString(magic));
        }
        
        // 版本
        byte version = byteBuffer.get();
        if (version != PROTOCOL_VERSION) {
            Log.w(TAG, "协议版本不匹配: " + version + " vs " + PROTOCOL_VERSION);
        }
        
        // 类型
        byte type = byteBuffer.get();
        
        // 序列号
        int sequence = byteBuffer.getInt();
        
        // 数据长度
        int dataLength = byteBuffer.getInt();
        
        // CRC
        short expectedCrc = byteBuffer.getShort();
        
        // 数据
        byte[] data = null;
        if (dataLength > 0) {
            if (HEADER_SIZE + dataLength > length) {
                throw new IOException("数据长度不匹配: expected=" + dataLength + 
                    ", available=" + (length - HEADER_SIZE));
            }
            
            data = new byte[dataLength];
            byteBuffer.get(data);
            
            // 验证CRC
            if (expectedCrc != 0) {
                CRC32 crc = new CRC32();
                crc.update(data);
                short actualCrc = (short) (crc.getValue() & 0xFFFF);
                if (actualCrc != expectedCrc) {
                    throw new IOException("CRC校验失败: expected=0x" + 
                        Integer.toHexString(expectedCrc & 0xFFFF) + 
                        ", actual=0x" + Integer.toHexString(actualCrc & 0xFFFF));
                }
            }
        }
        
        return new Packet(type, sequence, data);
    }

    /**
     * 构建握手包
     */
    public static byte[] buildHandshakePacket(String deviceName) {
        byte[] data = deviceName.getBytes(StandardCharsets.UTF_8);
        return buildPacket(PACKET_TYPE_HANDSHAKE, 0, data);
    }

    /**
     * 构建握手确认包
     */
    public static byte[] buildHandshakeAckPacket(String deviceName) {
        byte[] data = deviceName.getBytes(StandardCharsets.UTF_8);
        return buildPacket(PACKET_TYPE_HANDSHAKE_ACK, 0, data);
    }

    /**
     * 构建文件信息包
     * 格式: [文件名长度2字节][文件名N字节][文件大小8字节][文件索引4字节][总文件数4字节]
     */
    public static byte[] buildFileInfoPacket(int sequence, String fileName, long fileSize, 
                                            int fileIndex, int totalFiles) {
        try {
            byte[] fileNameBytes = fileName.getBytes(StandardCharsets.UTF_8);
            int fileNameLength = fileNameBytes.length;
            
            ByteBuffer buffer = ByteBuffer.allocate(2 + fileNameLength + 8 + 4 + 4);
            
            // 文件名长度
            buffer.putShort((short) fileNameLength);
            
            // 文件名
            buffer.put(fileNameBytes);
            
            // 文件大小
            buffer.putLong(fileSize);
            
            // 文件索引
            buffer.putInt(fileIndex);
            
            // 总文件数
            buffer.putInt(totalFiles);
            
            return buildPacket(PACKET_TYPE_FILE_INFO, sequence, buffer.array());
            
        } catch (Exception e) {
            Log.e(TAG, "构建文件信息包失败", e);
            return null;
        }
    }

    /**
     * 解析文件信息包
     */
    public static class FileInfo {
        public String fileName;
        public long fileSize;
        public int fileIndex;
        public int totalFiles;
        
        public FileInfo(String fileName, long fileSize, int fileIndex, int totalFiles) {
            this.fileName = fileName;
            this.fileSize = fileSize;
            this.fileIndex = fileIndex;
            this.totalFiles = totalFiles;
        }
    }

    public static FileInfo parseFileInfoPacket(Packet packet) throws IOException {
        if (packet.type != PACKET_TYPE_FILE_INFO) {
            throw new IOException("不是文件信息包");
        }
        
        if (packet.data == null || packet.data.length < 18) {
            throw new IOException("文件信息包数据不完整");
        }
        
        ByteBuffer buffer = ByteBuffer.wrap(packet.data);
        
        // 文件名长度
        short fileNameLength = buffer.getShort();
        
        if (fileNameLength <= 0 || fileNameLength > 1024) {
            throw new IOException("无效的文件名长度: " + fileNameLength);
        }
        
        // 文件名
        byte[] fileNameBytes = new byte[fileNameLength];
        buffer.get(fileNameBytes);
        String fileName = new String(fileNameBytes, StandardCharsets.UTF_8);
        
        // 文件大小
        long fileSize = buffer.getLong();
        
        // 文件索引
        int fileIndex = buffer.getInt();
        
        // 总文件数
        int totalFiles = buffer.getInt();
        
        return new FileInfo(fileName, fileSize, fileIndex, totalFiles);
    }

    /**
     * 构建文件数据包
     */
    public static byte[] buildFileDataPacket(int sequence, byte[] data, int offset, int length) {
        byte[] chunk = Arrays.copyOfRange(data, offset, offset + length);
        return buildPacket(PACKET_TYPE_FILE_DATA, sequence, chunk);
    }

    /**
     * 构建文件结束包
     */
    public static byte[] buildFileEndPacket(int sequence, long totalSize, long transferredSize) {
        ByteBuffer buffer = ByteBuffer.allocate(16);
        buffer.putLong(totalSize);
        buffer.putLong(transferredSize);
        return buildPacket(PACKET_TYPE_FILE_END, sequence, buffer.array());
    }

    /**
     * 构建确认包
     */
    public static byte[] buildAckPacket(int sequence) {
        return buildPacket(PACKET_TYPE_ACK, sequence, null);
    }

    /**
     * 构建错误包
     */
    public static byte[] buildErrorPacket(int sequence, String errorMessage) {
        byte[] data = errorMessage.getBytes(StandardCharsets.UTF_8);
        return buildPacket(PACKET_TYPE_ERROR, sequence, data);
    }

    /**
     * 构建传输完成包
     */
    public static byte[] buildTransferCompletePacket(int totalFiles, int successFiles, int failedFiles) {
        ByteBuffer buffer = ByteBuffer.allocate(12);
        buffer.putInt(totalFiles);
        buffer.putInt(successFiles);
        buffer.putInt(failedFiles);
        return buildPacket(PACKET_TYPE_TRANSFER_COMPLETE, 0, buffer.array());
    }

    /**
     * 解析传输完成包
     */
    public static class TransferResult {
        public int totalFiles;
        public int successFiles;
        public int failedFiles;
        
        public TransferResult(int totalFiles, int successFiles, int failedFiles) {
            this.totalFiles = totalFiles;
            this.successFiles = successFiles;
            this.failedFiles = failedFiles;
        }
    }

    public static TransferResult parseTransferCompletePacket(Packet packet) throws IOException {
        if (packet.type != PACKET_TYPE_TRANSFER_COMPLETE) {
            throw new IOException("不是传输完成包");
        }
        
        if (packet.data == null || packet.data.length < 12) {
            throw new IOException("传输完成包数据不完整");
        }
        
        ByteBuffer buffer = ByteBuffer.wrap(packet.data);
        int totalFiles = buffer.getInt();
        int successFiles = buffer.getInt();
        int failedFiles = buffer.getInt();
        
        return new TransferResult(totalFiles, successFiles, failedFiles);
    }

    /**
     * 获取包类型的字符串描述
     */
    public static String getPacketTypeName(byte type) {
        switch (type) {
            case PACKET_TYPE_HANDSHAKE: return "HANDSHAKE";
            case PACKET_TYPE_HANDSHAKE_ACK: return "HANDSHAKE_ACK";
            case PACKET_TYPE_FILE_INFO: return "FILE_INFO";
            case PACKET_TYPE_FILE_DATA: return "FILE_DATA";
            case PACKET_TYPE_FILE_END: return "FILE_END";
            case PACKET_TYPE_ACK: return "ACK";
            case PACKET_TYPE_ERROR: return "ERROR";
            case PACKET_TYPE_TRANSFER_COMPLETE: return "TRANSFER_COMPLETE";
            default: return "UNKNOWN(0x" + Integer.toHexString(type & 0xFF) + ")";
        }
    }
}

