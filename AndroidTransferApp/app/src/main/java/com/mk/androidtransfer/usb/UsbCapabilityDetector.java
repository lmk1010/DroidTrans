package com.mk.androidtransfer.usb;

import android.content.Context;
import android.content.pm.PackageManager;
import android.hardware.usb.UsbAccessory;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbManager;
import android.os.Build;
import android.util.Log;

import java.io.BufferedReader;
import java.io.File;
import java.io.InputStreamReader;
import java.net.NetworkInterface;
import java.util.Collections;
import java.util.Enumeration;
import java.util.HashMap;

/**
 * USB方案全面检测工具
 * 检测所有可能的USB P2P通信方案
 */
public class UsbCapabilityDetector {
    private static final String TAG = "UsbCapabilityDetector";
    
    private Context context;
    private UsbManager usbManager;
    
    public UsbCapabilityDetector(Context context) {
        this.context = context;
        this.usbManager = (UsbManager) context.getSystemService(Context.USB_SERVICE);
    }
    
    /**
     * 执行全面检测
     */
    public DetectionResult detectAllCapabilities() {
        DetectionResult result = new DetectionResult();
        
        Log.d(TAG, "========================================");
        Log.d(TAG, "开始USB能力全面检测");
        Log.d(TAG, "设备: " + Build.MANUFACTURER + " " + Build.MODEL);
        Log.d(TAG, "Android: " + Build.VERSION.RELEASE + " (API " + Build.VERSION.SDK_INT + ")");
        Log.d(TAG, "========================================");
        
        // 1. USB Host/OTG检测
        result.usbHost = checkUsbHost();
        
        // 2. USB Accessory检测
        result.usbAccessory = checkUsbAccessory();
        
        // 3. USB RNDIS/网络检测
        result.usbRndis = checkUsbRndis();
        
        // 4. USB串口检测
        result.usbSerial = checkUsbSerial();
        
        // 5. ADB检测
        result.adbUsb = checkAdbUsb();
        
        // 6. 内核支持检测
        result.kernelSupport = checkKernelSupport();
        
        // 7. 系统权限检测
        result.systemPermissions = checkSystemPermissions();
        
        // 8. Root检测
        result.hasRoot = checkRoot();
        
        // 综合评估
        result.recommendedSolution = evaluateRecommendation(result);
        
        Log.d(TAG, "========================================");
        Log.d(TAG, "检测完成");
        Log.d(TAG, "推荐方案: " + result.recommendedSolution);
        Log.d(TAG, "========================================");
        
        return result;
    }
    
    /**
     * 检测USB Host/OTG支持
     */
    private UsbHostResult checkUsbHost() {
        UsbHostResult result = new UsbHostResult();
        
        Log.d(TAG, "\n=== USB Host/OTG 检测 ===");
        
        // 检查硬件特性
        result.hasHostFeature = context.getPackageManager()
            .hasSystemFeature("android.hardware.usb.host");
        Log.d(TAG, "硬件特性: " + (result.hasHostFeature ? "✓" : "✗"));
        
        // 检查UsbManager
        result.hasUsbManager = (usbManager != null);
        Log.d(TAG, "UsbManager: " + (result.hasUsbManager ? "✓" : "✗"));
        
        // 检查当前连接的设备
        if (usbManager != null) {
            HashMap<String, UsbDevice> devices = usbManager.getDeviceList();
            result.connectedDevices = devices.size();
            Log.d(TAG, "已连接设备数: " + result.connectedDevices);
            
            for (UsbDevice device : devices.values()) {
                Log.d(TAG, "  设备: " + device.getProductName() + 
                    " (VID: 0x" + Integer.toHexString(device.getVendorId()) +
                    ", PID: 0x" + Integer.toHexString(device.getProductId()) + ")");
            }
        }
        
        // 检查内核OTG支持
        result.kernelOtgSupport = checkFile("/sys/class/android_usb/android0/functions") ||
                                  checkFile("/sys/kernel/config/usb_gadget");
        Log.d(TAG, "内核OTG: " + (result.kernelOtgSupport ? "✓" : "✗"));
        
        result.supported = result.hasHostFeature && result.hasUsbManager;
        Log.d(TAG, "总体支持: " + (result.supported ? "✓ 支持" : "✗ 不支持"));
        
        return result;
    }
    
    /**
     * 检测USB Accessory支持
     */
    private UsbAccessoryResult checkUsbAccessory() {
        UsbAccessoryResult result = new UsbAccessoryResult();
        
        Log.d(TAG, "\n=== USB Accessory 检测 ===");
        
        // 检查硬件特性
        result.hasAccessoryFeature = context.getPackageManager()
            .hasSystemFeature("android.hardware.usb.accessory");
        Log.d(TAG, "硬件特性: " + (result.hasAccessoryFeature ? "✓" : "✗"));
        
        // 检查当前Accessory
        if (usbManager != null) {
            UsbAccessory[] accessories = usbManager.getAccessoryList();
            result.hasAccessories = (accessories != null && accessories.length > 0);
            
            if (result.hasAccessories) {
                Log.d(TAG, "已连接Accessory数: " + accessories.length);
                for (UsbAccessory acc : accessories) {
                    Log.d(TAG, "  Accessory: " + acc.getManufacturer() + " " + acc.getModel());
                }
            } else {
                Log.d(TAG, "未检测到Accessory");
            }
        }
        
        // 检查AOA支持
        result.aoaSupport = checkAOASupport();
        Log.d(TAG, "AOA协议: " + (result.aoaSupport ? "✓" : "✗"));
        
        result.supported = result.hasAccessoryFeature;
        result.deprecated = (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S); // Android 12+
        
        if (result.deprecated) {
            Log.d(TAG, "⚠️ 注意: Accessory API在Android 12+已被标记为废弃");
        }
        
        Log.d(TAG, "总体支持: " + (result.supported ? "✓ 支持" : "✗ 不支持"));
        
        return result;
    }
    
    /**
     * 检测USB RNDIS/网络支持
     */
    private UsbRndisResult checkUsbRndis() {
        UsbRndisResult result = new UsbRndisResult();
        
        Log.d(TAG, "\n=== USB RNDIS/网络 检测 ===");
        
        // 检查网络接口
        try {
            Enumeration<NetworkInterface> interfaces = NetworkInterface.getNetworkInterfaces();
            if (interfaces != null) {
                for (NetworkInterface intf : Collections.list(interfaces)) {
                    String name = intf.getName().toLowerCase();
                    if (name.contains("usb") || name.contains("rndis") || 
                        name.contains("ncm") || name.contains("ecm")) {
                        result.hasUsbInterface = true;
                        result.interfaceName = intf.getName();
                        Log.d(TAG, "USB网络接口: " + intf.getName());
                    }
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "检查网络接口失败", e);
        }
        
        if (!result.hasUsbInterface) {
            Log.d(TAG, "未找到USB网络接口");
        }
        
        // 检查内核RNDIS支持
        result.kernelRndis = checkFile("/sys/class/android_usb/android0/f_rndis") ||
                            checkCommand("ls /sys/kernel/config/usb_gadget/*/functions/rndis.*");
        Log.d(TAG, "内核RNDIS: " + (result.kernelRndis ? "✓" : "✗"));
        
        // 检查USB网络共享功能
        result.usbTetheringAvailable = checkUsbTethering();
        Log.d(TAG, "USB网络共享: " + (result.usbTetheringAvailable ? "✓" : "✗"));
        
        // 重要：手机对手机无法使用
        result.phoneToPhoneSupport = false;
        Log.d(TAG, "⚠️ 手机对手机: ✗ 不支持（仅支持连接电脑）");
        
        result.supported = result.kernelRndis && result.usbTetheringAvailable;
        Log.d(TAG, "总体支持: " + (result.supported ? "✓ 支持(仅电脑)" : "✗ 不支持"));
        
        return result;
    }
    
    /**
     * 检测USB串口支持
     */
    private UsbSerialResult checkUsbSerial() {
        UsbSerialResult result = new UsbSerialResult();
        
        Log.d(TAG, "\n=== USB串口 检测 ===");
        
        // 检查串口驱动
        String[] serialPaths = {
            "/dev/ttyUSB0",
            "/dev/ttyACM0", 
            "/dev/ttyGS0",
            "/sys/class/tty"
        };
        
        for (String path : serialPaths) {
            if (checkFile(path)) {
                result.serialDevices.add(path);
                Log.d(TAG, "串口设备: " + path);
            }
        }
        
        result.hasSerialSupport = !result.serialDevices.isEmpty();
        
        // 检查内核串口支持
        result.kernelSerialSupport = checkCommand("cat /proc/tty/drivers | grep serial");
        Log.d(TAG, "内核串口: " + (result.kernelSerialSupport ? "✓" : "✗"));
        
        result.supported = result.hasSerialSupport;
        result.needsRoot = true; // 串口通常需要root
        
        Log.d(TAG, "总体支持: " + (result.supported ? "✓ 支持(需要Root)" : "✗ 不支持"));
        
        return result;
    }
    
    /**
     * 检测ADB over USB
     */
    private AdbUsbResult checkAdbUsb() {
        AdbUsbResult result = new AdbUsbResult();
        
        Log.d(TAG, "\n=== ADB over USB 检测 ===");
        
        // 检查ADB状态
        result.adbEnabled = checkCommand("getprop ro.adb.secure");
        Log.d(TAG, "ADB功能: " + (result.adbEnabled ? "✓" : "✗"));
        
        // 检查USB调试
        result.usbDebugging = android.provider.Settings.Secure.getInt(
            context.getContentResolver(),
            android.provider.Settings.Global.ADB_ENABLED, 0) == 1;
        Log.d(TAG, "USB调试: " + (result.usbDebugging ? "✓ 已开启" : "✗ 未开启"));
        
        // ADB不能用于P2P通信
        result.p2pCapable = false;
        Log.d(TAG, "⚠️ ADB不适用于手机P2P通信");
        
        result.supported = false;
        
        return result;
    }
    
    /**
     * 检测内核支持
     */
    private KernelSupportResult checkKernelSupport() {
        KernelSupportResult result = new KernelSupportResult();
        
        Log.d(TAG, "\n=== 内核支持 检测 ===");
        
        // USB Gadget
        result.usbGadget = checkFile("/sys/kernel/config/usb_gadget") ||
                          checkFile("/sys/class/android_usb");
        Log.d(TAG, "USB Gadget: " + (result.usbGadget ? "✓" : "✗"));
        
        // ConfigFS
        result.configFs = checkFile("/sys/kernel/config");
        Log.d(TAG, "ConfigFS: " + (result.configFs ? "✓" : "✗"));
        
        // FunctionFS
        result.functionFs = checkFile("/dev/usb-ffs");
        Log.d(TAG, "FunctionFS: " + (result.functionFs ? "✓" : "✗"));
        
        return result;
    }
    
    /**
     * 检测系统权限
     */
    private SystemPermissionsResult checkSystemPermissions() {
        SystemPermissionsResult result = new SystemPermissionsResult();
        
        Log.d(TAG, "\n=== 系统权限 检测 ===");
        
        // USB权限
        result.hasUsbPermission = context.checkSelfPermission("android.permission.USB_PERMISSION") 
            == PackageManager.PERMISSION_GRANTED;
        Log.d(TAG, "USB权限: " + (result.hasUsbPermission ? "✓" : "✗"));
        
        // 写入系统设置权限
        result.canWriteSettings = android.provider.Settings.System.canWrite(context);
        Log.d(TAG, "写入设置: " + (result.canWriteSettings ? "✓" : "✗"));
        
        // 系统应用
        result.isSystemApp = (context.getApplicationInfo().flags & 
            android.content.pm.ApplicationInfo.FLAG_SYSTEM) != 0;
        Log.d(TAG, "系统应用: " + (result.isSystemApp ? "✓" : "✗"));
        
        return result;
    }
    
    /**
     * 检测Root权限
     */
    private boolean checkRoot() {
        Log.d(TAG, "\n=== Root权限 检测 ===");
        
        // 方法1: 检查su命令
        boolean hasSu = checkCommand("su -c 'id'");
        
        // 方法2: 检查常见Root文件
        boolean hasRootFiles = checkFile("/system/xbin/su") || 
                              checkFile("/system/bin/su") ||
                              checkFile("/sbin/su");
        
        // 方法3: 检查Magisk
        boolean hasMagisk = checkFile("/data/adb/magisk");
        
        boolean hasRoot = hasSu || hasRootFiles || hasMagisk;
        
        Log.d(TAG, "Root权限: " + (hasRoot ? "✓ 已Root" : "✗ 未Root"));
        
        return hasRoot;
    }
    
    /**
     * 评估推荐方案
     */
    private String evaluateRecommendation(DetectionResult result) {
        StringBuilder recommendation = new StringBuilder();
        
        recommendation.append("\n╔════════════════════════════════════╗\n");
        recommendation.append("║      USB P2P 方案评估报告          ║\n");
        recommendation.append("╚════════════════════════════════════╝\n\n");
        
        // 可行方案
        recommendation.append("【可尝试方案】\n");
        
        if (result.usbHost.supported && result.usbAccessory.supported && !result.usbAccessory.deprecated) {
            recommendation.append("✓ USB Host + Accessory 模式\n");
            recommendation.append("  - 一台Host，一台Accessory\n");
            recommendation.append("  - 需要对方支持AOA\n");
            recommendation.append("  - 可能被厂商限制\n\n");
        }
        
        if (result.hasRoot) {
            recommendation.append("✓ Root方案（高级）\n");
            recommendation.append("  - 直接操作USB Gadget\n");
            recommendation.append("  - 自定义USB功能\n");
            recommendation.append("  - 需要Root权限\n\n");
        }
        
        // 不可行方案
        recommendation.append("【不可行方案】\n");
        recommendation.append("✗ USB RNDIS网络\n");
        recommendation.append("  - 仅支持连接电脑\n");
        recommendation.append("  - 手机对手机无法使用\n\n");
        
        recommendation.append("✗ USB串口通信\n");
        recommendation.append("  - 需要Root权限\n");
        recommendation.append("  - 实现复杂\n\n");
        
        // 最终建议
        recommendation.append("【最终建议】\n");
        
        if (!result.usbHost.supported && !result.hasRoot) {
            recommendation.append("❌ 该设备不支持任何USB P2P方案\n");
            recommendation.append("💡 建议改用 WiFi Direct (P2P)\n");
            recommendation.append("   - 速度快 (30-40MB/s)\n");
            recommendation.append("   - 兼容性好\n");
            recommendation.append("   - 无需Root\n");
        } else if (result.hasRoot) {
            recommendation.append("⭐ 可尝试Root方案\n");
            recommendation.append("   但实现难度大，稳定性差\n");
            recommendation.append("💡 仍然建议使用 WiFi Direct\n");
        } else {
            recommendation.append("⚠️  可尝试USB Host/Accessory\n");
            recommendation.append("   但成功率低，兼容性差\n");
            recommendation.append("💡 强烈建议使用 WiFi Direct\n");
        }
        
        return recommendation.toString();
    }
    
    // 辅助方法
    private boolean checkFile(String path) {
        return new File(path).exists();
    }
    
    private boolean checkCommand(String command) {
        try {
            Process process = Runtime.getRuntime().exec(command);
            int result = process.waitFor();
            return result == 0;
        } catch (Exception e) {
            return false;
        }
    }
    
    private boolean checkAOASupport() {
        // 检查是否有AOA相关的USB设备
        if (usbManager != null) {
            HashMap<String, UsbDevice> devices = usbManager.getDeviceList();
            for (UsbDevice device : devices.values()) {
                // Google的VID是0x18D1
                if (device.getVendorId() == 0x18D1) {
                    return true;
                }
            }
        }
        return false;
    }
    
    private boolean checkUsbTethering() {
        try {
            String output = executeCommand("getprop sys.usb.config");
            return output != null && output.contains("rndis");
        } catch (Exception e) {
            return false;
        }
    }
    
    private String executeCommand(String command) {
        try {
            Process process = Runtime.getRuntime().exec(command);
            BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()));
            StringBuilder output = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) {
                output.append(line);
            }
            process.waitFor();
            return output.toString();
        } catch (Exception e) {
            return null;
        }
    }
    
    // 结果类
    public static class DetectionResult {
        public UsbHostResult usbHost = new UsbHostResult();
        public UsbAccessoryResult usbAccessory = new UsbAccessoryResult();
        public UsbRndisResult usbRndis = new UsbRndisResult();
        public UsbSerialResult usbSerial = new UsbSerialResult();
        public AdbUsbResult adbUsb = new AdbUsbResult();
        public KernelSupportResult kernelSupport = new KernelSupportResult();
        public SystemPermissionsResult systemPermissions = new SystemPermissionsResult();
        public boolean hasRoot = false;
        public String recommendedSolution = "";
    }
    
    public static class UsbHostResult {
        public boolean supported = false;
        public boolean hasHostFeature = false;
        public boolean hasUsbManager = false;
        public int connectedDevices = 0;
        public boolean kernelOtgSupport = false;
    }
    
    public static class UsbAccessoryResult {
        public boolean supported = false;
        public boolean hasAccessoryFeature = false;
        public boolean hasAccessories = false;
        public boolean aoaSupport = false;
        public boolean deprecated = false;
    }
    
    public static class UsbRndisResult {
        public boolean supported = false;
        public boolean hasUsbInterface = false;
        public String interfaceName = "";
        public boolean kernelRndis = false;
        public boolean usbTetheringAvailable = false;
        public boolean phoneToPhoneSupport = false;
    }
    
    public static class UsbSerialResult {
        public boolean supported = false;
        public boolean hasSerialSupport = false;
        public java.util.List<String> serialDevices = new java.util.ArrayList<>();
        public boolean kernelSerialSupport = false;
        public boolean needsRoot = false;
    }
    
    public static class AdbUsbResult {
        public boolean supported = false;
        public boolean adbEnabled = false;
        public boolean usbDebugging = false;
        public boolean p2pCapable = false;
    }
    
    public static class KernelSupportResult {
        public boolean usbGadget = false;
        public boolean configFs = false;
        public boolean functionFs = false;
    }
    
    public static class SystemPermissionsResult {
        public boolean hasUsbPermission = false;
        public boolean canWriteSettings = false;
        public boolean isSystemApp = false;
    }
}


