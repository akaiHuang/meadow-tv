package com.akai.livingtv;

import android.content.*;
import android.os.*;
import org.json.*;
import javax.net.ssl.*;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.security.cert.X509Certificate;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.*;

/** Paired private LAN only; credentials for Codex never leave the Mac. */
final class AssistantClient {
    private final SharedPreferences prefs;
    private final Context context;
    private final Handler main=new Handler(Looper.getMainLooper());
    private final ExecutorService worker=Executors.newSingleThreadExecutor();
    private volatile boolean cancelled;
    private volatile String activeRun="";
    AssistantClient(Context c){context=c.getApplicationContext();prefs=context.getSharedPreferences("living",0);}
    static boolean privateHost(String h){
        if(h==null)return false;
        if(h.equals("localhost")||h.equals("127.0.0.1"))return true;
        if(!h.matches("[0-9.]+"))return false;
        String[] p=h.split("\\.");if(p.length!=4)return false;
        try{int a=Integer.parseInt(p[0]),b=Integer.parseInt(p[1]);for(String s:p)if(Integer.parseInt(s)>255)return false;return a==10||a==192&&b==168||a==172&&b>=16&&b<=31;}catch(Exception e){return false;}
    }
    static final class Reply {final String text;final boolean ok;Reply(String t,boolean success){text=t;ok=success;}}
    void health(Consumer<JSONObject> done,Consumer<String> failed){worker.execute(()->{try{JSONObject result=request("/health",null);main.post(()->{if(!cancelled)done.accept(result);});}catch(Exception e){main.post(()->{if(!cancelled)failed.accept(friendly(e));});}});}
    private String friendly(Exception e){if(e instanceof javax.net.ssl.SSLException)return "電腦配對無法驗證，請重新連接 Meadow。";if(e instanceof java.net.SocketTimeoutException||e instanceof java.net.ConnectException)return "暫時找不到電腦，請確認 Mac 已開機並連上相同 Wi-Fi。";return e.getMessage()!=null&&e.getMessage().startsWith("HTTP 4")?"訊息未能送出，請縮短內容後再試。":"連線中斷了，按一下重新連線即可再試。";}
    boolean configured(){return !prefs.getString("bridgeEndpoint","").isEmpty();}
    void ask(String text,JSONArray history,JSONArray catalog,BiConsumer<JSONObject,Consumer<JSONObject>> action,Consumer<String> status,Consumer<Reply> done){
        cancelled=false;
        JSONObject initialState=TvCommandDispatcher.state(context);
        worker.execute(()->{
            try{
                JSONObject run=request("/chat",new JSONObject().put("text",text).put("history",history).put("catalog",catalog).put("context",initialState));String id=run.getString("id");activeRun=id;main.post(()->{if(!cancelled)status.accept("Codex 已收到，正在回覆…");});long deadline=System.currentTimeMillis()+120000;
                while(!cancelled&&System.currentTimeMillis()<deadline){
                    JSONObject result=request("/run/"+id,null);JSONObject command=result.optJSONObject("command");
                    if(command!=null){
                        CountDownLatch latch=new CountDownLatch(1);JSONObject[] receipt={null};
                        main.post(()->{if(cancelled){latch.countDown();return;}action.accept(command,r->{receipt[0]=r;latch.countDown();});});
                        boolean podcastLookup="queue".equals(command.optString("action"))&&Arrays.asList("search","search_shows","episodes").contains(command.optString("operation"));
                        if(!latch.await(podcastLookup?60:15,TimeUnit.SECONDS)||receipt[0]==null)throw new IOException("TV action timeout");
                        request("/ack/"+id,new JSONObject().put("commandId",command.getString("id")).put("result",receipt[0]));
                    }
                    if(result.optBoolean("done")){String message=result.optString("text");if(message.isEmpty())message="這次沒有取得回答，請稍後再試。";String finalMessage=message;main.post(()->{if(!cancelled)done.accept(new Reply(finalMessage,!result.has("ok")||result.optBoolean("ok")));});return;}
                    Thread.sleep(400);
                }
                if(!cancelled)throw new IOException("timeout");
            }catch(Exception e){main.post(()->{if(!cancelled)done.accept(new Reply(friendly(e),false));});}
            finally{if(cancelled&&!activeRun.isEmpty())try{request("/cancel/"+activeRun,new JSONObject());}catch(Exception ignored){}activeRun="";}
        });
    }
    JSONObject request(String path,JSONObject body)throws Exception{
        URI endpoint=new URI(prefs.getString("bridgeEndpoint",""));if(!"https".equals(endpoint.getScheme())||!privateHost(endpoint.getHost()))throw new IOException("Private bridge is not configured");
        String expected=prefs.getString("bridgePin","");
        SSLContext tls=SSLContext.getInstance("TLS");tls.init(null,new TrustManager[]{new X509TrustManager(){
            public X509Certificate[] getAcceptedIssuers(){return new X509Certificate[0];}public void checkClientTrusted(X509Certificate[] c,String a)throws java.security.cert.CertificateException{throw new java.security.cert.CertificateException();}
            public void checkServerTrusted(X509Certificate[] chain,String auth)throws java.security.cert.CertificateException{try{if(chain==null||chain.length==0)throw new java.security.cert.CertificateException("Missing certificate");chain[0].checkValidity();byte[] digest=MessageDigest.getInstance("SHA-256").digest(chain[0].getEncoded());StringBuilder hex=new StringBuilder();for(byte b:digest)hex.append(String.format(Locale.ROOT,"%02x",b));if(!MessageDigest.isEqual(hex.toString().getBytes(StandardCharsets.US_ASCII),expected.getBytes(StandardCharsets.US_ASCII)))throw new java.security.cert.CertificateException("Unpaired bridge");}catch(java.security.cert.CertificateException e){throw e;}catch(Exception e){throw new java.security.cert.CertificateException(e);}}
        }},new SecureRandom());
        HttpsURLConnection c=(HttpsURLConnection)new URL(endpoint.toString()+path).openConnection();c.setSSLSocketFactory(tls.getSocketFactory());c.setHostnameVerifier((host,session)->host.equals(endpoint.getHost()));c.setConnectTimeout(4000);c.setReadTimeout(path.equals("/voice/start")?45000:15000);c.setInstanceFollowRedirects(false);c.setRequestProperty("Authorization","Bearer "+prefs.getString("bridgeToken",""));
        try{if(body!=null){c.setRequestMethod("POST");c.setDoOutput(true);c.setRequestProperty("Content-Type","application/json");try(OutputStream out=c.getOutputStream()){out.write(body.toString().getBytes(StandardCharsets.UTF_8));}}if(c.getResponseCode()!=200)throw new IOException("HTTP "+c.getResponseCode());try(InputStream in=c.getInputStream()){return new JSONObject(new String(FeedRepository.readLimited(in,256*1024),StandardCharsets.UTF_8));}}finally{c.disconnect();}
    }
    void close(){cancelled=true;worker.shutdownNow();}
}
