package com.chenzecon.oznerftcdebug;

import android.app.Activity;
import android.content.Context;
import android.net.wifi.WifiInfo;
import android.net.wifi.WifiManager;
import android.os.Bundle;
import android.os.Environment;
import android.text.InputType;
import android.util.Log;
import android.view.View;
import android.widget.*;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.*;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MainActivity extends Activity {
    private static final int FTC_PORT = 8000;
    private static final int UDP_PORT = 50000;
    private final ExecutorService executor = Executors.newCachedThreadPool();
    private final Object logLock = new Object();

    private TextView status, logView;
    private EditText ssid, password;
    private CheckBox sendV2;
    private ScrollView scroll;
    private FtcServer server;
    private File logFile, lastJson;

    @Override public void onCreate(Bundle b) {
        super.onCreate(b);
        initFiles();
        buildUi();
        log("APP_START 1.0-debug");
        showNetwork();
    }

    private void initFiles() {
        File d = getExternalFilesDir(Environment.DIRECTORY_DOCUMENTS);
        if (d == null) d = getFilesDir();
        if (!d.exists()) d.mkdirs();
        logFile = new File(d, "ozner_ftc_debug.log");
        lastJson = new File(d, "ozner_ftc_last.json");
    }

    private void buildUi() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(16,16,16,10);

        TextView title = new TextView(this);
        title.setText("Ozner FTC / EasyLink Debug");
        title.setTextSize(20);
        root.addView(title);

        status = new TextView(this);
        status.setText("状态：未启动");
        status.setPadding(0,8,0,8);
        root.addView(status);

        ssid = new EditText(this);
        ssid.setHint("Wi-Fi SSID");
        ssid.setSingleLine(true);
        root.addView(ssid);

        password = new EditText(this);
        password.setHint("Wi-Fi 密码");
        password.setSingleLine(true);
        password.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        root.addView(password);

        sendV2 = new CheckBox(this);
        sendV2.setText("同时发送 EasyLink V2");
        sendV2.setChecked(true);
        root.addView(sendV2);

        LinearLayout row = new LinearLayout(this);
        Button listen = new Button(this); listen.setText("启动 FTC");
        Button test = new Button(this); test.setText("开始配网测试");
        Button stop = new Button(this); stop.setText("停止");
        row.addView(listen, new LinearLayout.LayoutParams(0,-2,1));
        row.addView(test, new LinearLayout.LayoutParams(0,-2,1));
        row.addView(stop, new LinearLayout.LayoutParams(0,-2,1));
        root.addView(row);

        Button path = new Button(this); path.setText("显示日志文件路径");
        root.addView(path);

        scroll = new ScrollView(this);
        logView = new TextView(this);
        logView.setTextSize(11);
        logView.setTypeface(android.graphics.Typeface.MONOSPACE);
        scroll.addView(logView);
        root.addView(scroll, new LinearLayout.LayoutParams(-1,0,1));

        listen.setOnClickListener(v -> startFtc());
        test.setOnClickListener(v -> startTest());
        stop.setOnClickListener(v -> stopAll());
        path.setOnClickListener(v -> {
            log("LOG_PATH " + logFile.getAbsolutePath());
            log("LAST_JSON_PATH " + lastJson.getAbsolutePath());
            Toast.makeText(this, "已写入界面日志", Toast.LENGTH_LONG).show();
        });
        setContentView(root);
    }

    private void showNetwork() {
        executor.execute(() -> {
            WifiInfo i = ((WifiManager)getApplicationContext().getSystemService(Context.WIFI_SERVICE)).getConnectionInfo();
            final String s = i == null ? "unknown" : i.getSSID();
            final String ip = i == null ? "0.0.0.0" : ip(i.getIpAddress());
            log("NETWORK wifiIp=" + ip + " ssid=" + s);
            runOnUiThread(() -> { if (i != null && s != null && s.length()>0 && !"<unknown ssid>".equals(s)) ssid.setText(s.replace("\"", "")); });
        });
    }

    private int wifiIp() {
        WifiInfo i = ((WifiManager)getApplicationContext().getSystemService(Context.WIFI_SERVICE)).getConnectionInfo();
        return i == null ? 0 : i.getIpAddress();
    }

    private static String ip(int x) {
        return (x & 255) + "." + ((x>>>8)&255) + "." + ((x>>>16)&255) + "." + ((x>>>24)&255);
    }

    private void startFtc() {
        if (server != null && server.running) { log("FTC_ALREADY_RUNNING"); return; }
        server = new FtcServer();
        server.start();
    }

    private void startTest() {
        startFtc();
        executor.execute(() -> {
            try {
                Thread.sleep(300);
                String s = ssid.getText().toString().trim();
                String p = password.getText().toString();
                if (s.length()==0) { log("ERROR SSID_EMPTY"); return; }
                int local = wifiIp();
                if (local==0) { log("ERROR WIFI_IP_EMPTY"); return; }
                EasyLink sender = new EasyLink(s,p,local);
                log("EASYLINK_CONFIG " + sender.describe());
                long end = System.currentTimeMillis()+30000;
                int round=0;
                while (System.currentTimeMillis()<end && server!=null && server.running) {
                    round++;
                    log("EASYLINK_ROUND " + round + " V3_START");
                    sender.v3();
                    log("EASYLINK_ROUND " + round + " V3_DONE");
                    if (sendV2.isChecked()) {
                        sender.v2();
                        log("EASYLINK_ROUND " + round + " V2_DONE");
                    }
                    Thread.sleep(100);
                }
                log("EASYLINK_TEST_FINISHED");
                ui("测试结束，FTC继续监听");
            } catch (Throwable t) {
                log("TEST_EXCEPTION " + t);
                ui("测试异常");
            }
        });
    }

    private void stopAll() {
        if (server != null) server.stop();
        server = null;
        ui("已停止");
        log("STOP_ALL");
    }

    private void ui(String s) { runOnUiThread(() -> status.setText("状态：" + s)); }

    private void log(String s) {
        final String line = "[" + new SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US).format(new Date()) + "] " + s + "\n";
        synchronized (logLock) {
            try (FileOutputStream f = new FileOutputStream(logFile,true)) { f.write(line.getBytes(StandardCharsets.UTF_8)); } catch (Exception ignored) {}
        }
        runOnUiThread(() -> {
            logView.append(line);
            scroll.post(() -> scroll.fullScroll(View.FOCUS_DOWN));
        });
        Log.i("OznerFTCDebug", s);
    }

    private class FtcServer {
        volatile boolean running;
        ServerSocket ss;
        final ExecutorService pool=Executors.newCachedThreadPool();

        void start() {
            try {
                ss=new ServerSocket();
                ss.setReuseAddress(true);
                ss.bind(new InetSocketAddress(FTC_PORT));
                running=true;
                log("FTC_START bind=0.0.0.0:8000");
                executor.execute(() -> {
                    while(running) {
                        try {
                            Socket s=ss.accept();
                            log("FTC_ACCEPT remote=" + s.getRemoteSocketAddress() + " local=" + s.getLocalSocketAddress());
                            pool.execute(() -> handle(s));
                        } catch(IOException e) {
                            if(running) log("FTC_ACCEPT_EXCEPTION " + e);
                        }
                    }
                    log("FTC_ACCEPT_LOOP_EXIT");
                });
            } catch(Exception e) {
                log("FTC_START_EXCEPTION " + e);
                ui("FTC启动失败");
            }
        }

        void handle(Socket s) {
            String remote=String.valueOf(s.getRemoteSocketAddress());
            File raw=new File(logFile.getParentFile(),"ftc_raw_"+System.currentTimeMillis()+".txt");
            try {
                s.setSoTimeout(5000);
                InputStream in=new BufferedInputStream(s.getInputStream());
                ByteArrayOutputStream all=new ByteArrayOutputStream();
                byte[] buf=new byte[4096];
                int first=in.read(buf);
                if(first<0) throw new EOFException("peer closed immediately");
                all.write(buf,0,first);
                log("FTC_FIRST_READ remote="+remote+" bytes="+first);
                int contentLength=parseContentLength(new String(all.toByteArray(),StandardCharsets.ISO_8859_1));
                while(true) {
                    if(contentLength>=0 && all.size()>=headerEnd(all.toByteArray())+4+contentLength) break;
                    if(contentLength<0 && headerEnd(all.toByteArray())>=0) {
                        if(in.available()==0) break;
                    }
                    int n=in.read(buf);
                    if(n<0) break;
                    all.write(buf,0,n);
                    if(all.size()>65536) break;
                }
                byte[] rawBytes=all.toByteArray();
                writeFile(raw,rawBytes);
                String text=new String(rawBytes,StandardCharsets.UTF_8);
                log("FTC_RAW_BYTES remote="+remote+" bytes="+rawBytes.length);
                log("FTC_RAW_TEXT remote="+remote+" " + oneLine(text));
                int h=headerEnd(rawBytes);
                if(h>=0) {
                    String head=new String(rawBytes,0,h,StandardCharsets.ISO_8859_1);
                    log("FTC_HEADER remote="+remote+" " + oneLine(head));
                    int bodyStart=h+4;
                    if(bodyStart<=rawBytes.length) {
                        String body=new String(rawBytes,bodyStart,rawBytes.length-bodyStart,StandardCharsets.UTF_8).trim();
                        if(body.length()>0) {
                            writeFile(lastJson,body.getBytes(StandardCharsets.UTF_8));
                            log("FTC_JSON_SAVED bytes="+body.getBytes(StandardCharsets.UTF_8).length);
                            log("FTC_JSON "+body);
                        }
                    }
                }
                String response="HTTP/1.1 200 OK\r\nConnection: keep-alive\r\nContent-Type: application/json\r\nContent-Length: 2\r\n\r\n{}";
                OutputStream out=s.getOutputStream(); out.write(response.getBytes(StandardCharsets.US_ASCII)); out.flush();
                log("FTC_RESPONSE_SENT remote="+remote);
            } catch(Throwable t) {
                log("FTC_HANDLE_EXCEPTION remote="+remote+" "+t);
            } finally {
                try{s.close();}catch(Exception ignored){}
                log("FTC_CLOSE remote="+remote);
            }
        }

        int parseContentLength(String h) {
            for(String line:h.split("\\r\\n")) {
                int p=line.indexOf(':');
                if(p>0 && "content-length".equalsIgnoreCase(line.substring(0,p).trim()))
                    try{return Integer.parseInt(line.substring(p+1).trim());}catch(Exception ignored){}
            }
            return -1;
        }
        int headerEnd(byte[] b) {
            for(int i=0;i+3<b.length;i++) if(b[i]=='\r'&&b[i+1]=='\n'&&b[i+2]=='\r'&&b[i+3]=='\n') return i;
            return -1;
        }
        String oneLine(String s) { return s.replace("\r","\\r").replace("\n","\\n"); }
        void writeFile(File f,byte[] b)throws IOException{try(FileOutputStream o=new FileOutputStream(f)){o.write(b);}}
        void stop(){running=false;try{if(ss!=null)ss.close();}catch(Exception ignored){}pool.shutdownNow();log("FTC_STOP");}
    }

    private static String safe(String s) { return s==null?"":s; }
    private static String hex(byte[] b){StringBuilder s=new StringBuilder();for(byte x:b)s.append(String.format(Locale.US,"%02X",x&255));return s.toString();}

    private class EasyLink {
        final byte[] ssid,key,userInfo,sendData; final int localIp; final InetAddress broadcast;
        EasyLink(String s,String p,int ip)throws Exception{
            ssid=s.getBytes(StandardCharsets.UTF_8); key=p.getBytes(StandardCharsets.UTF_8); localIp=ip;
            userInfo=new byte[]{0x23,(byte)(ip&255),(byte)((ip>>>8)&255),(byte)((ip>>>16)&255),(byte)((ip>>>24)&255)};
            broadcast=InetAddress.getByName(((ip&255))+"."+((ip>>>8)&255)+"."+((ip>>>16)&255)+".255");
            int total=3+ssid.length+key.length+userInfo.length+2;
            if(total>127) throw new IOException("EasyLink payload too large: "+total);
            sendData=new byte[128]; sendData[0]=(byte)total;
            int i=1; System.arraycopy(ssid,0,sendData,i,ssid.length);i+=ssid.length;
            System.arraycopy(key,0,sendData,i,key.length);i+=key.length;
            System.arraycopy(userInfo,0,sendData,i,userInfo.length);i+=userInfo.length;
            int sum=0;for(int j=0;j<i;j++)sum=(sum+(sendData[j]&255))&65535;
            sendData[i++]=(byte)(sum>>>8);sendData[i]=(byte)sum;
        }
        String describe(){return "wifiIp="+ip(localIp)+" broadcast="+broadcast.getHostAddress()+" userInfo="+hex(userInfo)+" v3Len="+(sendData[0]&255);}
        void v3(){
            try(DatagramSocket s=new DatagramSocket()){
                s.setBroadcast(true);
                send(s,0x5AA);send(s,0x5AB);send(s,0x5AC);
                int k=0,j=1;
                for(int i=0;i<(sendData[0]&255);i++,j++){
                    send(s,j*256+(sendData[i]&255));
                    if(i%4==3){k++;send(s,1280+k);}
                    if(j==4)j=1;
                }
            }catch(Exception e){log("EASYLINK_V3_EXCEPTION "+e);}
        }
        void send(DatagramSocket s,int len)throws Exception{byte[] d=new byte[Math.min(len,1500)];Arrays.fill(d,(byte)0);s.send(new DatagramPacket(d,d.length,broadcast,UDP_PORT));Thread.sleep(10);}
        void v2(){
            try{
                InetAddress head=InetAddress.getByName("239.118.0.0");
                MulticastSocket m=new MulticastSocket();
                byte[] sync="abcdefghijklmnopqrst".getBytes(StandardCharsets.US_ASCII);
                for(int z=0;z<5;z++){m.send(new DatagramPacket(sync,sync.length,head,randomPort()));Thread.sleep(10);}
                byte[] d=new byte[2+ssid.length+key.length+2+userInfo.length];
                d[0]=(byte)ssid.length;d[1]=(byte)key.length;
                System.arraycopy(ssid,0,d,2,ssid.length);System.arraycopy(key,0,d,2+ssid.length,key.length);
                int q=2+ssid.length+key.length;d[q]=(byte)userInfo.length;d[q+1]=0;System.arraycopy(userInfo,0,d,q+2,userInfo.length);
                for(int k=0;k<d.length;k+=2){int a=d[k]&255,b=k+1<d.length?d[k+1]&255:0;InetAddress g=InetAddress.getByName("239.126."+a+"."+b);byte[] p=new byte[k/2+20];m.send(new DatagramPacket(p,p.length,g,randomPort()));Thread.sleep(10);}
                m.close();
            }catch(Exception e){log("EASYLINK_V2_EXCEPTION "+e);}
        }
        int randomPort(){int n=new Random().nextInt(65536);return n<10000?65523:n;}
    }
}
