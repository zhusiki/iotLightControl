package com.zsc.iotlightcontrol;

import androidx.annotation.NonNull;
import androidx.annotation.RequiresApi;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import android.Manifest;
import android.app.Activity;
import android.content.Context;
import android.content.pm.PackageManager;
import android.hardware.Camera;
import android.hardware.camera2.CameraAccessException;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraManager;
import android.os.Build;
import android.os.Bundle;
import android.text.format.Time;
import android.view.View;
import android.widget.Button;
import android.widget.CompoundButton;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import org.eclipse.paho.client.mqttv3.IMqttDeliveryToken;
import org.eclipse.paho.client.mqttv3.MqttCallback;
import org.eclipse.paho.client.mqttv3.MqttClient;
import org.eclipse.paho.client.mqttv3.MqttConnectOptions;
import org.eclipse.paho.client.mqttv3.MqttMessage;
import org.eclipse.paho.client.mqttv3.MqttTopic;
import org.eclipse.paho.client.mqttv3.persist.MemoryPersistence;
import org.greenrobot.eventbus.EventBus;
import org.greenrobot.eventbus.Subscribe;
import org.greenrobot.eventbus.ThreadMode;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.Properties;
import java.util.ResourceBundle;

import javax.crypto.Mac;
import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;

public class MainActivity extends AppCompatActivity {
    private Button button1,button2;
    private TextView textView,textView2;
    private Camera m_Camera;
    private Switch m_switch;
    private Boolean isFirstStart=true;
    private int requestCameraPermission;

    //初始化必要的阿里云物联网设备信息，信息在thing.properties中可以自行设置
    public static String productKey;
    public static String deviceName;
    public static String deviceSecret;
    public static String regionId;

    //物模型-属性上报topic
    private static String pubTopic;
    private static String setTopic;

    //属性上报payload
    private static final String payloadJson = "{\"id\": %s,\"params\": {\"LightSwitch\": %s,},\"method\": \"thing.event.property.post\"}";
    private static MqttClient mqttClient;

    //时间信息初始化,用于消息时间显示。
    Time time=new Time();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        button1=findViewById(R.id.button1);
        button1.setOnClickListener(new myButton1());
        button2=findViewById(R.id.button2);
        button2.setOnClickListener(new myButton2());
        m_switch=findViewById(R.id.switch2);
        m_switch.setOnCheckedChangeListener(new mySwitch());
        textView=findViewById(R.id.textView9);
        textView2=findViewById(R.id.textView10);
        //第一次启动
        isFirstStart=true;
        //按钮2未连接=不可用
        button2.setEnabled(false);
        button2.setText("当前已与 IOT STUDIO 断开连接");
        //EventBus
        EventBus.getDefault().register(this);
        //确认权限
        if(ContextCompat.checkSelfPermission(MainActivity.this, Manifest.permission.CAMERA)!= PackageManager.PERMISSION_GRANTED){
            button1.setEnabled(false);
            button2.setEnabled(false);
            m_switch.setEnabled(false);
            ActivityCompat.requestPermissions(MainActivity.this,new String[]{Manifest.permission.CAMERA},requestCameraPermission);
        }
        //加载properties文件相关信息
        ResourceBundle resource=ResourceBundle.getBundle("assets/thing");
        productKey=resource.getString("productKey");
        deviceName=resource.getString("deviceName");
        deviceSecret=resource.getString("deviceSecret");
        regionId=resource.getString("regionId");
        pubTopic= "/sys/" + productKey + "/" + deviceName + "/thing/event/property/post";
        setTopic="/sys/" + productKey + "/" + deviceName + "/thing/service/property/set";
    }
    //摄像头权限判定与按钮启用
    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults){
        if(requestCode==requestCameraPermission){
            if(grantResults[0]==PackageManager.PERMISSION_GRANTED){
                button1.setEnabled(true);
                m_switch.setEnabled(true);
            }else {
                Toast.makeText(MainActivity.this,"手机相机权限未授权,无法使用。",Toast.LENGTH_SHORT).show();
                button1.setEnabled(false);
                button2.setEnabled(false);
                m_switch.setEnabled(false);
            }
            return;
        }
        super.onRequestPermissionsResult(requestCode,permissions,grantResults);
    }

    @RequiresApi(api = Build.VERSION_CODES.M)
    @Subscribe(threadMode = ThreadMode.MAIN)
    public void handleMessage(MessageEvent messageEvent) {
        System.out.println("LOG::"+messageEvent.getMessage());
        time.setToNow();
        textView2.setText(time.format("%Y-%m-%d %H:%M:%S")+"\n"+messageEvent.getMessage());
        String jsonData=messageEvent.getMessage();
        try {
            JSONObject jsonObject=new JSONObject(jsonData);
            Object deviceOrder=jsonObject.getJSONObject("params").get("LightSwitch");
            //ord是云平台下发的设备状态 1=打开，0=关闭
            int ord=Integer.parseInt(deviceOrder.toString());
            if(ord==1){
                openLight();
            }
            else if(ord==0){
                closeLight();
            }
            else {
                System.out.println("LOG::"+ord);
            }
        } catch (JSONException e) {
            e.printStackTrace();
        }
    }


    private class myButton1 implements View.OnClickListener{
        @Override
        public void onClick(View view){
            initAliyunIoTClient();
            getDeviceOrder();
            button1.setEnabled(false);
            button1.setText("已连接至 IOT STUDIO");
            button2.setEnabled(true);
            button2.setText("断开与 IOT STUDIO 的连接");
        }
    }

    private class myButton2 implements View.OnClickListener{
        @Override
        public void onClick(View view){
            try{
                if(mqttClient.isConnected()){
                    mqttClient.disconnect();
                    Toast.makeText(getApplicationContext(),"已断开连接",Toast.LENGTH_SHORT).show();
                    button1.setEnabled(true);
                    button1.setText("连接至 IOT STUDIO");
                    button2.setEnabled(false);
                    button2.setText("当前已与 IOT STUDIO 断开连接");
                    textView.setText("消息内容将在此显示。");
                    textView2.setText("消息内容将在此显示。");
                }
            }catch (Exception e){
                e.printStackTrace();
                Toast.makeText(getApplicationContext(),"发生错误，断开连接失败。",Toast.LENGTH_SHORT).show();
            }
        }
    }

    private class mySwitch implements Switch.OnCheckedChangeListener{
        @RequiresApi(api = Build.VERSION_CODES.M)
        @Override
        public void onCheckedChanged(CompoundButton buttonView, boolean isChecked) {
            //防止初始化的时候触发监听
            if (!buttonView.isPressed()) {
                return;
            }
            if(isChecked){
                openLight();
            }else{
                closeLight();
            }
        }
    }

    //打开闪光灯
    @RequiresApi(api = Build.VERSION_CODES.M)
    private void openLight(){
        try {
            //判断API是否大于24（安卓7.0系统对应的API）
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                //获取CameraManager
                CameraManager mCameraManager = (CameraManager) getApplicationContext().getSystemService(Context.CAMERA_SERVICE);
                //获取当前手机所有摄像头设备ID
                assert mCameraManager != null;
                String[] ids  = mCameraManager.getCameraIdList();
                for (String id : ids) {
                    CameraCharacteristics c = mCameraManager.getCameraCharacteristics(id);
                    //查询该摄像头组件是否包含闪光灯
                    Boolean flashAvailable = c.get(CameraCharacteristics.FLASH_INFO_AVAILABLE);
                    Integer lensFacing = c.get(CameraCharacteristics.LENS_FACING);
                    if (flashAvailable != null && flashAvailable && lensFacing != null && lensFacing == CameraCharacteristics.LENS_FACING_BACK) {
                        //打开手电筒
                        mCameraManager.setTorchMode(id, true);
                    }
                }
            }else {
                //小于安卓7.0使用camera方法
                //使用setFlashMode中闪光灯常亮的方法来使手电筒功能生效
                m_Camera = Camera.open();
                Camera.Parameters mParameters;
                mParameters = m_Camera.getParameters();
                mParameters.setFlashMode(Camera.Parameters.FLASH_MODE_TORCH);
                m_Camera.setParameters(mParameters);
            }
            if(mqttClient.isConnected()){
                //设定要发送的闪光灯状态，从而实现云端显示闪光灯的状态
                String payload=postDeviceProperties("1");
                time.setToNow();
                textView.setText(time.format("%Y-%m-%d %H:%M:%S")+"\n"+payload);
                //用户提示以及按钮属性设置
                Toast.makeText(getApplicationContext(), "已打开闪光灯,状态已发送。", Toast.LENGTH_SHORT).show();
            }else{
                Toast.makeText(getApplicationContext(), "未连接至IoT Studio，已打开闪光灯，状态未发送。", Toast.LENGTH_SHORT).show();
            }
            if(!m_switch.isChecked()) {
                m_switch.setChecked(true);
            }
        }catch (Exception e){
            e.printStackTrace();
        }
    }
    //关闭闪光灯
    @RequiresApi(api = Build.VERSION_CODES.M)
    private void closeLight(){
        try{
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                //获取CameraManager
                CameraManager mCameraManager = (CameraManager) getApplicationContext().getSystemService(Context.CAMERA_SERVICE);
                //获取当前手机所有摄像头设备ID
                assert mCameraManager != null;
                String[] ids  = mCameraManager.getCameraIdList();
                for (String id : ids) {
                    CameraCharacteristics c = mCameraManager.getCameraCharacteristics(id);
                    //查询该摄像头组件是否包含闪光灯
                    Boolean flashAvailable = c.get(CameraCharacteristics.FLASH_INFO_AVAILABLE);
                    Integer lensFacing = c.get(CameraCharacteristics.LENS_FACING);
                    if (flashAvailable != null && flashAvailable
                            && lensFacing != null && lensFacing == CameraCharacteristics.LENS_FACING_BACK) {
                        //关闭手电筒
                        mCameraManager.setTorchMode(id, false);
                    }
                }
            }else {
                //小于安卓7.0使用camera方法
                //使用setFlashMode中闪光灯关闭的方法来关闭手电筒
                Camera.Parameters mParameters;
                mParameters = m_Camera.getParameters();
                mParameters.setFlashMode(Camera.Parameters.FLASH_MODE_OFF);
                m_Camera.setParameters(mParameters);
                m_Camera.release();
            }
            if(mqttClient.isConnected()){
                //设定要发送的闪光灯状态，从而实现云端显示闪光灯的状态
                String payload=postDeviceProperties("0");
                time.setToNow();
                textView.setText(time.format("%Y-%m-%d %H:%M:%S")+"\n"+payload);
                //用户提示以及按钮属性设置
                Toast.makeText(getApplicationContext(), "已关闭闪光灯,状态已发送。", Toast.LENGTH_SHORT).show();
            }else {
                Toast.makeText(getApplicationContext(), "未连接至IoT Studio，已关闭闪光灯，状态未发送。", Toast.LENGTH_SHORT).show();
            }
            if(m_switch.isChecked()) {
                m_switch.setChecked(false);
            }
        } catch(Exception ex){
            ex.printStackTrace();
        }
    }

    private static void initAliyunIoTClient() {
        try {
            //连接所需要的信息：服务器地址，客户端id，用户名，密码
            String clientId = "java" + System.currentTimeMillis();
            Map<String, String> params = new HashMap<>(16);
            params.put("productKey", productKey);
            params.put("deviceName", deviceName);
            params.put("clientId", clientId);
            String timestamp = String.valueOf(System.currentTimeMillis());
            params.put("timestamp", timestamp);
            // 这里阿里云的服务器地区为cn-shanghai
            String targetServer = "tcp://" + productKey + ".iot-as-mqtt."+regionId+".aliyuncs.com:1883";
            String mqttclientId = clientId + "|securemode=3,signmethod=hmacsha1,timestamp=" + timestamp + "|";
            String mqttUsername = deviceName + "&" + productKey;
            String mqttPassword = sign(params, deviceSecret, "hmacsha1");
            System.out.println("LOG::deviceSecret="+deviceSecret);
            connectMqtt(targetServer, mqttclientId, mqttUsername, mqttPassword);
        } catch (Exception e) {
            System.out.println("initAliyunIoTClient error " + e.getMessage());
        }
    }

    public static void connectMqtt(String url, String clientId, String mqttUsername, String mqttPassword) throws Exception {
        MemoryPersistence persistence = new MemoryPersistence();
        mqttClient = new MqttClient(url, clientId, persistence);
        MqttConnectOptions connOpts = new MqttConnectOptions();
        // MQTT 3.1.1对应MqttVersion(4)
        connOpts.setMqttVersion(4);
        connOpts.setAutomaticReconnect(false);
        connOpts.setCleanSession(false);
        //用户信息
        connOpts.setUserName(mqttUsername);
        connOpts.setPassword(mqttPassword.toCharArray());
        connOpts.setKeepAliveInterval(60);
        //开始连接
        mqttClient.connect(connOpts);
    }

    //订阅消息
    private static void getDeviceOrder(){
        try{
            // 设置回调
            mqttClient.setCallback(new MqttCallback() {
                @Override
                public void connectionLost(Throwable cause) {
                    System.out.println("LOG::连接丢失");
                    System.out.println("LOG::Reason="+cause);
                }
                @Override
                public void messageArrived(String topic, MqttMessage message) throws Exception {
                    String str=new String(message.getPayload());
                    System.out.println("LOG::收到信息="+str);
                    EventBus.getDefault().post(new MessageEvent(str));
                }
                @Override
                public void deliveryComplete(IMqttDeliveryToken token) {
                    System.out.println("LOG::delivery complete");
                }
            });
            MqttTopic topic = mqttClient.getTopic(setTopic);
            //订阅消息
            int[] Qos  = {1};
            String[] topic1 = {setTopic};
            mqttClient.subscribe(topic1, Qos);
        }catch (Exception e){
            e.printStackTrace();
        }
    }
    public static String sign(Map<String, String> params, String deviceSecret, String signMethod) {
        //将参数Key按字典顺序排序
        String[] sortedKeys = params.keySet().toArray(new String[] {});
        Arrays.sort(sortedKeys);
        //生成规范化请求字符串
        StringBuilder canonicalizedQueryString = new StringBuilder();
        for (String key : sortedKeys) {
            if ("sign".equalsIgnoreCase(key)) {
                continue;
            }
            canonicalizedQueryString.append(key).append(params.get(key));
        }
        try {
            String key = deviceSecret;
            return encryptHMAC(signMethod,canonicalizedQueryString.toString(), key);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private static String postDeviceProperties(String status) {
        String payload="null";
        try {
            //上报数据
            payload = String.format(payloadJson, System.currentTimeMillis(),status);
            System.out.println("post :"+payload);
            MqttMessage message = new MqttMessage(payload.getBytes("utf-8"));
            message.setQos(1);
            mqttClient.publish(pubTopic, message);
        } catch (Exception e) {
            e.printStackTrace();
        }finally {
            return payload;
        }
    }

    //HMACSHA1加密
    public static String encryptHMAC(String signMethod,String content, String key) throws Exception {
        SecretKey secretKey = new SecretKeySpec(key.getBytes("utf-8"), signMethod);
        Mac mac = Mac.getInstance(secretKey.getAlgorithm());
        mac.init(secretKey);
        byte[] data = mac.doFinal(content.getBytes("utf-8"));
        return bytesToHexString(data);
    }
    //数组转十六进制
    public static final String bytesToHexString(byte[] bArray) {
        StringBuffer sb = new StringBuffer(bArray.length);
        String sTemp;
        for (int i = 0; i < bArray.length; i++) {
            sTemp = Integer.toHexString(0xFF & bArray[i]);
            if (sTemp.length() < 2) {
                sb.append(0);
            }
            sb.append(sTemp.toUpperCase());
        }
        return sb.toString();
    }

    @RequiresApi(api = Build.VERSION_CODES.M)
    @Override
    protected void onDestroy(){
        super.onDestroy();
        //取消注册事件
        EventBus.getDefault().unregister(this);
        closeLight();
        try {
            if(mqttClient.isConnected()){
                mqttClient.close();
            }
            m_Camera.release();
        }catch (Exception e){
            e.printStackTrace();
        }
    }

    @Override
    protected void onStop(){
        super.onStop();
        Toast.makeText(getApplicationContext(),"程序仍在后台运行",Toast.LENGTH_SHORT).show();
    }

    @Override
    protected void onStart(){
        super.onStart();
        if(!isFirstStart) {
            try {
                if(mqttClient!=null){
                    if (mqttClient.isConnected()) {
                        button1.setEnabled(false);
                        button2.setEnabled(true);
                        Toast.makeText(getApplicationContext(), "仍在连接状态中，请继续操作。", Toast.LENGTH_SHORT).show();
                    }
                }
            } catch (Exception e) {
                e.printStackTrace();
            }
        }
        isFirstStart=false;
    }
}
