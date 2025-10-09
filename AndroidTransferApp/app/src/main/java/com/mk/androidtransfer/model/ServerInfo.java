package com.mk.androidtransfer.model;

/**
 * 服务器信息模型
 */
public class ServerInfo {
    private String name;        // 服务器名称
    private String ipAddress;   // IP地址
    private int port;           // 端口号
    private boolean available;  // 是否可用

    public ServerInfo(String name, String ipAddress, int port) {
        this.name = name;
        this.ipAddress = ipAddress;
        this.port = port;
        this.available = true;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getIpAddress() {
        return ipAddress;
    }

    public void setIpAddress(String ipAddress) {
        this.ipAddress = ipAddress;
    }

    public int getPort() {
        return port;
    }

    public void setPort(int port) {
        this.port = port;
    }

    public boolean isAvailable() {
        return available;
    }

    public void setAvailable(boolean available) {
        this.available = available;
    }

    /**
     * 获取完整的服务器URL
     */
    public String getServerUrl() {
        return "http://" + ipAddress + ":" + port + "/";
    }

    /**
     * 获取显示的IP地址（带端口）
     */
    public String getDisplayAddress() {
        return ipAddress + ":" + port;
    }
    
    /**
     * 获取IP地址（兼容旧代码）
     */
    public String getIp() {
        return ipAddress;
    }
    
    @Override
    public boolean equals(Object obj) {
        if (this == obj) return true;
        if (obj == null || getClass() != obj.getClass()) return false;
        ServerInfo that = (ServerInfo) obj;
        return port == that.port && ipAddress.equals(that.ipAddress);
    }
    
    @Override
    public int hashCode() {
        int result = ipAddress.hashCode();
        result = 31 * result + port;
        return result;
    }
}
