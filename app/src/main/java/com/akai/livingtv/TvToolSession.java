package com.akai.livingtv;

import android.content.*;
import android.os.*;
import java.util.UUID;
import java.util.function.Consumer;
import org.json.*;

/** One explicit, bounded tool run. UI callbacks may detach while its receipt channel survives. */
final class TvToolSession {
    static final long TIMEOUT_MS=120000;
    private static final Handler MAIN=new Handler(Looper.getMainLooper());
    private static TvToolSession current;
    final String id=UUID.randomUUID().toString();
    private final Context context;
    private final String text;
    private final JSONArray history,catalog;
    private AssistantClient client;
    private Consumer<String> status;
    private Consumer<AssistantClient.Reply> done;
    private volatile boolean active=true;
    private boolean begun;
    private final Runnable timeout=()->finish(new AssistantClient.Reply("電視任務超過兩分鐘，已停止操作與連線。",false));

    private TvToolSession(Context context,String text,JSONArray history,JSONArray catalog,Consumer<String> status,Consumer<AssistantClient.Reply> done){this.context=context.getApplicationContext();this.text=text;this.history=copy(history);this.catalog=copy(catalog);this.status=status;this.done=done;}
    static TvToolSession start(Context context,String text,JSONArray history,JSONArray catalog,Consumer<String> status,Consumer<AssistantClient.Reply> done){
        TvToolSession session=new TvToolSession(context,text,history,catalog,status,done);MAIN.post(()->{
            if(!session.active)return;if(current!=null)current.cancel();current=session;
            session.client=new AssistantClient(session.context);if(!session.client.configured()){session.finish(new AssistantClient.Reply("尚未與 Mac 配對，沒有開始電視任務。",false));return;}
            MAIN.postDelayed(session.timeout,TIMEOUT_MS);
            try{Intent service=new Intent(session.context,TvTaskService.class).setAction(TvTaskService.START).putExtra("sessionId",session.id);if(Build.VERSION.SDK_INT>=26)session.context.startForegroundService(service);else session.context.startService(service);}
            catch(RuntimeException error){session.finish(new AssistantClient.Reply("Android 暫時不允許啟動電視任務，沒有繼續背景操作。",false));}
        });return session;
    }
    static TvToolSession find(String id){return current!=null&&current.active&&current.id.equals(id)?current:null;}
    static void serviceStopped(String id){TvToolSession session=find(id);if(session!=null)session.finish(new AssistantClient.Reply("電視任務服務已停止，操作已取消。",false));}
    void begin(){
        if(!active||begun)return;begun=true;
        if(status!=null)status.accept("正在讀取電視畫面與可用工具…");
        client.ask(text,history,catalog,(command,receipt)->{if(active)TvCommandDispatcher.execute(context,command,receipt);else receipt.accept(TvUiSnapshot.jsonObject("ok",false,"dispatched",false,"verified",false,"errorCode","cancelled"));},message->{if(active&&status!=null)status.accept(message);},this::finish);
    }
    void cancel(){if(Looper.myLooper()!=Looper.getMainLooper()){MAIN.post(this::cancel);return;}finish(new AssistantClient.Reply("電視任務已取消。",false));}
    void detach(){if(Looper.myLooper()!=Looper.getMainLooper()){MAIN.post(this::detach);return;}status=null;done=null;}
    boolean isActive(){return active;}
    private void finish(AssistantClient.Reply reply){
        if(Looper.myLooper()!=Looper.getMainLooper()){MAIN.post(()->finish(reply));return;}if(!active)return;active=false;MAIN.removeCallbacks(timeout);
        String summary=reply.text==null?"":reply.text;if(summary.length()>1500)summary=summary.substring(0,1500);context.getSharedPreferences("living",0).edit().putString("latestTaskResult",TvUiSnapshot.jsonObject("text",summary,"ok",reply.ok,"finishedAt",System.currentTimeMillis()).toString()).apply();
        if(client!=null){client.close();client=null;}Consumer<AssistantClient.Reply> callback=done;done=null;status=null;if(current==this)current=null;TvTaskService.finish(id);if(callback!=null)callback.accept(reply);
    }
    private static JSONArray copy(JSONArray source){if(source==null)return new JSONArray();try{return new JSONArray(source.toString());}catch(JSONException error){return new JSONArray();}}
}
