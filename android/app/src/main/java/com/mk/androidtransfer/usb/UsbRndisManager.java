package com.mk.androidtransfer.usb;

import android.content.Context;
import android.hardware.usb.UsbManager;
import android.net.ConnectivityManager;
import android.net.LinkProperties;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.os.Build;
import android.util.Log;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.util.Collections;
import java.util.Enumeration;

/**
 * USB RNDIS网络管理器
 * 用于检测和启用USB网络（RNDIS/ECM）功能
 */
public class UsbRndisManager {
    private static final String TAG = "UsbRndisManager";
    
    private Context context;
    private UsbManager usbManager;
    private ConnectivityManager connectivityManager;
    
    // RNDIS相关常量
    private static final String USB_FUNCTION_RNDIS = "rndis";
    private static final String USB_FUNCTION_ECM = "ecm";
    
    public UsbRndisManager(Context context) {
        this.context = context;
        this.usbManager = (UsbManager) context.getSystemService(Context.USB_SERVICE);
        this.connectivityManager = (ConnectivityManager) context.getSystemService(Context.CONNECTIVITY_SERVICE);
    }
    
    /**
     * 检测设备是否支持RNDIS
     * @return RndisCheckResult 检测结果
     */
    public RndisCheckResult checkRndisSupport() {
        RndisCheckResult result = new RndisCheckResult();
        
        Log.d(TAG, "=== 开始检测RNDIS支持 ===");
        Log.d(TAG, "设备型号: " + Build.MANUFACTURER + " " + Build.MODEL);
        Log.d(TAG, "Android版本: " + Build.VERSION.RELEASE + " (SDK " + Build.VERSION.SDK_INT + ")");
        
        // 1. 检查USB管理器
        if (usbManager == null) {
            result.supported = false;
            result.errorMessage = "无法访问USB管理器";
            Log.e(TAG, "USB Manager 为 null");
            return result;
        }
        result.hasUsbManager = true;
        Log.d(TAG, "✓ USB管理器可用");
        
        // 2. 检查系统是否支持USB Host
        boolean hasUsbHost = context.getPackageManager().hasSystemFeature("android.hardware.usb.host");
        result.hasUsbHost = hasUsbHost;
        Log.d(TAG, (hasUsbHost ? "✓" : "✗") + " USB Host支持: " + hasUsbHost);
        
        // 3. 检查USB网络接口
        boolean hasUsbInterface = checkUsbNetworkInterface();
        result.hasUsbNetworkInterface = hasUsbInterface;
        Log.d(TAG, (hasUsbInterface ? "✓" : "✗") + " USB网络接口: " + hasUsbInterface);
        
        // 4. 检查RNDIS内核支持
        boolean hasRndisKernel = checkRndisKernelSupport();
        result.hasRndisKernel = hasRndisKernel;
        Log.d(TAG, (hasRndisKernel ? "✓" : "✗") + " RNDIS内核支持: " + hasRndisKernel);
        
        // 5. 检查USB配置文件
        boolean hasUsbConfig = checkUsbConfigFile();
        result.hasUsbConfig = hasUsbConfig;
        Log.d(TAG, (hasUsbConfig ? "✓" : "✗") + " USB配置文件: " + hasUsbConfig);
        
        // 6. 检查系统属性
        String usbFunction = getSystemProperty("sys.usb.config");
        String usbState = getSystemProperty("sys.usb.state");
        result.currentUsbFunction = usbFunction;
        result.currentUsbState = usbState;
        Log.d(TAG, "当前USB功能: " + usbFunction);
        Log.d(TAG, "当前USB状态: " + usbState);
        
        // 7. 检查厂商特性
        result.manufacturer = Build.MANUFACTURER.toLowerCase();
        result.isHuawei = result.manufacturer.contains("huawei") || result.manufacturer.contains("honor");
        result.isXiaomi = result.manufacturer.contains("xiaomi") || result.manufacturer.contains("redmi");
        result.isOppo = result.manufacturer.contains("oppo") || result.manufacturer.contains("oneplus") || result.manufacturer.contains("realme");
        result.isVivo = result.manufacturer.contains("vivo") || result.manufacturer.contains("iqoo");
        result.isSamsung = result.manufacturer.contains("samsung");
        
        Log.d(TAG, "厂商: " + result.manufacturer);
        
        // 综合判断是否支持
        result.supported = result.hasUsbManager && (hasUsbHost || hasUsbInterface || hasRndisKernel);
        
        if (result.supported) {
            result.supportLevel = determineSupportLevel(result);
            Log.d(TAG, "=== 检测结果: 支持RNDIS (级别: " + result.supportLevel + ") ===");
        } else {
            result.errorMessage = "设备不支持USB RNDIS功能";
            Log.d(TAG, "=== 检测结果: 不支持RNDIS ===");
        }
        
        return result;
    }
    
    /**
     * 判断支持级别
     */
    private String determineSupportLevel(RndisCheckResult result) {
        if (result.hasRndisKernel && result.hasUsbConfig) {
            return "完全支持";
        } else if (result.hasUsbNetworkInterface) {
            return "基础支持";
        } else if (result.hasUsbHost) {
            return "可能支持";
        } else {
            return "未知";
        }
    }
    
    /**
     * 检查USB网络接口
     */
    private boolean checkUsbNetworkInterface() {
        try {
            Enumeration<NetworkInterface> interfaces = NetworkInterface.getNetworkInterfaces();
            if (interfaces == null) {
                return false;
            }
            
            for (NetworkInterface intf : Collections.list(interfaces)) {
                String name = intf.getName().toLowerCase();
                // 常见的USB网络接口名称
                if (name.contains("usb") || name.contains("rndis") || 
                    name.contains("ncm") || name.contains("ecm")) {
                    Log.d(TAG, "发现USB网络接口: " + intf.getName());
                    return true;
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "检查网络接口失败", e);
        }
        return false;
    }
    
    /**
     * 检查RNDIS内核支持
     */
    private boolean checkRndisKernelSupport() {
        try {
            // 检查内核模块
            String[] checkPaths = {
                "/sys/class/android_usb/android0/functions",
                "/sys/kernel/config/usb_gadget",
                "/config/usb_gadget",
                "/sys/devices/virtual/android_usb/android0/functions"
            };
            
            for (String path : checkPaths) {
                String output = executeCommand("ls " + path);
                if (output != null && (output.contains("rndis") || output.contains("ncm") || output.contains("ecm"))) {
                    Log.d(TAG, "在 " + path + " 发现RNDIS支持");
                    return true;
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "检查内核支持失败", e);
        }
        return false;
    }
    
    /**
     * 检查USB配置文件
     */
    private boolean checkUsbConfigFile() {
        try {
            String[] checkFiles = {
                "/sys/class/android_usb/android0/functions",
                "/sys/class/android_usb/android0/f_rndis/ethaddr",
                "/config/usb_gadget/g1/functions"
            };
            
            for (String file : checkFiles) {
                String output = executeCommand("ls -la " + file);
                if (output != null && output.length() > 0) {
                    Log.d(TAG, "找到USB配置: " + file);
                    return true;
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "检查USB配置失败", e);
        }
        return false;
    }
    
    /**
     * 获取系统属性
     */
    private String getSystemProperty(String key) {
        try {
            String output = executeCommand("getprop " + key);
            if (output != null) {
                return output.trim();
            }
        } catch (Exception e) {
            Log.e(TAG, "获取系统属性失败: " + key, e);
        }
        return "";
    }
    
    /**
     * 执行Shell命令
     */
    private String executeCommand(String command) {
        try {
            Process process = Runtime.getRuntime().exec(command);
            BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()));
            StringBuilder output = new StringBuilder();
            String line;
            
            while ((line = reader.readLine()) != null) {
                output.append(line).append("\n");
            }
            
            process.waitFor();
            reader.close();
            
            return output.toString();
        } catch (Exception e) {
            Log.e(TAG, "执行命令失败: " + command, e);
            return null;
        }
    }
    
    /**
     * 获取当前USB网络IP地址
     */
    public String getUsbNetworkIp() {
        try {
            Enumeration<NetworkInterface> interfaces = NetworkInterface.getNetworkInterfaces();
            if (interfaces == null) {
                return null;
            }
            
            for (NetworkInterface intf : Collections.list(interfaces)) {
                String name = intf.getName().toLowerCase();
                if (name.contains("usb") || name.contains("rndis")) {
                    Enumeration<InetAddress> addresses = intf.getInetAddresses();
                    for (InetAddress addr : Collections.list(addresses)) {
                        if (addr instanceof Inet4Address && !addr.isLoopbackAddress()) {
                            String ip = addr.getHostAddress();
                            Log.d(TAG, "USB网络IP: " + ip);
                            return ip;
                        }
                    }
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "获取USB IP失败", e);
        }
        return null;
    }
    
    /**
     * 检查USB网络是否已激活
     */
    public boolean isUsbNetworkActive() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            try {
                Network[] networks = connectivityManager.getAllNetworks();
                for (Network network : networks) {
                    NetworkCapabilities caps = connectivityManager.getNetworkCapabilities(network);
                    if (caps != null) {
                        // 检查是否是USB网络
                        LinkProperties props = connectivityManager.getLinkProperties(network);
                        if (props != null) {
                            String interfaceName = props.getInterfaceName();
                            if (interfaceName != null && 
                                (interfaceName.contains("usb") || interfaceName.contains("rndis"))) {
                                Log.d(TAG, "USB网络已激活: " + interfaceName);
                                return true;
                            }
                        }
                    }
                }
            } catch (Exception e) {
                Log.e(TAG, "检查USB网络状态失败", e);
            }
        }
        
        // 备用方法：检查IP地址
        return getUsbNetworkIp() != null;
    }
    
    /**
     * RNDIS检测结果
     */
    public static class RndisCheckResult {
        public boolean supported = false;
        public String supportLevel = "未知";
        public String errorMessage = "";
        
        // 详细检测结果
        public boolean hasUsbManager = false;
        public boolean hasUsbHost = false;
        public boolean hasUsbNetworkInterface = false;
        public boolean hasRndisKernel = false;
        public boolean hasUsbConfig = false;
        
        public String currentUsbFunction = "";
        public String currentUsbState = "";
        
        // 厂商信息
        public String manufacturer = "";
        public boolean isHuawei = false;
        public boolean isXiaomi = false;
        public boolean isOppo = false;
        public boolean isVivo = false;
        public boolean isSamsung = false;
        
        /**
         * 获取详细报告
         */
        public String getDetailedReport() {
            StringBuilder report = new StringBuilder();
            report.append("📱 设备信息\n");
            report.append("厂商: ").append(Build.MANUFACTURER).append("\n");
            report.append("型号: ").append(Build.MODEL).append("\n");
            report.append("Android: ").append(Build.VERSION.RELEASE).append(" (SDK ").append(Build.VERSION.SDK_INT).append(")\n\n");
            
            report.append("🔍 RNDIS支持检测\n");
            report.append(hasUsbManager ? "✓" : "✗").append(" USB管理器\n");
            report.append(hasUsbHost ? "✓" : "✗").append(" USB Host支持\n");
            report.append(hasUsbNetworkInterface ? "✓" : "✗").append(" USB网络接口\n");
            report.append(hasRndisKernel ? "✓" : "✗").append(" RNDIS内核支持\n");
            report.append(hasUsbConfig ? "✓" : "✗").append(" USB配置文件\n\n");
            
            report.append("⚙️ 系统状态\n");
            report.append("USB功能: ").append(currentUsbFunction.isEmpty() ? "未知" : currentUsbFunction).append("\n");
            report.append("USB状态: ").append(currentUsbState.isEmpty() ? "未知" : currentUsbState).append("\n\n");
            
            report.append("📊 综合结果\n");
            report.append("支持状态: ").append(supported ? "✓ 支持" : "✗ 不支持").append("\n");
            report.append("支持级别: ").append(supportLevel).append("\n");
            
            if (!supported && !errorMessage.isEmpty()) {
                report.append("\n⚠️ ").append(errorMessage);
            }
            
            return report.toString();
        }
        
        /**
         * 获取简短总结
         */
        public String getSummary() {
            if (supported) {
                return "✓ 设备支持USB RNDIS网络 (级别: " + supportLevel + ")";
            } else {
                return "✗ 设备不支持USB RNDIS网络\n" + errorMessage;
            }
        }
    }
}

