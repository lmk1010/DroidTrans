package com.mk.androidtransfer.usb;

import android.app.PendingIntent;
import android.content.Context;
import android.hardware.usb.UsbAccessory;
import android.hardware.usb.UsbConstants;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbDeviceConnection;
import android.hardware.usb.UsbEndpoint;
import android.hardware.usb.UsbInterface;
import android.hardware.usb.UsbManager;
import android.os.Build;
import android.os.ParcelFileDescriptor;
import android.util.Log;

import java.io.FileDescriptor;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;

/**
 * USB AOA (Android Open Accessory) 协议管理器
 * 负责建立USB连接，支持Host和Accessory两种模式
 */
public class UsbAOAManager {
    private static final String TAG = "UsbAOAManager";
    
    // AOA协议版本
    private static final int AOA_PROTOCOL_VERSION = 2;
    
    // AOA控制请求
    private static final int AOA_GET_PROTOCOL = 51;
    private static final int AOA_SEND_IDENT = 52;
    private static final int AOA_START_ACCESSORY = 53;
    
    // Accessory识别字符串
    private static final String MANUFACTURER = "MK";
    private static final String MODEL = "AndroidTransfer";
    private static final String DESCRIPTION = "Android USB Transfer";
    private static final String VERSION = "2.0";
    private static final String URI = "https://github.com/mk/androidtransfer";
    private static final String SERIAL = "0000000012345678";
    
    // USB连接对象
    private UsbManager usbManager;
    private Context context;
    private UsbDevice targetDevice;
    private UsbDeviceConnection deviceConnection;
    private UsbAccessory usbAccessory;
    private ParcelFileDescriptor accessoryFileDescriptor;
    
    // IO流
    private FileInputStream inputStream;
    private FileOutputStream outputStream;
    
    // 端点
    private UsbEndpoint endpointIn;
    private UsbEndpoint endpointOut;
    
    // 连接状态
    private boolean isConnected = false;
    private boolean isHostMode = false;

    public UsbAOAManager(Context context) {
        this.context = context;
        this.usbManager = (UsbManager) context.getSystemService(Context.USB_SERVICE);
    }

    /**
     * 检测是否为Host模式（有USB设备连接）
     */
    public boolean detectHostMode() {
        if (usbManager == null) return false;
        
        for (UsbDevice device : usbManager.getDeviceList().values()) {
            Log.d(TAG, "检测到USB设备: " + device.getProductName() + 
                ", VendorId: 0x" + Integer.toHexString(device.getVendorId()) +
                ", ProductId: 0x" + Integer.toHexString(device.getProductId()));
            
            // 检查是否是AOA设备或Android设备
            if (isAndroidDevice(device) || isAOADevice(device)) {
                targetDevice = device;
                isHostMode = true;
                return true;
            }
        }
        return false;
    }

    /**
     * 检测是否为Accessory模式（作为配件连接到其他设备）
     */
    public boolean detectAccessoryMode() {
        if (usbManager == null) return false;
        
        UsbAccessory[] accessories = usbManager.getAccessoryList();
        if (accessories != null && accessories.length > 0) {
            usbAccessory = accessories[0];
            Log.d(TAG, "检测到USB Accessory: " + usbAccessory.getManufacturer() + 
                " " + usbAccessory.getModel());
            isHostMode = false;
            return true;
        }
        return false;
    }

    /**
     * 判断是否是Android设备
     */
    private boolean isAndroidDevice(UsbDevice device) {
        // Google Vendor ID
        int vendorId = device.getVendorId();
        return vendorId == 0x18D1 || // Google
               vendorId == 0x04E8 || // Samsung
               vendorId == 0x22B8 || // Motorola
               vendorId == 0x0BB4 || // HTC
               vendorId == 0x1004 || // LG
               vendorId == 0x12D1 || // Huawei
               vendorId == 0x2717;   // Xiaomi
    }

    /**
     * 判断是否是AOA设备
     */
    private boolean isAOADevice(UsbDevice device) {
        int vendorId = device.getVendorId();
        int productId = device.getProductId();
        
        // AOA协议的Vendor ID是Google的0x18D1
        // Product ID: 0x2D00 (Accessory), 0x2D01 (Accessory + ADB)
        return vendorId == 0x18D1 && 
               (productId == 0x2D00 || productId == 0x2D01 || 
                productId == 0x2D02 || productId == 0x2D03 ||
                productId == 0x2D04 || productId == 0x2D05);
    }

    /**
     * 作为Host连接到目标设备（发送端）
     */
    public boolean connectAsHost(UsbDevice device) throws IOException {
        if (device == null) {
            device = targetDevice;
        }
        
        if (device == null) {
            Log.e(TAG, "没有找到目标设备");
            return false;
        }

        // 检查权限
        if (!usbManager.hasPermission(device)) {
            Log.w(TAG, "没有USB设备权限");
            return false;
        }

        // 打开设备连接
        deviceConnection = usbManager.openDevice(device);
        if (deviceConnection == null) {
            Log.e(TAG, "无法打开USB设备连接");
            return false;
        }

        Log.d(TAG, "USB设备连接已建立");

        // 如果不是AOA设备，尝试切换到AOA模式
        if (!isAOADevice(device)) {
            Log.d(TAG, "设备不是AOA模式，尝试切换...");
            
            boolean switchSuccess = false;
            try {
                switchSuccess = switchToAOAMode(device);
            } catch (Exception e) {
                Log.e(TAG, "切换AOA模式异常", e);
            }
            
            if (!switchSuccess) {
                Log.e(TAG, "切换到AOA模式失败");
                deviceConnection.close();
                deviceConnection = null;
                return false;
            }
            
            // 关闭当前连接，等待设备重新枚举
            deviceConnection.close();
            deviceConnection = null;
            
            // 切换后设备会重新枚举，需要等待并重新连接
            Log.d(TAG, "设备正在切换到AOA模式，等待重新枚举...");
            
            // 等待设备重新枚举（3-5秒）
            try {
                Thread.sleep(3000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            
            return false; // 返回false，等待设备重新连接
        }

        // 查找并声明接口
        if (!claimInterface(device)) {
            Log.e(TAG, "无法声明USB接口");
            deviceConnection.close();
            deviceConnection = null;
            return false;
        }

        isConnected = true;
        isHostMode = true;
        Log.d(TAG, "Host模式连接成功");
        return true;
    }

    /**
     * 切换设备到AOA模式
     */
    private boolean switchToAOAMode(UsbDevice device) {
        try {
            // 1. 获取协议版本
            byte[] buffer = new byte[2];
            int len = deviceConnection.controlTransfer(
                UsbConstants.USB_DIR_IN | UsbConstants.USB_TYPE_VENDOR,
                AOA_GET_PROTOCOL,
                0, 0,
                buffer, buffer.length,
                5000
            );
            
            if (len != 2) {
                Log.e(TAG, "获取AOA协议版本失败");
                return false;
            }
            
            int version = buffer[0] | (buffer[1] << 8);
            Log.d(TAG, "设备支持的AOA协议版本: " + version);
            
            if (version < 1) {
                Log.e(TAG, "设备不支持AOA协议");
                return false;
            }

            // 2. 发送Accessory识别字符串
            sendString(AOA_SEND_IDENT, 0, MANUFACTURER);
            sendString(AOA_SEND_IDENT, 1, MODEL);
            sendString(AOA_SEND_IDENT, 2, DESCRIPTION);
            sendString(AOA_SEND_IDENT, 3, VERSION);
            sendString(AOA_SEND_IDENT, 4, URI);
            sendString(AOA_SEND_IDENT, 5, SERIAL);

            // 3. 启动Accessory模式
            len = deviceConnection.controlTransfer(
                UsbConstants.USB_DIR_OUT | UsbConstants.USB_TYPE_VENDOR,
                AOA_START_ACCESSORY,
                0, 0,
                null, 0,
                5000
            );

            if (len < 0) {
                Log.e(TAG, "启动AOA模式失败");
                return false;
            }

            Log.d(TAG, "AOA模式启动命令已发送");
            return true;

        } catch (Exception e) {
            Log.e(TAG, "切换到AOA模式时出错", e);
            return false;
        }
    }

    /**
     * 发送字符串到设备
     */
    private void sendString(int request, int index, String string) {
        byte[] bytes = string.getBytes();
        deviceConnection.controlTransfer(
            UsbConstants.USB_DIR_OUT | UsbConstants.USB_TYPE_VENDOR,
            request,
            0, index,
            bytes, bytes.length,
            5000
        );
    }

    /**
     * 声明USB接口并获取端点
     */
    private boolean claimInterface(UsbDevice device) {
        if (device.getInterfaceCount() == 0) {
            Log.e(TAG, "设备没有可用的接口");
            return false;
        }

        // 获取第一个接口
        UsbInterface usbInterface = device.getInterface(0);
        
        // 声明接口
        if (!deviceConnection.claimInterface(usbInterface, true)) {
            Log.e(TAG, "无法声明接口");
            return false;
        }

        // 查找批量传输端点
        for (int i = 0; i < usbInterface.getEndpointCount(); i++) {
            UsbEndpoint endpoint = usbInterface.getEndpoint(i);
            
            if (endpoint.getType() == UsbConstants.USB_ENDPOINT_XFER_BULK) {
                if (endpoint.getDirection() == UsbConstants.USB_DIR_IN) {
                    endpointIn = endpoint;
                    Log.d(TAG, "找到输入端点，最大包大小: " + endpoint.getMaxPacketSize());
                } else if (endpoint.getDirection() == UsbConstants.USB_DIR_OUT) {
                    endpointOut = endpoint;
                    Log.d(TAG, "找到输出端点，最大包大小: " + endpoint.getMaxPacketSize());
                }
            }
        }

        if (endpointIn == null || endpointOut == null) {
            Log.e(TAG, "未找到必需的批量传输端点");
            return false;
        }

        return true;
    }

    /**
     * 作为Accessory连接（接收端）
     */
    public boolean connectAsAccessory(UsbAccessory accessory) throws IOException {
        if (accessory == null) {
            accessory = usbAccessory;
        }
        
        if (accessory == null) {
            Log.e(TAG, "没有找到USB Accessory");
            return false;
        }

        // 检查权限
        if (!usbManager.hasPermission(accessory)) {
            Log.w(TAG, "没有USB Accessory权限");
            return false;
        }

        // 打开Accessory
        accessoryFileDescriptor = usbManager.openAccessory(accessory);
        if (accessoryFileDescriptor == null) {
            Log.e(TAG, "无法打开USB Accessory");
            return false;
        }

        FileDescriptor fd = accessoryFileDescriptor.getFileDescriptor();
        inputStream = new FileInputStream(fd);
        outputStream = new FileOutputStream(fd);

        isConnected = true;
        isHostMode = false;
        Log.d(TAG, "Accessory模式连接成功");
        return true;
    }

    /**
     * 发送数据（Host或Accessory模式）
     */
    public int sendData(byte[] data, int timeout) throws IOException {
        if (!isConnected) {
            throw new IOException("USB未连接");
        }

        if (isHostMode) {
            // Host模式：使用bulkTransfer
            if (endpointOut == null) {
                throw new IOException("输出端点未初始化");
            }
            
            int sent = deviceConnection.bulkTransfer(endpointOut, data, data.length, timeout);
            if (sent < 0) {
                throw new IOException("数据发送失败: " + sent);
            }
            return sent;
            
        } else {
            // Accessory模式：使用FileOutputStream
            if (outputStream == null) {
                throw new IOException("输出流未初始化");
            }
            
            outputStream.write(data);
            outputStream.flush();
            return data.length;
        }
    }

    /**
     * 接收数据（Host或Accessory模式）
     */
    public int receiveData(byte[] buffer, int timeout) throws IOException {
        if (!isConnected) {
            throw new IOException("USB未连接");
        }

        if (isHostMode) {
            // Host模式：使用bulkTransfer
            if (endpointIn == null) {
                throw new IOException("输入端点未初始化");
            }
            
            try {
                int received = deviceConnection.bulkTransfer(endpointIn, buffer, buffer.length, timeout);
                
                // bulkTransfer返回负数表示错误
                if (received < 0) {
                    // -1通常表示超时或没有数据
                    if (received == -1) {
                        return 0; // 返回0表示超时但不是错误
                    }
                    throw new IOException("数据接收失败，错误码: " + received);
                }
                
                return received;
            } catch (Exception e) {
                Log.e(TAG, "Host模式接收数据异常", e);
                throw new IOException("接收失败: " + e.getMessage());
            }
            
        } else {
            // Accessory模式：使用FileInputStream
            if (inputStream == null) {
                throw new IOException("输入流未初始化");
            }
            
            try {
                // FileInputStream.read()可能返回部分数据
                int received = inputStream.read(buffer);
                
                if (received < 0) {
                    throw new IOException("连接已断开");
                }
                
                return received;
            } catch (Exception e) {
                Log.e(TAG, "Accessory模式接收数据异常", e);
                throw new IOException("接收失败: " + e.getMessage());
            }
        }
    }

    /**
     * 断开连接
     */
    public void disconnect() {
        isConnected = false;
        
        try {
            if (inputStream != null) {
                inputStream.close();
                inputStream = null;
            }
        } catch (IOException e) {
            Log.e(TAG, "关闭输入流失败", e);
        }
        
        try {
            if (outputStream != null) {
                outputStream.close();
                outputStream = null;
            }
        } catch (IOException e) {
            Log.e(TAG, "关闭输出流失败", e);
        }
        
        try {
            if (accessoryFileDescriptor != null) {
                accessoryFileDescriptor.close();
                accessoryFileDescriptor = null;
            }
        } catch (IOException e) {
            Log.e(TAG, "关闭Accessory文件描述符失败", e);
        }
        
        if (deviceConnection != null) {
            deviceConnection.close();
            deviceConnection = null;
        }
        
        endpointIn = null;
        endpointOut = null;
        targetDevice = null;
        usbAccessory = null;
        
        Log.d(TAG, "USB连接已断开");
    }

    // Getter方法
    public boolean isConnected() {
        return isConnected;
    }

    public boolean isHostMode() {
        return isHostMode;
    }

    public UsbDevice getTargetDevice() {
        return targetDevice;
    }

    public UsbAccessory getUsbAccessory() {
        return usbAccessory;
    }
    
    public int getMaxPacketSize() {
        if (isHostMode && endpointOut != null) {
            return endpointOut.getMaxPacketSize();
        }
        return 16384; // Accessory模式默认值
    }
}

