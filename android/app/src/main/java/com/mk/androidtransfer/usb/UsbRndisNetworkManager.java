package com.mk.androidtransfer.usb;

import android.content.Context;
import android.net.ConnectivityManager;
import android.util.Log;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.NetworkInterface;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.Collections;
import java.util.Enumeration;

/**
 * USB RNDIS网络管理器
 * 负责建立USB虚拟网络连接
 */
public class UsbRndisNetworkManager {
    private static final String TAG = "UsbRndisNetwork";
    
    // RNDIS网络配置
    private static final String[] RNDIS_IP_RANGES = {
        "192.168.42.",  // 标准RNDIS范围
        "192.168.43.",
        "192.168.44.",
        "10.0.0.",      // 备用范围
    };
    
    private static final int TRANSFER_PORT = 8877;
    private static final int CONNECTION_TIMEOUT = 10000;
    
    private Context context;
    private String localIp;
    private String remoteIp;
    private boolean isHost;
    
    public UsbRndisNetworkManager(Context context) {
        this.context = context;
    }
    
    /**
     * 检查USB网络是否已启用
     * 注意：普通APP无权自动启用，需要用户手动在系统设置中开启
     */
    public boolean checkUsbNetworkEnabled() {
        try {
            Log.d(TAG, "检查USB网络是否已启用...");
            
            // 检查是否有USB网络接口
            Enumeration<NetworkInterface> interfaces = NetworkInterface.getNetworkInterfaces();
            if (interfaces == null) {
                return false;
            }
            
            for (NetworkInterface intf : Collections.list(interfaces)) {
                String name = intf.getName().toLowerCase();
                if (name.contains("usb") || name.contains("rndis") || 
                    name.contains("ncm") || name.contains("ecm")) {
                    
                    // 检查是否有IP地址
                    Enumeration<InetAddress> addresses = intf.getInetAddresses();
                    for (InetAddress addr : Collections.list(addresses)) {
                        if (addr instanceof Inet4Address && !addr.isLoopbackAddress()) {
                            Log.d(TAG, "✓ USB网络已启用: " + name + " IP: " + addr.getHostAddress());
                            return true;
                        }
                    }
                }
            }
            
            Log.d(TAG, "✗ USB网络未启用");
            return false;
            
        } catch (Exception e) {
            Log.e(TAG, "检查USB网络失败", e);
            return false;
        }
    }
    
    /**
     * 获取USB网络共享引导信息
     */
    public String getUsbTetheringGuide() {
        String manufacturer = android.os.Build.MANUFACTURER.toLowerCase();
        
        if (manufacturer.contains("xiaomi") || manufacturer.contains("redmi")) {
            return "【小米/Redmi手机】\n" +
                   "设置 → 连接与共享 → USB网络共享 → 开启\n" +
                   "或\n" +
                   "设置 → 更多连接方式 → USB网络共享";
        } else if (manufacturer.contains("vivo") || manufacturer.contains("iqoo")) {
            return "【vivo/iQOO手机】\n" +
                   "设置 → 其他网络与连接 → 个人热点 → USB网络共享\n" +
                   "或\n" +
                   "设置 → 更多设置 → 移动网络共享 → USB共享网络";
        } else if (manufacturer.contains("oppo") || manufacturer.contains("oneplus") || manufacturer.contains("realme")) {
            return "【OPPO/一加/真我手机】\n" +
                   "设置 → 连接与共享 → USB网络共享\n" +
                   "或\n" +
                   "设置 → 其他无线连接 → USB网络共享";
        } else if (manufacturer.contains("huawei") || manufacturer.contains("honor")) {
            return "【华为/荣耀手机】\n" +
                   "设置 → 移动网络 → 个人热点 → 更多共享设置 → USB共享网络\n" +
                   "或\n" +
                   "设置 → 无线和网络 → 移动网络共享 → USB共享网络";
        } else if (manufacturer.contains("samsung")) {
            return "【三星手机】\n" +
                   "设置 → 连接 → 移动热点和网络共享 → USB网络共享";
        } else {
            return "【通用步骤】\n" +
                   "设置 → 搜索 \"USB网络共享\" 或 \"USB共享网络\"\n" +
                   "或\n" +
                   "设置 → 网络 → 热点与网络共享 → USB网络共享";
        }
    }
    
    /**
     * 检测并建立USB网络连接
     * @param isInitiator 是否是发起端
     * @return 连接结果，-1=需要启用USB网络，0=失败，1=成功
     */
    public int establishConnection(boolean isInitiator) {
        this.isHost = isInitiator;
        
        Log.d(TAG, "建立USB网络连接 (角色: " + (isInitiator ? "发起端" : "等待端") + ")");
        
        // 1. 检查USB网络是否已启用
        if (!checkUsbNetworkEnabled()) {
            Log.w(TAG, "USB网络未启用，需要用户手动开启");
            return -1; // 需要启用
        }
        
        // 2. 检测USB网络接口和IP
        if (!detectUsbNetwork()) {
            Log.e(TAG, "未检测到USB网络接口");
            return 0; // 失败
        }
        
        // 3. 根据角色执行不同的连接流程
        if (isInitiator) {
            return connectAsInitiator() ? 1 : 0;
        } else {
            return waitForConnection() ? 1 : 0;
        }
    }
    
    /**
     * 检测USB网络接口
     */
    private boolean detectUsbNetwork() {
        try {
            // 等待USB网络接口出现
            for (int retry = 0; retry < 30; retry++) {
                Enumeration<NetworkInterface> interfaces = NetworkInterface.getNetworkInterfaces();
                if (interfaces == null) {
                    Thread.sleep(500);
                    continue;
                }
                
                for (NetworkInterface intf : Collections.list(interfaces)) {
                    String name = intf.getName().toLowerCase();
                    
                    // 检查是否是USB网络接口
                    if (name.contains("usb") || name.contains("rndis") || 
                        name.contains("ncm") || name.contains("ecm")) {
                        
                        // 获取IP地址
                        Enumeration<InetAddress> addresses = intf.getInetAddresses();
                        for (InetAddress addr : Collections.list(addresses)) {
                            if (addr instanceof Inet4Address && !addr.isLoopbackAddress()) {
                                localIp = addr.getHostAddress();
                                Log.d(TAG, "✓ 检测到USB网络: " + name + " IP: " + localIp);
                                return true;
                            }
                        }
                    }
                }
                
                Log.d(TAG, "等待USB网络接口... (" + (retry + 1) + "/30)");
                Thread.sleep(500);
            }
            
            Log.e(TAG, "未检测到USB网络接口");
            return false;
            
        } catch (Exception e) {
            Log.e(TAG, "检测USB网络失败", e);
            return false;
        }
    }
    
    /**
     * 作为发起端连接
     */
    private boolean connectAsInitiator() {
        try {
            Log.d(TAG, "[发起端] 开始扫描对端IP...");
            
            // 计算对端可能的IP地址
            String[] possibleIps = calculatePossibleRemoteIps();
            
            // 尝试连接对端
            for (String ip : possibleIps) {
                Log.d(TAG, "尝试连接: " + ip + ":" + TRANSFER_PORT);
                
                try {
                    Socket socket = new Socket();
                    socket.connect(new InetSocketAddress(ip, TRANSFER_PORT), 2000);
                    socket.close();
                    
                    remoteIp = ip;
                    Log.d(TAG, "✓ 成功连接到对端: " + remoteIp);
                    return true;
                    
                } catch (IOException e) {
                    // 连接失败，继续尝试下一个
                }
            }
            
            Log.e(TAG, "无法连接到对端");
            return false;
            
        } catch (Exception e) {
            Log.e(TAG, "[发起端] 连接失败", e);
            return false;
        }
    }
    
    /**
     * 等待对端连接
     */
    private boolean waitForConnection() {
        try {
            Log.d(TAG, "[等待端] 启动服务器等待连接...");
            
            ServerSocket serverSocket = new ServerSocket(TRANSFER_PORT);
            serverSocket.setSoTimeout(30000); // 30秒超时
            
            Log.d(TAG, "服务器监听: " + localIp + ":" + TRANSFER_PORT);
            
            Socket socket = serverSocket.accept();
            remoteIp = socket.getInetAddress().getHostAddress();
            
            Log.d(TAG, "✓ 接收到连接: " + remoteIp);
            
            socket.close();
            serverSocket.close();
            
            return true;
            
        } catch (Exception e) {
            Log.e(TAG, "[等待端] 等待连接失败", e);
            return false;
        }
    }
    
    /**
     * 计算可能的对端IP地址
     */
    private String[] calculatePossibleRemoteIps() {
        if (localIp == null) {
            return new String[0];
        }
        
        // 解析本地IP
        String[] parts = localIp.split("\\.");
        if (parts.length != 4) {
            return new String[0];
        }
        
        String baseIp = parts[0] + "." + parts[1] + "." + parts[2] + ".";
        int lastOctet = Integer.parseInt(parts[3]);
        
        // 生成可能的IP列表
        String[] possibleIps = new String[10];
        int count = 0;
        
        // 尝试相邻的IP
        for (int offset : new int[]{1, -1, 2, -2, 3, -3}) {
            int targetOctet = lastOctet + offset;
            if (targetOctet > 0 && targetOctet < 255) {
                possibleIps[count++] = baseIp + targetOctet;
            }
        }
        
        // 尝试常见的对端IP
        possibleIps[count++] = baseIp + "129";
        possibleIps[count++] = baseIp + "130";
        possibleIps[count++] = baseIp + "1";
        possibleIps[count++] = baseIp + "2";
        
        // 去除null值
        String[] result = new String[count];
        System.arraycopy(possibleIps, 0, result, 0, count);
        
        return result;
    }
    
    /**
     * 获取本地IP
     */
    public String getLocalIp() {
        return localIp;
    }
    
    /**
     * 获取对端IP
     */
    public String getRemoteIp() {
        return remoteIp;
    }
    
    /**
     * 是否是发起端
     */
    public boolean isHost() {
        return isHost;
    }
    
    /**
     * 获取传输端口
     */
    public int getTransferPort() {
        return TRANSFER_PORT;
    }
}

