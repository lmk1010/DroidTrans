package com.mk.androidtransfer.usb;

import android.util.Log;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;

/**
 * USB RNDIS文件传输管理器
 * 基于TCP/IP的高速文件传输
 */
public class UsbRndisTransferManager {
    private static final String TAG = "UsbRndisTransfer";
    
    private Socket socket;
    private DataInputStream inputStream;
    private DataOutputStream outputStream;
    private ServerSocket serverSocket;
    
    private boolean isConnected = false;
    
    /**
     * 作为客户端连接
     */
    public boolean connectAsClient(String host, int port) throws IOException {
        try {
            Log.d(TAG, "连接到服务器: " + host + ":" + port);
            
            socket = new Socket();
            socket.connect(new InetSocketAddress(host, port), 10000);
            socket.setTcpNoDelay(true);
            socket.setSendBufferSize(256 * 1024);
            socket.setReceiveBufferSize(256 * 1024);
            
            inputStream = new DataInputStream(socket.getInputStream());
            outputStream = new DataOutputStream(socket.getOutputStream());
            
            isConnected = true;
            Log.d(TAG, "✓ 连接成功");
            return true;
            
        } catch (IOException e) {
            Log.e(TAG, "连接失败", e);
            disconnect();
            throw e;
        }
    }
    
    /**
     * 作为服务器等待连接
     */
    public boolean startServer(int port) throws IOException {
        try {
            Log.d(TAG, "启动服务器监听: " + port);
            
            serverSocket = new ServerSocket(port);
            serverSocket.setSoTimeout(30000);
            
            Log.d(TAG, "等待客户端连接...");
            socket = serverSocket.accept();
            socket.setTcpNoDelay(true);
            socket.setSendBufferSize(256 * 1024);
            socket.setReceiveBufferSize(256 * 1024);
            
            inputStream = new DataInputStream(socket.getInputStream());
            outputStream = new DataOutputStream(socket.getOutputStream());
            
            isConnected = true;
            Log.d(TAG, "✓ 客户端已连接: " + socket.getInetAddress().getHostAddress());
            return true;
            
        } catch (IOException e) {
            Log.e(TAG, "服务器启动失败", e);
            disconnect();
            throw e;
        }
    }
    
    /**
     * 执行握手
     */
    public boolean performHandshake(String deviceName, boolean isInitiator) throws IOException {
        try {
            if (isInitiator) {
                // 发送握手
                sendHandshake(deviceName);
                // 接收响应
                String remoteName = receiveHandshake();
                Log.d(TAG, "✓ 握手成功，对端设备: " + remoteName);
                return true;
            } else {
                // 接收握手
                String remoteName = receiveHandshake();
                // 发送响应
                sendHandshake(deviceName);
                Log.d(TAG, "✓ 握手成功，对端设备: " + remoteName);
                return true;
            }
        } catch (IOException e) {
            Log.e(TAG, "握手失败", e);
            throw e;
        }
    }
    
    /**
     * 发送握手包
     */
    private void sendHandshake(String deviceName) throws IOException {
        outputStream.writeUTF("HANDSHAKE");
        outputStream.writeUTF(deviceName);
        outputStream.flush();
        Log.d(TAG, "发送握手: " + deviceName);
    }
    
    /**
     * 接收握手包
     */
    private String receiveHandshake() throws IOException {
        String type = inputStream.readUTF();
        if (!"HANDSHAKE".equals(type)) {
            throw new IOException("无效的握手包: " + type);
        }
        String deviceName = inputStream.readUTF();
        Log.d(TAG, "接收握手: " + deviceName);
        return deviceName;
    }
    
    /**
     * 发送文件信息
     */
    public void sendFileInfo(String fileName, long fileSize, int fileIndex, int totalFiles) throws IOException {
        outputStream.writeUTF("FILE_INFO");
        outputStream.writeUTF(fileName);
        outputStream.writeLong(fileSize);
        outputStream.writeInt(fileIndex);
        outputStream.writeInt(totalFiles);
        outputStream.flush();
        
        Log.d(TAG, String.format("发送文件信息: %s, 大小: %d, %d/%d", 
            fileName, fileSize, fileIndex + 1, totalFiles));
    }
    
    /**
     * 接收文件信息
     */
    public FileInfo receiveFileInfo() throws IOException {
        String type = inputStream.readUTF();
        if (!"FILE_INFO".equals(type)) {
            throw new IOException("期望FILE_INFO，收到: " + type);
        }
        
        FileInfo info = new FileInfo();
        info.fileName = inputStream.readUTF();
        info.fileSize = inputStream.readLong();
        info.fileIndex = inputStream.readInt();
        info.totalFiles = inputStream.readInt();
        
        Log.d(TAG, String.format("接收文件信息: %s, 大小: %d, %d/%d",
            info.fileName, info.fileSize, info.fileIndex + 1, info.totalFiles));
        
        return info;
    }
    
    /**
     * 发送文件数据块
     */
    public void sendFileChunk(byte[] data, int offset, int length) throws IOException {
        outputStream.writeUTF("FILE_DATA");
        outputStream.writeInt(length);
        outputStream.write(data, offset, length);
        outputStream.flush();
    }
    
    /**
     * 接收文件数据块
     */
    public int receiveFileChunk(byte[] buffer) throws IOException {
        String type = inputStream.readUTF();
        if (!"FILE_DATA".equals(type)) {
            if ("FILE_END".equals(type)) {
                return -1; // 文件结束
            }
            throw new IOException("期望FILE_DATA，收到: " + type);
        }
        
        int length = inputStream.readInt();
        inputStream.readFully(buffer, 0, length);
        return length;
    }
    
    /**
     * 发送文件结束
     */
    public void sendFileEnd() throws IOException {
        outputStream.writeUTF("FILE_END");
        outputStream.flush();
        Log.d(TAG, "发送文件结束");
    }
    
    /**
     * 发送传输完成
     */
    public void sendTransferComplete(int totalFiles, int successFiles, int failedFiles) throws IOException {
        outputStream.writeUTF("TRANSFER_COMPLETE");
        outputStream.writeInt(totalFiles);
        outputStream.writeInt(successFiles);
        outputStream.writeInt(failedFiles);
        outputStream.flush();
        
        Log.d(TAG, String.format("发送传输完成: total=%d, success=%d, failed=%d",
            totalFiles, successFiles, failedFiles));
    }
    
    /**
     * 接收传输完成
     */
    public TransferResult receiveTransferComplete() throws IOException {
        String type = inputStream.readUTF();
        if (!"TRANSFER_COMPLETE".equals(type)) {
            throw new IOException("期望TRANSFER_COMPLETE，收到: " + type);
        }
        
        TransferResult result = new TransferResult();
        result.totalFiles = inputStream.readInt();
        result.successFiles = inputStream.readInt();
        result.failedFiles = inputStream.readInt();
        
        Log.d(TAG, String.format("接收传输完成: total=%d, success=%d, failed=%d",
            result.totalFiles, result.successFiles, result.failedFiles));
        
        return result;
    }
    
    /**
     * 断开连接
     */
    public void disconnect() {
        isConnected = false;
        
        try {
            if (inputStream != null) inputStream.close();
        } catch (IOException e) {
            Log.w(TAG, "关闭输入流失败", e);
        }
        
        try {
            if (outputStream != null) outputStream.close();
        } catch (IOException e) {
            Log.w(TAG, "关闭输出流失败", e);
        }
        
        try {
            if (socket != null) socket.close();
        } catch (IOException e) {
            Log.w(TAG, "关闭Socket失败", e);
        }
        
        try {
            if (serverSocket != null) serverSocket.close();
        } catch (IOException e) {
            Log.w(TAG, "关闭ServerSocket失败", e);
        }
        
        Log.d(TAG, "连接已断开");
    }
    
    /**
     * 是否已连接
     */
    public boolean isConnected() {
        return isConnected && socket != null && socket.isConnected() && !socket.isClosed();
    }
    
    /**
     * 文件信息
     */
    public static class FileInfo {
        public String fileName;
        public long fileSize;
        public int fileIndex;
        public int totalFiles;
    }
    
    /**
     * 传输结果
     */
    public static class TransferResult {
        public int totalFiles;
        public int successFiles;
        public int failedFiles;
    }
}

