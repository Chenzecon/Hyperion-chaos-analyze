package com.chenzecon.oznerftcdebug;

import android.app.Activity;
import android.content.Context;
import android.net.wifi.WifiInfo;
import android.net.wifi.WifiManager;
import android.net.nsd.NsdManager;
import android.net.nsd.NsdServiceInfo;
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
    private EditText ssid, password, callbackIp;
    private CheckBox sendV2;
    private ScrollView scroll;
    private FtcServer server;
    private WifiManager.MulticastLock multicastLock;
    private NsdManager nsdManager;
    private final List<NsdManager.DiscoveryListener> mdnsListeners = new ArrayList<NsdManager.DiscoveryListener>();
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

        callbackIp = new EditText(this);
        callbackIp.setHint("FTC 回连 IP（默认手机 IP）");
        callbackIp.setSingleLine(true);
        root.addView(callbackIp);

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

        LinearLayout mdnsRow = new LinearLayout(this);
        Button mdns = new Button(this); mdns.setText("扫描 mDNS");
        Button mdnsStop = new Button(this); mdnsStop.setText("停止 mDNS");
        mdnsRow.addView(mdns, new LinearLayout.LayoutParams(0,-2,1));
        mdnsRow.addView(mdnsStop, new LinearLayout.LayoutParams(0,-2,1));
        root.addView(mdnsRow);

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
        mdns.setOnClickListener(v -> startMdnsScan());
        mdnsStop.setOnClickListener(v -> stopMdnsScan());
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
            runOnUiThread(() -> {
                if (i != null && s != null && s.length()>0 && !"<unknown ssid>".equals(s)) ssid.setText(s.replace("\"", ""));
                if (callbackIp != null && !"0.0.0.0".equals(ip)) callbackIp.setText(ip);
            });
        });
    }

    private int wifiIp() {
        WifiInfo i = ((WifiManager)getApplicationContext().getSystemService(Context.WIFI_SERVICE)).getConnectionInfo();
        return i == null ? 0 : i.getIpAddress();
    }

    private static int parseIpv4(String s) {
        try {
            String[] a=s.split("\\.");
            if(a.length!=4)return 0;
            int b0=Integer.parseInt(a[0]),b1=Integer.parseInt(a[1]),b2=Integer.parseInt(a[2]),b3=Integer.parseInt(a[3]);
            if((b0|b1|b2|b3)<0||b0>255||b1>255||b2>255||b3>255)return 0;
            return b0|(b1<<8)|(b2<<16)|(b3<<24);
        }catch(Exception e){return 0;}
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
        acquireMulticastLock();
        startFtc();
        executor.execute(() -> {
            try {
                Thread.sleep(300);
                String s = ssid.getText().toString().trim();
                String p = password.getText().toString();
                if (s.length()==0) { log("ERROR SSID_EMPTY"); return; }
                int local = wifiIp();
                if (local==0) { log("ERROR WIFI_IP_EMPTY"); return; }
                String cbText=callbackIp.getText().toString().trim();
                int callback=local;
                if (cbText.length()>0) {
                    callback=parseIpv4(cbText);
                    if(callback==0){ log("ERROR FTC_CALLBACK_IP_INVALID " + cbText); return; }
                }
                EasyLink sender = new EasyLink(s,p,local,callback);
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
                ui("测试结束，FTC继续监听，自动扫描 mDNS");
                startMdnsScan();
            } catch (Throwable t) {
                log("TEST_EXCEPTION " + t);
                ui("测试异常");
            }
        });
    }

    private void stopAll() {
        releaseMulticastLock();
        if (server != null) server.stop();
        server = null;
        ui("已停止");
        log("STOP_ALL");
    }

    private void startMdnsScan() {
        try {
            stopMdnsScan();
            nsdManager=(NsdManager)getSystemService(Context.NSD_SERVICE);
            if(nsdManager==null){log("MDNS_MANAGER_NULL");return;}
            acquireMulticastLock();
            discoverMdnsType("_easylink._tcp.");
            discoverMdnsType("_http._tcp.");
            log("MDNS_SCAN_START types=_easylink._tcp.,_http._tcp.");
            ui("正在扫描 mDNS");
        } catch(Throwable t) {
            log("MDNS_START_EXCEPTION "+t);
        }
    }

    private void discoverMdnsType(final String type) {
        if(nsdManager==null)return;
        final NsdManager.DiscoveryListener listener=new NsdManager.DiscoveryListener(){
            @Override public void onDiscoveryStarted(String serviceType){log("MDNS_DISCOVERY_STARTED type="+serviceType);}
            @Override public void onServiceFound(NsdServiceInfo serviceInfo){
                log("MDNS_SERVICE_FOUND type="+type+" name="+serviceInfo.getServiceName()+" info="+serviceInfo);
                try{
                    nsdManager.resolveService(serviceInfo,new NsdManager.ResolveListener(){
                        @Override public void onResolveFailed(NsdServiceInfo si,int errorCode){
                            log("MDNS_RESOLVE_FAILED type="+type+" name="+si.getServiceName()+" code="+errorCode);
                        }
                        @Override public void onServiceResolved(NsdServiceInfo si){
                            String host=si.getHost()==null?"null":si.getHost().getHostAddress();
                            String attrsText=decodeMdnsAttributes(si.getAttributes());
                            log("MDNS_RESOLVED type="+type+" name="+si.getServiceName()+" host="+host+" port="+si.getPort()+" attrs="+attrsText);
                            if (host != null && !"null".equals(host) && si.getPort() > 0) {
                                probeTcpService(type, si.getServiceName(), host, si.getPort(), si.getAttributes());
                            }
                        }
                    });
                }catch(Throwable t){log("MDNS_RESOLVE_EXCEPTION type="+type+" "+t);}
            }
            @Override public void onServiceLost(NsdServiceInfo serviceInfo){log("MDNS_SERVICE_LOST type="+type+" name="+serviceInfo.getServiceName());}
            @Override public void onDiscoveryStopped(String serviceType){log("MDNS_DISCOVERY_STOPPED type="+serviceType);}
            @Override public void onStartDiscoveryFailed(String serviceType,int errorCode){
                log("MDNS_DISCOVERY_START_FAILED type="+serviceType+" code="+errorCode);
                try{nsdManager.stopServiceDiscovery(this);}catch(Throwable ignored){}
            }
            @Override public void onStopDiscoveryFailed(String serviceType,int errorCode){log("MDNS_DISCOVERY_STOP_FAILED type="+serviceType+" code="+errorCode);}
        };
        mdnsListeners.add(listener);
        nsdManager.discoverServices(type,NsdManager.PROTOCOL_DNS_SD,listener);
    }

    private String decodeMdnsAttributes(Map<String, byte[]> attrs) {
        if (attrs == null || attrs.isEmpty()) return "{}";
        StringBuilder out = new StringBuilder("{");
        boolean first = true;
        for (Map.Entry<String, byte[]> e : attrs.entrySet()) {
            if (!first) out.append(", ");
            first = false;
            byte[] b = e.getValue();
            String ascii = printableAscii(b);
            String utf8;
            try { utf8 = new String(b, StandardCharsets.UTF_8); } catch (Throwable t) { utf8 = ""; }
            out.append(e.getKey()).append("={hex=").append(hex(b))
               .append(", ascii=").append(ascii)
               .append(", utf8=").append(escapeOneLine(utf8)).append("}");
        }
        out.append("}");
        return out.toString();
    }

    private String printableAscii(byte[] b) {
        if (b == null) return "";
        StringBuilder s = new StringBuilder();
        for (byte x : b) {
            int c = x & 255;
            s.append(c >= 32 && c <= 126 ? (char)c : '.');
        }
        return s.toString();
    }

    private String escapeOneLine(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\").replace("\r", "\\\\r").replace("\n", "\\\\n");
    }

    private void probeTcpService(String type, String serviceName, String host, int port, Map<String, byte[]> attrs) {
        executor.execute(() -> {
            Socket socket = new Socket();
            File rawFile = new File(logFile.getParentFile(),
                    "tcp_" + host.replace('.', '_') + "_" + port + "_" + System.currentTimeMillis() + ".bin");
            try {
                log("TCP_PROBE_START type=" + type + " name=" + serviceName + " dst=" + host + ":" + port);
                socket.setReuseAddress(true);
                socket.connect(new InetSocketAddress(host, port), 3000);
                socket.setSoTimeout(1800);
                log("TCP_CONNECTED dst=" + host + ":" + port + " local=" + socket.getLocalSocketAddress());

                InputStream in = new BufferedInputStream(socket.getInputStream());
                ByteArrayOutputStream pre = new ByteArrayOutputStream();
                byte[] buf = new byte[4096];

                try {
                    int n = in.read(buf);
                    if (n > 0) {
                        pre.write(buf, 0, n);
                        log("TCP_PRE_READ bytes=" + n + " hex=" + hex(buf, n) + " text=" + oneLine(new String(buf, 0, n, StandardCharsets.UTF_8)));
                    }
                } catch (SocketTimeoutException e) {
                    log("TCP_PRE_READ_TIMEOUT");
                }

                String req = "GET / HTTP/1.1\\r\\nHost: " + host + ":" + port
                        + "\\r\\nConnection: close\\r\\nUser-Agent: OznerFTC-Debug/1.1\\r\\n\\r\\n";
                OutputStream out = socket.getOutputStream();
                out.write(req.getBytes(StandardCharsets.US_ASCII));
                out.flush();
                log("TCP_HTTP_GET_SENT dst=" + host + ":" + port + " bytes=" + req.length());

                ByteArrayOutputStream all = new ByteArrayOutputStream();
                if (pre.size() > 0) all.write(pre.toByteArray());

                socket.setSoTimeout(2500);
                long deadline = System.currentTimeMillis() + 5000;
                while (System.currentTimeMillis() < deadline && all.size() < 65536) {
                    try {
                        int n = in.read(buf);
                        if (n < 0) break;
                        if (n == 0) continue;
                        all.write(buf, 0, n);
                        log("TCP_READ_CHUNK bytes=" + n + " total=" + all.size());
                    } catch (SocketTimeoutException e) {
                        break;
                    }
                }

                byte[] result = all.toByteArray();
                writeFile(rawFile, result);
                log("TCP_RAW_SAVED path=" + rawFile.getName() + " bytes=" + result.length);
                if (result.length > 0) {
                    int show = Math.min(result.length, 8192);
                    log("TCP_RAW_HEX bytes=" + result.length + " hex=" + hex(result, show));
                    log("TCP_RAW_TEXT text=" + oneLine(new String(result, 0, show, StandardCharsets.UTF_8)));
                } else {
                    log("TCP_NO_RESPONSE");
                }
                log("TCP_PROBE_DONE dst=" + host + ":" + port);
            } catch (Throwable t) {
                log("TCP_PROBE_EXCEPTION dst=" + host + ":" + port + " " + t);
            } finally {
                try { socket.close(); } catch (Exception ignored) {}
            }
        });
    }

    private static String hex(byte[] b, int len) {
        if (b == null) return "";
        int n = Math.min(len, b.length);
        StringBuilder s = new StringBuilder(n * 2);
        for (int i = 0; i < n; i++) s.append(String.format(Locale.US, "%02X", b[i] & 255));
        return s.toString();
    }

    private void stopMdnsScan() {
        if(nsdManager!=null){
            for(NsdManager.DiscoveryListener l: new ArrayList<NsdManager.DiscoveryListener>(mdnsListeners)){
                try{nsdManager.stopServiceDiscovery(l);}catch(Throwable ignored){}
            }
        }
        mdnsListeners.clear();
        log("MDNS_SCAN_STOP");
    }

    private void acquireMulticastLock() {
        try {
            WifiManager wm=(WifiManager)getApplicationContext().getSystemService(Context.WIFI_SERVICE);
            if (wm != null) {
                multicastLock=wm.createMulticastLock("OznerFTCDebug");
                multicastLock.setReferenceCounted(false);
                multicastLock.acquire();
                log("WIFI_MULTICAST_LOCK acquired");
            }
        } catch(Throwable t) {
            log("WIFI_MULTICAST_LOCK_EXCEPTION " + t);
        }
    }

    private void releaseMulticastLock() {
        try {
            if (multicastLock != null && multicastLock.isHeld()) multicastLock.release();
            multicastLock=null;
            log("WIFI_MULTICAST_LOCK released");
        } catch(Throwable t) {
            log("WIFI_MULTICAST_UNLOCK_EXCEPTION " + t);
        }
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
        final byte[] ssid,key,userInfo,sendData; final int localIp, callbackIpValue; final InetAddress broadcast;
        EasyLink(String s,String p,int ip,int callbackIp)throws Exception{
            ssid=s.getBytes(StandardCharsets.UTF_8); key=p.getBytes(StandardCharsets.UTF_8); localIp=ip; callbackIpValue=callbackIp;
            String strIp=String.format(Locale.US,"%08x",callbackIp);
            byte[] ipBytes=hexStringToBytes(strIp);
            userInfo=new byte[5];
            userInfo[0]=0x23;
            System.arraycopy(ipBytes,0,userInfo,1,4);
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
        String describe(){return "wifiIp="+ip(localIp)+" broadcast="+broadcast.getHostAddress()+" ftcIp="+ip(callbackIpValue)+" userInfo="+hex(userInfo)+" v3Len="+(sendData[0]&255);}
        void v3(){
            try {
                InetAddress localAddress=InetAddress.getByName(ip(localIp));
                DatagramSocket s=new DatagramSocket(null);
                s.setReuseAddress(true);
                s.bind(new InetSocketAddress(localAddress,0));
                s.setBroadcast(true);
                log("V3_SOCKET local="+s.getLocalSocketAddress()+" dst="+broadcast.getHostAddress()+":"+UDP_PORT);
                send(s,0x5AA,"START1");
                send(s,0x5AB,"START2");
                send(s,0x5AC,"START3");
                int k=0, j=1;
                for(int i=0; i<(sendData[0]&255); i++){
                    int len=j*256+(sendData[i]&255);
                    send(s,len,"DATA"+i);
                    if(i%4==3){
                        k++;
                        send(s,1280+k,"SYNC"+k);
                    }
                    j++;
                    if(j==5) j=1;
                }
                log("V3_SOCKET_CLOSE");
                s.close();
            } catch(Exception e) {
                log("EASYLINK_V3_EXCEPTION "+e);
            }
        }
        void send(DatagramSocket s,int len,String tag)throws Exception{
            byte[] d=new byte[Math.min(len,1500)];
            Arrays.fill(d,(byte)0);
            DatagramPacket p=new DatagramPacket(d,d.length,broadcast,UDP_PORT);
            s.send(p);
            log("V3_UDP_SEND tag="+tag+" len="+d.length+" src="+s.getLocalSocketAddress()+" dst="+broadcast.getHostAddress()+":"+UDP_PORT);
            Thread.sleep(10);
        }
        void v2(){
            try{
                String head="239.118.0.0";
                String ip;
                String syncHString="abcdefghijklmnopqrstuvw";
                int userlength=userInfo.length;

                byte[] syncHBuffer=syncHString.getBytes(StandardCharsets.US_ASCII);
                byte[] data=new byte[2];
                data[0]=(byte)ssid.length;
                data[1]=(byte)key.length;
                byte[] temp=new byte[ssid.length+key.length];
                System.arraycopy(ssid,0,temp,0,ssid.length);
                System.arraycopy(key,0,temp,ssid.length,key.length);
                byte[] base=new byte[data.length+temp.length];
                System.arraycopy(data,0,base,0,data.length);
                System.arraycopy(temp,0,base,data.length,temp.length);
                data=base;

                for(int z=0;z<5;z++){
                    InetSocketAddress sockAddr=new InetSocketAddress(InetAddress.getByName(head),randomPort());
                    sendV2Exact(new DatagramPacket(syncHBuffer,20,sockAddr),head,"SYNC"+z);
                    Thread.sleep(10);
                }

                if(userlength==0){
                    for(int k=0;k<data.length;k+=2){
                        if(k+1<data.length) ip="239.126."+(data[k]&255)+"."+(data[k+1]&255);
                        else ip="239.126."+(data[k]&255)+".0";
                        InetSocketAddress sockAddr=new InetSocketAddress(InetAddress.getByName(ip),randomPort());
                        byte[] bbbb=new byte[k/2+20];
                        sendV2Exact(new DatagramPacket(bbbb,k/2+20,sockAddr),ip,"DATA"+k);
                        Thread.sleep(10);
                    }
                }else{
                    if(data.length%2==0){
                        if(userInfo.length==0){
                            byte[] temp_length={(byte)userlength,0,0};
                            data=concat(data,temp_length);
                        }else{
                            byte[] temp_length={(byte)userlength,0};
                            data=concat(data,temp_length);
                        }
                    }else{
                        byte[] temp_length={0,(byte)userlength,0};
                        data=concat(data,temp_length);
                    }
                    data=concat(data,userInfo);

                    for(int k=0;k<data.length;k+=2){
                        if(k+1<data.length) ip="239.126."+(data[k]&255)+"."+(data[k+1]&255);
                        else ip="239.126."+(data[k]&255)+".0";
                        InetSocketAddress sockAddr=new InetSocketAddress(InetAddress.getByName(ip),randomPort());
                        byte[] bbbb=new byte[k/2+20];
                        sendV2Exact(new DatagramPacket(bbbb,k/2+20,sockAddr),ip,"DATA"+k);
                        Thread.sleep(10);
                    }
                }
                log("EASYLINK_V2_SENT_EXACT bytes="+data.length+" sourcePort=54064");
            }catch(Exception e){
                log("EASYLINK_V2_EXCEPTION "+e);
            }
        }
        byte[] concat(byte[] a,byte[] b){
            byte[] r=new byte[a.length+b.length];
            System.arraycopy(a,0,r,0,a.length);
            System.arraycopy(b,0,r,a.length,b.length);
            return r;
        }
        void sendV2Exact(DatagramPacket p,String group,String tag)throws Exception{
            MulticastSocket sock=null;
            try{
                sock=new MulticastSocket(54064);
                InetAddress g=InetAddress.getByName(group);
                sock.joinGroup(g);
                sock.send(p);
                log("V2_UDP_SEND tag="+tag+" srcPort=54064 len="+p.getLength()+" dst="+group+":"+p.getPort());
            }finally{
                if(sock!=null)sock.close();
            }
        }
        int randomPort(){int n=new Random().nextInt(65536);return n<10000?65523:n;}
        byte[] hexStringToBytes(String s){byte[] r=new byte[s.length()/2];for(int i=0;i<r.length;i++)r[i]=(byte)Integer.parseInt(s.substring(i*2,i*2+2),16);return r;}
    }
}
