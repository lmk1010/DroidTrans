package com.mk.androidtransfer.usb;

import android.content.Context;
import android.os.Build;
import android.util.Log;

import java.io.IOException;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * USB连接管理器
 * 整合AOA协议和传输协议，提供高层API
 */
public class UsbConnectionManager {
    private static final String TAG = "UsbConnectionManager";
    
    private Context context;
    private UsbAOAManager aoaManager;
    private AtomicBoolean isConnected = new AtomicBoolean(false);
    private ConnectionListener listener;
    private int sequenceNumber = 0;
    
    // 超时设置
    private static final int CONNECT_TIMEOUT = 10000; // 10秒
    private static final int SEND_TIMEOUT = 5000;     // 5秒
    private static final int RECEIVE_TIMEOUT = 3000;  // 3秒（降低以便更快重试）
    
    public interface ConnectionListener {
        void onConnected(boolean isHost);
        void onDisconnected();
        void onError(String error);
        void onDataReceived(UsbTransferProtocol.Packet packet);
    }

    public UsbConnectionManager(Context context) {
        this.context = context;
        this.aoaManager = new UsbAOAManager(context);
    }

    public void setConnectionListener(ConnectionListener listener) {
        this.listener = listener;
    }

    /**
     * 自动检测并连接
     * 返回: true=Host模式, false=Accessory模式, null=未检测到
     */
    public Boolean detectAndConnect() throws IOException {
        Log.d(TAG, "开始自动检测USB连接模式...");
        
        // 先检测Host模式
        if (aoaManager.detectHostMode()) {
            Log.d(TAG, "检测到Host模式，作为发送端连接");
            boolean success = aoaManager.connectAsHost(null);
            
            if (success) {
                isConnected.set(true);
                if (listener != null) {
                    listener.onConnected(true);
                }
                return true;
            } else {
                Log.w(TAG, "Host模式连接失败，可能正在切换到AOA模式");
                // 设备可能正在切换到AOA模式，返回null表示需要重试
                return null;
            }
        }
        
        // 检测Accessory模式
        if (aoaManager.detectAccessoryMode()) {
            Log.d(TAG, "检测到Accessory模式，作为接收端连接");
            boolean success = aoaManager.connectAsAccessory(null);
            
            if (success) {
                isConnected.set(true);
                if (listener != null) {
                    listener.onConnected(false);
                }
                return false;
            } else {
                throw new IOException("Accessory模式连接失败");
            }
        }
        
        Log.w(TAG, "未检测到USB连接");
        return null;
    }

    /**
     * 执行握手
     */
    public boolean performHandshake() throws IOException {
        if (!isConnected.get()) {
            throw new IOException("USB未连接");
        }
        
        String deviceName = Build.MODEL;
        Log.d(TAG, "开始握手，设备名: " + deviceName + ", 模式: " + (aoaManager.isHostMode() ? "Host" : "Accessory"));
        
        // 设置握手超时时间 (60秒)
        long startTime = System.currentTimeMillis();
        final long HANDSHAKE_TIMEOUT = 60000;
        
        if (aoaManager.isHostMode()) {
            // Host端：发送握手，等待确认
            Log.d(TAG, "[Host] 发送握手包...");
            byte[] handshake = UsbTransferProtocol.buildHandshakePacket(deviceName);
            sendRawData(handshake);
            Log.d(TAG, "[Host] 握手包已发送，等待接收端响应...");
            
            // 重试接收，带超时保护
            int retries = 0;
            final int MAX_RETRIES = 20; // 增加到20次
            byte[] response = null;
            
            while (retries < MAX_RETRIES) {
                long elapsed = System.currentTimeMillis() - startTime;
                if (elapsed > HANDSHAKE_TIMEOUT) {
                    throw new IOException("握手超时: 未在 " + (HANDSHAKE_TIMEOUT/1000) + " 秒内收到响应");
                }
                
                try {
                    Log.d(TAG, "[Host] 尝试接收握手确认... (第" + (retries + 1) + "/" + MAX_RETRIES + "次)");
                    response = receiveRawDataWithTimeout(2000); // 2秒超时
                    
                    if (response != null && response.length > 0) {
                        Log.d(TAG, "[Host] 收到数据，长度: " + response.length);
                        break;
                    }
                    
                    Log.w(TAG, "[Host] 未收到数据，重试...");
                    retries++;
                    Thread.sleep(500); // 等待0.5秒后重试
                    
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new IOException("握手被中断");
                } catch (IOException e) {
                    Log.w(TAG, "[Host] 接收失败: " + e.getMessage());
                    retries++;
                    if (retries >= MAX_RETRIES) {
                        throw new IOException("握手失败: 无法接收确认 - " + e.getMessage());
                    }
                    try {
                        Thread.sleep(500);
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        throw new IOException("握手被中断");
                    }
                }
            }
            
            if (response == null || response.length == 0) {
                throw new IOException("握手失败: 未收到确认数据");
            }
            
            UsbTransferProtocol.Packet packet = UsbTransferProtocol.parsePacket(response, response.length);
            
            if (packet.type == UsbTransferProtocol.PACKET_TYPE_HANDSHAKE_ACK) {
                String remoteName = packet.data != null ? new String(packet.data) : "Unknown";
                Log.d(TAG, "[Host] ✓ 握手成功！对方设备: " + remoteName);
                return true;
            } else {
                throw new IOException("握手失败，收到意外的包类型: " + 
                    UsbTransferProtocol.getPacketTypeName(packet.type));
            }
            
        } else {
            // Accessory端：等待握手，发送确认
            Log.d(TAG, "[Accessory] 等待接收握手包...");
            
            // 重试接收，带超时保护
            int retries = 0;
            final int MAX_RETRIES = 20; // 增加到20次
            byte[] request = null;
            
            while (retries < MAX_RETRIES) {
                long elapsed = System.currentTimeMillis() - startTime;
                if (elapsed > HANDSHAKE_TIMEOUT) {
                    throw new IOException("握手超时: 未在 " + (HANDSHAKE_TIMEOUT/1000) + " 秒内收到握手包");
                }
                
                try {
                    Log.d(TAG, "[Accessory] 尝试接收握手包... (第" + (retries + 1) + "/" + MAX_RETRIES + "次)");
                    request = receiveRawDataWithTimeout(2000); // 2秒超时
                    
                    if (request != null && request.length > 0) {
                        Log.d(TAG, "[Accessory] 收到数据，长度: " + request.length);
                        break;
                    }
                    
                    Log.w(TAG, "[Accessory] 未收到数据，重试...");
                    retries++;
                    Thread.sleep(500); // 等待0.5秒后重试
                    
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new IOException("握手被中断");
                } catch (IOException e) {
                    Log.w(TAG, "[Accessory] 接收失败: " + e.getMessage());
                    retries++;
                    if (retries >= MAX_RETRIES) {
                        throw new IOException("握手失败: 无法接收握手包 - " + e.getMessage());
                    }
                    try {
                        Thread.sleep(500);
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        throw new IOException("握手被中断");
                    }
                }
            }
            
            if (request == null || request.length == 0) {
                throw new IOException("握手失败: 未收到握手包");
            }
            
            UsbTransferProtocol.Packet packet = UsbTransferProtocol.parsePacket(request, request.length);
            
            if (packet.type == UsbTransferProtocol.PACKET_TYPE_HANDSHAKE) {
                String remoteName = packet.data != null ? new String(packet.data) : "Unknown";
                Log.d(TAG, "[Accessory] 收到握手，对方设备: " + remoteName);
                
                Log.d(TAG, "[Accessory] 发送握手确认...");
                byte[] ack = UsbTransferProtocol.buildHandshakeAckPacket(deviceName);
                sendRawData(ack);
                Log.d(TAG, "[Accessory] 握手确认已发送");
                
                // 等待一下确保数据发送完成
                try {
                    Thread.sleep(500);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                
                Log.d(TAG, "[Accessory] ✓ 握手成功");
                return true;
            } else {
                throw new IOException("握手失败，收到意外的包类型: " + 
                    UsbTransferProtocol.getPacketTypeName(packet.type));
            }
        }
    }
    
    /**
     * 接收原始数据（带超时）
     */
    private byte[] receiveRawDataWithTimeout(int timeoutMs) throws IOException {
        return receiveRawData();
    }

    /**
     * 发送数据包
     */
    public void sendPacket(byte type, byte[] data) throws IOException {
        if (!isConnected.get()) {
            throw new IOException("USB未连接");
        }
        
        byte[] packet = UsbTransferProtocol.buildPacket(type, sequenceNumber++, data);
        sendRawData(packet);
        
        Log.d(TAG, String.format("发送数据包: type=%s, seq=%d, size=%d", 
            UsbTransferProtocol.getPacketTypeName(type), 
            sequenceNumber - 1, 
            data != null ? data.length : 0));
    }

    /**
     * 接收数据包
     */
    public UsbTransferProtocol.Packet receivePacket() throws IOException {
        if (!isConnected.get()) {
            throw new IOException("USB未连接");
        }
        
        byte[] buffer = receiveRawData();
        UsbTransferProtocol.Packet packet = UsbTransferProtocol.parsePacket(buffer, buffer.length);
        
        Log.d(TAG, String.format("接收数据包: type=%s, seq=%d, size=%d", 
            UsbTransferProtocol.getPacketTypeName(packet.type), 
            packet.sequence, 
            packet.data != null ? packet.data.length : 0));
        
        if (listener != null) {
            listener.onDataReceived(packet);
        }
        
        return packet;
    }

    /**
     * 发送原始数据
     */
    private void sendRawData(byte[] data) throws IOException {
        try {
            int sent = aoaManager.sendData(data, SEND_TIMEOUT);
            if (sent != data.length) {
                throw new IOException("数据发送不完整: " + sent + "/" + data.length);
            }
        } catch (IOException e) {
            Log.e(TAG, "发送数据失败", e);
            if (listener != null) {
                listener.onError("发送失败: " + e.getMessage());
            }
            throw e;
        }
    }

    /**
     * 接收原始数据
     */
    private byte[] receiveRawData() throws IOException {
        try {
            Log.d(TAG, "开始接收数据包...");
            
            // 先读取头部，获取数据包总长度
            byte[] header = new byte[16];
            int received = 0;
            int retries = 0;
            final int MAX_RETRIES = 3;
            
            // 分段接收头部，处理部分读取
            while (received < 16 && retries < MAX_RETRIES) {
                try {
                    byte[] buffer = new byte[16 - received];
                    int len = aoaManager.receiveData(buffer, RECEIVE_TIMEOUT);
                    
                    Log.d(TAG, "接收数据: " + len + " bytes (已接收: " + received + "/16)");
                    
                    if (len > 0) {
                        System.arraycopy(buffer, 0, header, received, len);
                        received += len;
                        retries = 0; // 成功读取，重置重试计数
                    } else if (len == 0) {
                        // 没有数据可读，等待后重试
                        Log.w(TAG, "未收到数据，等待重试... (重试: " + (retries+1) + "/" + MAX_RETRIES + ")");
                        retries++;
                        if (retries < MAX_RETRIES) {
                            Thread.sleep(100);
                        }
                    } else {
                        throw new IOException("接收头部失败: " + len);
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new IOException("接收被中断");
                }
            }
            
            if (received < 16) {
                Log.e(TAG, "接收头部不完整: " + received + "/16 bytes");
                throw new IOException("接收头部不完整: " + received + "/16 bytes");
            }
            
            // 解析数据长度
            int dataLength = ((header[12] & 0xFF) << 24) | 
                           ((header[13] & 0xFF) << 16) | 
                           ((header[14] & 0xFF) << 8) | 
                           (header[15] & 0xFF);
            
            Log.d(TAG, "数据包头部接收完成，数据长度: " + dataLength);
            
            if (dataLength < 0 || dataLength > 50 * 1024 * 1024) {
                throw new IOException("无效的数据长度: " + dataLength);
            }
            
            // 如果有数据部分，继续接收
            if (dataLength > 0) {
                byte[] fullPacket = new byte[16 + dataLength];
                System.arraycopy(header, 0, fullPacket, 0, 16);
                
                int totalReceived = 0;
                retries = 0;
                int chunkSize = Math.min(16384, aoaManager.getMaxPacketSize());
                
                while (totalReceived < dataLength) {
                    try {
                        int remaining = dataLength - totalReceived;
                        byte[] buffer = new byte[Math.min(remaining, chunkSize)];
                        int len = aoaManager.receiveData(buffer, RECEIVE_TIMEOUT);
                        
                        if (len > 0) {
                            System.arraycopy(buffer, 0, fullPacket, 16 + totalReceived, len);
                            totalReceived += len;
                            retries = 0; // 成功读取，重置重试计数
                            
                            // 打印进度
                            if (totalReceived % (chunkSize * 10) == 0 || totalReceived == dataLength) {
                                Log.d(TAG, String.format("接收进度: %d/%d (%.1f%%)", 
                                    totalReceived, dataLength, totalReceived * 100.0 / dataLength));
                            }
                        } else if (len == 0) {
                            // 没有数据可读，等待后重试
                            retries++;
                            if (retries >= MAX_RETRIES) {
                                throw new IOException("接收数据超时: " + totalReceived + "/" + dataLength);
                            }
                            Thread.sleep(100);
                        } else {
                            throw new IOException("接收数据失败: " + len);
                        }
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        throw new IOException("接收被中断");
                    }
                }
                
                Log.d(TAG, "数据包接收完成: " + (16 + dataLength) + " bytes");
                return fullPacket;
            } else {
                return header;
            }
            
        } catch (IOException e) {
            Log.e(TAG, "接收数据失败", e);
            if (listener != null) {
                listener.onError("接收失败: " + e.getMessage());
            }
            throw e;
        }
    }

    /**
     * 发送文件信息
     */
    public void sendFileInfo(String fileName, long fileSize, int fileIndex, int totalFiles) throws IOException {
        byte[] packet = UsbTransferProtocol.buildFileInfoPacket(
            sequenceNumber++, fileName, fileSize, fileIndex, totalFiles);
        sendRawData(packet);
        
        Log.d(TAG, String.format("发送文件信息: %s, 大小=%d, 索引=%d/%d", 
            fileName, fileSize, fileIndex, totalFiles));
    }

    /**
     * 发送文件数据块
     */
    public void sendFileData(byte[] data, int offset, int length) throws IOException {
        byte[] packet = UsbTransferProtocol.buildFileDataPacket(
            sequenceNumber++, data, offset, length);
        sendRawData(packet);
    }

    /**
     * 发送文件结束
     */
    public void sendFileEnd(long totalSize, long transferredSize) throws IOException {
        byte[] packet = UsbTransferProtocol.buildFileEndPacket(
            sequenceNumber++, totalSize, transferredSize);
        sendRawData(packet);
        
        Log.d(TAG, String.format("发送文件结束: total=%d, transferred=%d", 
            totalSize, transferredSize));
    }

    /**
     * 发送确认
     */
    public void sendAck(int sequence) throws IOException {
        byte[] packet = UsbTransferProtocol.buildAckPacket(sequence);
        sendRawData(packet);
    }

    /**
     * 发送错误
     */
    public void sendError(String errorMessage) throws IOException {
        byte[] packet = UsbTransferProtocol.buildErrorPacket(sequenceNumber++, errorMessage);
        sendRawData(packet);
        
        Log.e(TAG, "发送错误: " + errorMessage);
    }

    /**
     * 发送传输完成
     */
    public void sendTransferComplete(int totalFiles, int successFiles, int failedFiles) throws IOException {
        byte[] packet = UsbTransferProtocol.buildTransferCompletePacket(
            totalFiles, successFiles, failedFiles);
        sendRawData(packet);
        
        Log.d(TAG, String.format("发送传输完成: total=%d, success=%d, failed=%d", 
            totalFiles, successFiles, failedFiles));
    }

    /**
     * 等待确认
     */
    public boolean waitForAck(int expectedSequence, int timeoutMs) throws IOException {
        long startTime = System.currentTimeMillis();
        
        while (System.currentTimeMillis() - startTime < timeoutMs) {
            try {
                UsbTransferProtocol.Packet packet = receivePacket();
                
                if (packet.type == UsbTransferProtocol.PACKET_TYPE_ACK && 
                    packet.sequence == expectedSequence) {
                    return true;
                } else if (packet.type == UsbTransferProtocol.PACKET_TYPE_ERROR) {
                    String error = packet.data != null ? new String(packet.data) : "Unknown error";
                    throw new IOException("收到错误: " + error);
                }
                
            } catch (IOException e) {
                if (System.currentTimeMillis() - startTime >= timeoutMs) {
                    throw new IOException("等待确认超时");
                }
                // 继续等待
                try {
                    Thread.sleep(100);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    throw new IOException("等待被中断");
                }
            }
        }
        
        throw new IOException("等待确认超时");
    }

    /**
     * 断开连接
     */
    public void disconnect() {
        isConnected.set(false);
        aoaManager.disconnect();
        
        if (listener != null) {
            listener.onDisconnected();
        }
        
        Log.d(TAG, "连接已断开");
    }

    /**
     * 是否已连接
     */
    public boolean isConnected() {
        return isConnected.get();
    }

    /**
     * 是否为Host模式
     */
    public boolean isHostMode() {
        return aoaManager.isHostMode();
    }

    /**
     * 获取最大数据块大小
     */
    public int getMaxDataSize() {
        return Math.min(UsbTransferProtocol.MAX_DATA_SIZE, aoaManager.getMaxPacketSize());
    }
}

