package com.example.usbToSerialPort;

import androidx.appcompat.app.AppCompatActivity;

import android.os.Bundle;
import android.view.View;

import com.example.usbToSerialPort.databinding.ActivityMainBinding;

public class MainActivity extends AppCompatActivity {

    ActivityMainBinding viewBinding;
    USBTransferUtil USB;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        viewBinding = ActivityMainBinding.inflate(getLayoutInflater());
        setContentView(viewBinding.getRoot());

        USB = USBTransferUtil.getInstance();
        USB.init(this);
        // 数据接收
        USB.setOnUSBDateReceive(new USBTransferUtil.OnUSBDateReceive() {
            @Override
            public void onReceive(String data_str) {
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        viewBinding.receive.append("receive: "+data_str+"\r\n");
                    }
                });
            }
        });
        // 连接
        viewBinding.connect.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                USB.connect();
            }
        });
        // 下发数据
        viewBinding.send.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                String content_str = viewBinding.content.getText().toString();
                if(!content_str.equals("")){
                    USB.write(USBTransferUtil.string2Hex(content_str));
                    viewBinding.receive.append("send: "+content_str+"\r\n");
                }
            }
        });
        // 断开
        viewBinding.disconnect.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                USB.disconnect();
            }
        });
    }


    @Override
    protected void onResume() {
        USB.connect();  // 当系统监测到usb插入动作后跳转到此页面时
        super.onResume();
    }

    @Override
    protected void onDestroy() {
        if(USBTransferUtil.isConnectUSB){USB.disconnect();}
        super.onDestroy();
    }
}