package com.akai.livingtv;

import android.app.SearchManager;
import android.content.*;
import android.media.AudioManager;
import android.net.Uri;
import android.os.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Consumer;
import org.json.*;
import static com.akai.livingtv.TvUiSnapshot.jsonObject;

/** Application-scoped typed tool boundary; activity visibility never establishes success. */
final class TvCommandDispatcher {
    private static final Handler MAIN=new Handler(Looper.getMainLooper());
    private static final Map<String,String> APP_PACKAGES;
    static {
        Map<String,String> apps=new HashMap<>();apps.put("youtube","com.google.android.youtube.tv");apps.put("netflix","com.netflix.ninja");apps.put("hbo","com.wbd.stream");apps.put("apple","com.apple.atve.androidtv.appletv");apps.put("pts","com.pts.ptsplus");apps.put("anime","tw.com.gamer.android.animad");APP_PACKAGES=Collections.unmodifiableMap(apps);
    }
    private TvCommandDispatcher(){}
    /** Safe to call from the client worker; the actual tree capture always runs on main. */
    static JSONObject state(Context context){
        if(Looper.myLooper()==Looper.getMainLooper())return RemoteService.readSnapshot(context).data;
        FutureTask<JSONObject> task=new FutureTask<>(()->RemoteService.readSnapshot(context).data);MAIN.post(task);
        try{return task.get(2,TimeUnit.SECONDS);}catch(Exception error){task.cancel(false);return jsonObject("source","accessibility","unavailable",true,"errorCode","state_timeout","permission",jsonObject("accessibilityConnected",RemoteService.connected()!=null),"targets",new JSONArray(),"capabilities",jsonObject("readTree",false,"nodeActions",false,"shell",false,"coordinateGestures",false));}
    }
    static void execute(Context context,JSONObject command,Consumer<JSONObject> done){
        Context app=context.getApplicationContext();if(Looper.myLooper()!=Looper.getMainLooper()){MAIN.post(()->execute(app,command,done));return;}
        String action=command.optString("action");
        if(action.equals("android")){TvUiExecutor.execute(app,command,done);return;}
        if(action.equals("queue")){
            QueueController.execute(app,command,result->{String op=command.optString("operation");boolean mutation=!Arrays.asList("search","search_video","list","search_shows","list_shows","episodes").contains(op);boolean persisted=Arrays.asList("follow_show","unfollow_show").contains(op)&&result.optBoolean("persisted");boolean ok=result.optBoolean("ok");try{result.put("dispatched",mutation&&ok);result.put("verified",ok&&(!mutation||persisted));result.put("verificationScope",persisted?"saved_library":mutation?"queue_receipt":"observed_queue");}catch(JSONException ignored){}done.accept(result);});return;
        }
        if(!legacyArguments(command,action)){done.accept(error("invalid_arguments","不支援這組電視工具參數。"));return;}
        TvUiSnapshot before=RemoteService.readSnapshot(app);
        try{
            if(action.equals("status")){done.accept(status(app,before));return;}
            if(action.equals("volume")){
                Object raw=command.opt("percent");if(!(raw instanceof Number)||((Number)raw).doubleValue()!=((Number)raw).intValue()||((Number)raw).intValue()<0||((Number)raw).intValue()>100){done.accept(error("invalid_volume","音量需為 0–100 整數。"));return;}
                AudioManager audio=(AudioManager)app.getSystemService(Context.AUDIO_SERVICE);int max=audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC),wanted=Math.round(max*((Number)raw).intValue()/100f);audio.setStreamVolume(AudioManager.STREAM_MUSIC,wanted,0);int actual=audio.getStreamVolume(AudioManager.STREAM_MUSIC);
                done.accept(jsonObject("ok",true,"dispatched",true,"verified",actual==wanted,"verificationScope","actual_volume","volume",actual,"maximum",max,"message","音量實際值 "+actual+" / "+max,"before",before.data,"after",RemoteService.readSnapshot(app).data));return;
            }
            if(action.equals("open_app")){
                String pkg=APP_PACKAGES.get(command.optString("app"));Intent intent=pkg==null?null:TvUiExecutor.launchIntent(app,pkg);if(intent==null){done.accept(error("app_unavailable","App 未安裝、不可開啟或不在已允許的清單。"));return;}
                app.startActivity(intent);observe(app,before,pkg,"已送出開啟 App 指令；未驗證播放。",done);return;
            }
            if(action.equals("search")){
                String query=command.optString("query");if(!command.optString("app").equals("youtube")||!TvUiExecutor.validText(query)||query.codePointCount(0,query.length())>180){done.accept(error("unsupported_search","目前僅支援 YouTube 的有界搜尋文字。"));return;}
                String pkg=APP_PACKAGES.get("youtube");Intent entry=TvUiExecutor.launchIntent(app,pkg);if(entry==null||entry.getComponent()==null){done.accept(error("app_unavailable","YouTube 尚未安裝或無法開啟。"));return;}
                Intent search=new Intent(Intent.ACTION_SEARCH).setComponent(entry.getComponent()).setPackage(pkg).putExtra(SearchManager.QUERY,query).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                if(!launch(app,search)){Intent web=new Intent(Intent.ACTION_VIEW,Uri.parse("https://www.youtube.com/results?search_query="+Uri.encode(query))).setPackage(pkg).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);if(!launch(app,web)){done.accept(error("dispatch_failed","YouTube 沒有接受搜尋指令。"));return;}}
                observe(app,before,"","已送出搜尋指令；未驗證搜尋結果或播放。",done);return;
            }
            if(action.equals("inputs")){
                app.startActivity(new Intent(app,MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK|Intent.FLAG_ACTIVITY_CLEAR_TOP).putExtra("fromShortcut",true).putExtra("action","inputs"));observe(app,before,"","已送出輸入來源選單指令。",done);return;
            }
            if(action.equals("hdmi")){
                if(!InputController.openConnectedHdmi(app)){done.accept(error("input_unavailable","找不到可用 HDMI。"));return;}
                observe(app,before,"","已送出 HDMI 切換指令；未驗證影像。",done);return;
            }
            done.accept(error("unsupported","電視不支援此工具操作。"));
        }catch(RuntimeException error){done.accept(error("dispatch_failed","Android 沒有接受這次操作。"));}
    }
    private static JSONObject status(Context context,TvUiSnapshot snapshot){
        AudioManager audio=(AudioManager)context.getSystemService(Context.AUDIO_SERVICE);int actual=audio.getStreamVolume(AudioManager.STREAM_MUSIC),max=audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC);String weather="尚未讀取";
        SharedPreferences prefs=context.getSharedPreferences("living",0);int city=Math.max(0,Math.min(WeatherRepository.CITIES.length-1,prefs.getInt("weatherCity",0)));String cached=prefs.getString("weather"+city,"");if(!cached.isEmpty())try{JSONObject reading=new JSONObject(cached);double temperature=reading.optDouble("temperature_2m",Double.NaN);String shown=Double.isNaN(temperature)||Double.isInfinite(temperature)?"—（尚未提供）":Math.round(temperature)+"°";weather=WeatherRepository.CITIES[city]+" "+shown+" "+WeatherRepository.description(reading.optInt("weather_code",-1));}catch(JSONException ignored){}
        return jsonObject("ok",true,"dispatched",false,"verified",true,"verificationScope","observed_status","volume",actual,"maximum",max,"weather",weather,"weatherObservedAt",prefs.getLong("weather"+city+"At",0),"state",snapshot.data,"message","音量實際值 "+actual+" / "+max+"；天氣："+weather);
    }
    private static boolean legacyArguments(JSONObject value,String action){
        Set<String> keys=new HashSet<>(Arrays.asList("id","action"));if(action.equals("open_app"))keys.add("app");if(action.equals("search"))keys.addAll(Arrays.asList("app","query"));if(action.equals("volume"))keys.add("percent");for(Iterator<String> names=value.keys();names.hasNext();)if(!keys.contains(names.next()))return false;
        if(!Arrays.asList("open_app","search","volume","inputs","hdmi","status").contains(action))return false;
        if(action.equals("search")&&(!(value.opt("app") instanceof String)||!(value.opt("query") instanceof String)))return false;
        return !action.equals("open_app")||(value.opt("app") instanceof String&&APP_PACKAGES.containsKey(value.optString("app")));
    }
    private static boolean launch(Context context,Intent intent){try{if(intent.resolveActivity(context.getPackageManager())!=null){context.startActivity(intent);return true;}}catch(RuntimeException ignored){}return false;}
    private static void observe(Context context,TvUiSnapshot before,String expectedPackage,String message,Consumer<JSONObject> done){MAIN.postDelayed(()->{TvUiSnapshot after=RemoteService.readSnapshot(context);boolean verified=!expectedPackage.isEmpty()&&expectedPackage.equals(after.packageName);done.accept(jsonObject("ok",true,"dispatched",true,"verified",verified,"verificationScope",expectedPackage.isEmpty()?"dispatch_only":"foreground_app","effectObserved",!before.sameScreen(after),"message",message,"before",before.data,"after",after.data));},450);}
    private static JSONObject error(String code,String message){return jsonObject("ok",false,"dispatched",false,"verified",false,"errorCode",code,"message",message);}
}
