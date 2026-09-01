package com.mk.androidtransfer.model;

/**
 * 服务器信息模型
 */
public class ServerInfo {
    private String name;        // 服务器名称
    private String ipAddress;   // IP地址
    private int port;           // 端口号
    private String engine;      // go / swift / android
    private boolean available;  // 是否可用

    public ServerInfo(String name, String ipAddress, int port) {
        this(name, ipAddress, port, "");
    }

    public ServerInfo(String name, String ipAddress, int port, String engine) {
        this.name = name;
        this.ipAddress = ipAddress;
        this.port = port;
        this.engine = engine == null ? "" : engine.trim().toLowerCase(java.util.Locale.US);
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

    public String getEngine() {
        return engine;
    }

    public void setEngine(String engine) {
        this.engine = engine == null ? "" : engine.trim().toLowerCase(java.util.Locale.US);
    }

    public boolean isAndroidPhone() {
        return "android".equals(engine);
    }

    public boolean isIPhone() {
        return "swift".equals(engine);
    }

    public boolean isPhone() {
        if (isAndroidPhone() || isIPhone() || port == 9600) {
            return true;
        }
        String n = name == null ? "" : name.toLowerCase(java.util.Locale.US);
        return n.contains("iphone") || n.contains("phone") || n.contains("android")
                || n.contains("pixel") || n.contains("oneplus") || n.contains("oppo")
                || n.contains("vivo") || n.contains("xiaomi") || n.contains("redmi")
                || n.contains("huawei") || n.contains("honor") || n.contains("samsung");
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
