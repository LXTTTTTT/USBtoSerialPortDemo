package com.example.usbToSerialPort;

import android.app.Activity;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.hardware.usb.UsbDeviceConnection;
import android.hardware.usb.UsbManager;
import android.os.Build;
import android.util.Log;
import android.widget.Toast;
import com.hoho.android.usbserial.driver.UsbSerialDriver;
import com.hoho.android.usbserial.driver.UsbSerialPort;
import com.hoho.android.usbserial.driver.UsbSerialProber;
import com.hoho.android.usbserial.util.SerialInputOutputManager;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UnsupportedEncodingException;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

// usb 数据传输工具
public class USBTransferUtil {

    private String TAG = "USBTransferUtil";
    public static boolean isConnectUSB = false;  // 连接标识
    private Context my_context;
    private UsbManager manager;  // usb管理器

    private BroadcastReceiver usbReceiver;  // 广播监听：判断usb设备授权操作
    private static final String INTENT_ACTION_GRANT_USB = BuildConfig.APPLICATION_ID + ".INTENT_ACTION_GRANT_USB";  // usb权限请求标识
    private final String IDENTIFICATION = " USB-Serial Controller D";  // 目标设备标识

// 顺序： manager - availableDrivers（所有可用设备） - UsbSerialDriver（目标设备对象） - UsbDeviceConnection（设备连接对象） - UsbSerialPort（设备的端口，一般只有1个）
    private List<UsbSerialDriver> availableDrivers = new ArrayList<>();  // 所有可用设备
    private UsbSerialDriver usbSerialDriver;  // 当前连接的设备
    private UsbDeviceConnection usbDeviceConnection;  // 连接对象
    private UsbSerialPort usbSerialPort;  // 设备端口对象，通过这个读写数据
    private SerialInputOutputManager inputOutputManager;  // 数据输入输出流管理器

// 连接参数，按需求自行修改，一般情况下改变的参数只有波特率，数据位、停止位、奇偶校验都是固定的8/1/none ---------------------
    private int baudRate = 115200;  // 波特率
    private int dataBits = 8;  // 数据位
    private int stopBits = UsbSerialPort.STOPBITS_1;  // 停止位
    private int parity = UsbSerialPort.PARITY_NONE;// 奇偶校验

// 单例 -------------------------
    private static USBTransferUtil usbTransferUtil;
    public static USBTransferUtil getInstance() {
        if(usbTransferUtil == null){
            usbTransferUtil = new USBTransferUtil();
        }
        return usbTransferUtil;
    }
// 接口 -------------------------
    public interface OnUSBDateReceive{ void onReceive(String data_str);}
    private OnUSBDateReceive onUSBDateReceive;
    public void setOnUSBDateReceive(OnUSBDateReceive onUSBDateReceive){this.onUSBDateReceive = onUSBDateReceive;}

    public void init(Context context){
        my_context = context;
        manager = (UsbManager) context.getSystemService(Context.USB_SERVICE);
    }

    public void setBaudRate(int baudRate){this.baudRate = baudRate;}

    public void connect(){
        if(!isConnectUSB){
            registerReceiver();  // 注册广播监听
            refreshDevice();  // 拿到已连接的usb设备列表
            connectDevice();  // 建立连接
        }
    }

    // 注册usb授权监听广播
    public void registerReceiver(){
        usbReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                Log.e(TAG, "onReceive: " + intent.getAction());
                if(INTENT_ACTION_GRANT_USB.equals(intent.getAction())) {
                    // 授权操作完成，连接
//                    boolean granted = intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false);  // 不知为何获取到的永远都是 false 因此无法判断授权还是拒绝
                    connectDevice();
                }
            }
        };
        my_context.registerReceiver(usbReceiver,new IntentFilter(INTENT_ACTION_GRANT_USB));
    }

    // 刷新当前可用 usb设备
    public void refreshDevice(){
        availableDrivers.clear();
        availableDrivers = UsbSerialProber.getDefaultProber().findAllDrivers(manager);
        Log.e(TAG, "当前可用 usb 设备数量: " + availableDrivers.size() );
        // 有设备可以连接
        if(availableDrivers.size() != 0){
            // 当时开发用的是定制平板电脑有 2 个usb口，所以搜索到两个
            if(availableDrivers.size()>1){
                for (int i = 0; i < availableDrivers.size(); i++) {
                    UsbSerialDriver availableDriver = availableDrivers.get(i);
                    String productName = availableDriver.getDevice().getProductName();
                    Log.e(TAG, "productName: "+productName);
                    // 我是通过 ProductName 这个参数来识别我要连接的设备
                    if(productName.equals(IDENTIFICATION)){
                        usbSerialDriver = availableDriver;
                    }
                }
            }
            // 通常手机只有充电口 1 个
            else {
                usbSerialDriver = availableDrivers.get(0);
            }
            usbSerialPort = usbSerialDriver.getPorts().get(0);  // 一般设备的端口都只有一个，具体要参考设备的说明文档
            // 同时申请设备权限
            if(!manager.hasPermission(usbSerialDriver.getDevice())){
                int flags = Build.VERSION.SDK_INT >= Build.VERSION_CODES.M ? PendingIntent.FLAG_IMMUTABLE : 0;
                PendingIntent usbPermissionIntent = PendingIntent.getBroadcast(my_context, 0, new Intent(INTENT_ACTION_GRANT_USB), flags);
                manager.requestPermission(usbSerialDriver.getDevice(), usbPermissionIntent);
            }
        }
        // 没有设备
        else {
            Toast.makeText(my_context,"请先接入设备",Toast.LENGTH_SHORT).show();
        }
    }

    // 连接设备
    public void connectDevice(){
        if(usbSerialDriver == null || inputOutputManager != null){return;}
        // 判断是否拥有权限
        boolean hasPermission = manager.hasPermission(usbSerialDriver.getDevice());
        if(hasPermission){
            usbDeviceConnection = manager.openDevice(usbSerialDriver.getDevice());  // 拿到连接对象
            if(usbSerialPort == null){return;}
            try {
                usbSerialPort.open(usbDeviceConnection);  // 打开串口
                usbSerialPort.setParameters(baudRate, dataBits, stopBits, parity);  // 设置串口参数：波特率 - 115200 ， 数据位 - 8 ， 停止位 - 1 ， 奇偶校验 - 无
                startReceiveData();  // 开启数据监听
                init_device();  // 下发初始化指令
            } catch (IOException e) {
                e.printStackTrace();
            }
        } else {
            Toast.makeText(my_context,"请先授予权限再连接",Toast.LENGTH_SHORT).show();
        }
    }

    ByteArrayOutputStream baos = new ByteArrayOutputStream();
    private byte[] readBuffer = new byte[1024 * 2];  // 缓冲区
    // 开启数据接收监听
    public void startReceiveData(){
        if(usbSerialPort == null || !usbSerialPort.isOpen()){return;}
        inputOutputManager = new SerialInputOutputManager(usbSerialPort, new SerialInputOutputManager.Listener() {
            @Override
            public void onNewData(byte[] data) {
            // 在这里处理接收到的 usb 数据 -------------------------------
                // 按照结尾标识符处理
                baos.write(data,0,data.length);
                readBuffer = baos.toByteArray();
                if (readBuffer.length >= 2 && readBuffer[readBuffer.length - 2] == (byte)'\r' && readBuffer[readBuffer.length - 1] == (byte)'\n') {
                    String data_str = bytes2string(readBuffer);
                    Log.i(TAG, "收到 usb 数据: " + data_str);
                    if(onUSBDateReceive!=null){onUSBDateReceive.onReceive(data_str);}
                    baos.reset();  // 重置
                }
                // 直接处理
//                String data_str = bytes2string(data);
//                Log.i(TAG, "收到 usb 数据: " + data_str);
            }
            @Override
            public void onRunError(Exception e) {
                Log.e(TAG, "usb 断开了" );
                disconnect();
                e.printStackTrace();
            }
        });
        inputOutputManager.start();
        isConnectUSB = true;  // 修改连接标识
        Toast.makeText(my_context,"连接成功",Toast.LENGTH_SHORT).show();
    }

    // 下发数据：建议使用线程池
    public void write(String data_hex){
        if(usbSerialPort != null){
            Log.e(TAG, "当前usb状态: isOpen-" + usbSerialPort.isOpen() );
            // 当串口打开时再下发
            if(usbSerialPort.isOpen()){
                byte[] data_bytes = hex2bytes(data_hex);  // 将字符数据转化为 byte[]
                if (data_bytes == null || data_bytes.length == 0) return;
                try {
                    usbSerialPort.write(data_bytes,0);  // 写入数据，延迟设置太大的话如果下发间隔太小可能报错
                } catch (IOException e) {
                    e.printStackTrace();
                }
            }else {
                Log.e(TAG, "write: usb 未连接");
            }
        }
    }


    // 断开连接
    public void disconnect(){
        try{
            // 停止数据接收监听
            if(inputOutputManager != null){
                inputOutputManager.stop();
                inputOutputManager = null;
            }
            // 关闭端口
            if(usbSerialPort != null){
                usbSerialPort.close();
                usbSerialPort = null;
            }
            // 关闭连接
            if(usbDeviceConnection != null){
                usbDeviceConnection.close();
                usbDeviceConnection = null;
            }
            // 清除设备
            if(usbSerialDriver != null){
                usbSerialDriver = null;
            }
            // 清空设备列表
            availableDrivers.clear();
            // 注销广播监听
            if(usbReceiver != null){
                my_context.unregisterReceiver(usbReceiver);
            }
            if(isConnectUSB){
                isConnectUSB = false;  // 修改标识
            }
            Log.e(TAG, "断开连接" );
        }catch (Exception e){
            e.printStackTrace();
        }
    }


    // 下发设备初始化指令
    public void init_device(){
        try {
            Thread.sleep(500);
        } catch (InterruptedException e) {
            e.printStackTrace();
        }
        write("6861686168610D0A");  // 查询 IC 信息
    }


    public static String bytes2string(byte[] bytes) {
        if (bytes == null) {return "";}
        String newStr = null;
        try {
            newStr = new String(bytes, "GB18030").trim();
        } catch (UnsupportedEncodingException e) {
            e.printStackTrace();
        }
        return newStr;
    }

    public static byte[] hex2bytes(String hex) {
        if (hex == null || hex.length() < 1) {return null;}
        // 如果长度不是偶数，则前面补0
        if (hex.length() % 2 != 0) {hex = "0" + hex;}
        byte[] bytes = new byte[(hex.length() + 1) / 2];
        try {
            for (int i = 0, j = 0; i < hex.length(); i += 2) {
                byte hight = (byte) (Character.digit(hex.charAt(i), 16) & 0xff);
                byte low = (byte) (Character.digit(hex.charAt(i + 1), 16) & 0xff);
                bytes[j++] = (byte) (hight << 4 | low);
            }
        } catch (Exception e) {
            e.printStackTrace();
            return null;
        }
        return bytes;
    }

    public static String string2Hex(String str) {
        String hex;
        try {
            byte[] bytes = string2bytes(str,"GB18030");
            hex = bytes2Hex(bytes);
        } catch (UnsupportedEncodingException e) {
            e.printStackTrace();
            return null;
        }
        return hex;
    }

    public static byte[] string2bytes(String str, String charset) throws UnsupportedEncodingException {
        if (str == null) {
            return null;
        }
        return str.getBytes(charset);
    }

    public static String bytes2Hex(byte[] bytes){
        String hex = "";
        for (int i = 0; i < bytes.length; i++) {
            int value = bytes[i] & 0xff;
            String hexVaule = Integer.toHexString(value);
            if (hexVaule.length() < 2) {
                hexVaule = "0" + hexVaule;
            }
            hex += hexVaule;
        }
        return hex;
    }

}
