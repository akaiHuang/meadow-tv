package com.akai.livingtv;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.function.Consumer;
import java.util.function.LongSupplier;

/** Explicit city selection, no location permission. A complete forecast is cached for 30 minutes. */
final class WeatherRepository implements AutoCloseable {
    static final String[] CITIES={"台北","新北","桃園","台中","台南","高雄","新竹","宜蘭","花蓮"};
    private static final double[][] POINTS={{25.033,121.5654},{25.012,121.465},{24.993,121.301},{24.1477,120.6736},{22.9997,120.227},{22.6273,120.3014},{24.8138,120.9675},{24.757,121.753},{23.987,121.602}};
    private static final long CACHE_MILLIS=30*60*1000L;
    private static final ZoneId TAIPEI=ZoneId.of("Asia/Taipei");

    static final class Day {
        final String date;
        final int code;
        final Double max,min;
        Day(String date,int code,Double max,Double min){this.date=date;this.code=code;this.max=max;this.min=min;}
    }
    static final class Reading {
        final String city,text;
        final int code;
        final boolean day;
        final List<Day> days;
        final long updatedAtMillis;
        final boolean stale;
        Reading(String city,String text,int code,boolean day){this(city,text,code,day,Collections.emptyList(),0,false);}
        Reading(String city,String text,int code,boolean day,List<Day> days,long updatedAtMillis,boolean stale){
            this.city=city;this.text=text;this.code=code;this.day=day;
            this.days=Collections.unmodifiableList(new ArrayList<>(days));
            this.updatedAtMillis=updatedAtMillis;this.stale=stale;
        }
        private Reading asStale(){return new Reading(city,text,code,day,days,updatedAtMillis,true);}
    }
    interface Fetcher { JSONObject fetch(int city)throws Exception; }
    private static final class Request {
        final Reading cached;
        final List<Consumer<Reading>> callbacks=new ArrayList<>();
        Request(Reading cached){this.cached=cached;}
    }

    private final SharedPreferences prefs;
    private final ExecutorService work;
    private final Fetcher fetcher;
    private final LongSupplier clock;
    private final Handler ui=new Handler(Looper.getMainLooper());
    private final Object gate=new Object();
    private final Map<Integer,Request> inFlight=new HashMap<>();
    private boolean closed;

    WeatherRepository(Context context){this(context,WeatherRepository::fetchForecast,Executors.newSingleThreadExecutor(),System::currentTimeMillis);}
    WeatherRepository(Context context,Fetcher fetcher,ExecutorService work,LongSupplier clock){
        prefs=context.getSharedPreferences("living",0);this.fetcher=fetcher;this.work=work;this.clock=clock;
    }
    void load(Consumer<Reading> done){
        int city=Math.max(0,Math.min(CITIES.length-1,prefs.getInt("weatherCity",0)));
        load(city,done,false);
    }
    void load(int city,Consumer<Reading> done){
        load(city,done,true);
    }
    private void load(int city,Consumer<Reading> done,boolean requireForecast){
        if(city<0||city>=CITIES.length)throw new IllegalArgumentException("Unknown weather city");
        synchronized(gate){
            if(closed)return;
            Reading cached=readCache(city,clock.getAsLong(),requireForecast);
            if(cached!=null)deliver(done,cached);
            if(cached!=null&&!cached.stale)return;
            Request existing=inFlight.get(city);
            if(existing!=null){existing.callbacks.add(done);return;}
            Request request=new Request(cached);request.callbacks.add(done);inFlight.put(city,request);
            try{work.execute(()->refresh(city,request));}
            catch(RejectedExecutionException ignored){finish(city,request,null);}
        }
    }
    private void refresh(int city,Request request){
        JSONObject payload=null;Reading reading=null;
        try{
            synchronized(gate){if(closed||inFlight.get(city)!=request)return;}
            payload=fetcher.fetch(city);
            reading=parseForecast(CITIES[city],payload,clock.getAsLong(),false);
            // A malformed or partial response must not replace a usable cached week.
            if(reading.days.size()!=7)throw new IOException("Incomplete daily forecast");
        }catch(Exception ignored){payload=null;reading=null;}
        synchronized(gate){
            if(closed||inFlight.get(city)!=request)return;
            if(reading!=null){
                String fullKey=forecastKey(city),legacyKey="weather"+city;
                SharedPreferences.Editor editor=prefs.edit().putString(fullKey,payload.toString()).putLong(fullKey+"At",reading.updatedAtMillis);
                JSONObject current=payload.optJSONObject("current");
                if(current!=null)editor.putString(legacyKey,current.toString()).putLong(legacyKey+"At",reading.updatedAtMillis);
                editor.apply();
            }
            finish(city,request,reading);
        }
    }
    private void finish(int city,Request request,Reading reading){
        synchronized(gate){
            if(closed||inFlight.get(city)!=request)return;
            inFlight.remove(city);
            Reading result=reading!=null?reading:request.cached!=null?request.cached.asStale():unavailable(CITIES[city]);
            for(Consumer<Reading> callback:request.callbacks)deliver(callback,result);
            request.callbacks.clear();
        }
    }
    private void deliver(Consumer<Reading> callback,Reading reading){
        ui.post(()->{synchronized(gate){if(!closed)callback.accept(reading);}});
    }
    private Reading readCache(int city,long now,boolean requireForecast){
        String fullKey=forecastKey(city),saved=prefs.getString(fullKey,"");
        if(!saved.isEmpty())try{
            long at=prefs.getLong(fullKey+"At",0);
            Reading cached=parseForecast(CITIES[city],new JSONObject(saved),at,false);
            boolean today=cached.days.size()==7&&cached.days.get(0).date.equals(java.time.Instant.ofEpochMilli(now).atZone(TAIPEI).toLocalDate().toString());
            boolean fresh=at>0&&now>=at&&now-at<CACHE_MILLIS&&cached.code>=0&&(!requireForecast||today);
            if(cached.code>=0||!cached.days.isEmpty())return fresh?cached:cached.asStale();
        }catch(Exception ignored){}
        // Homepage callers may reuse current-only cache; the weather panel still fetches its week.
        String legacyKey="weather"+city;saved=prefs.getString(legacyKey,"");
        if(!saved.isEmpty())try{
            long at=prefs.getLong(legacyKey+"At",0);
            boolean fresh=!requireForecast&&at>0&&now>=at&&now-at<CACHE_MILLIS;
            Reading cached=currentReading(CITIES[city],new JSONObject(saved),Collections.emptyList(),at,!fresh);
            if(cached.code>=0)return cached;
        }catch(Exception ignored){}
        return null;
    }

    static String forecastKey(int city){return "weatherForecast"+city;}
    static String forecastUrl(int city){
        double[] point=POINTS[city];
        return "https://api.open-meteo.com/v1/forecast?latitude="+point[0]+"&longitude="+point[1]
                +"&current=temperature_2m,weather_code,is_day&daily=weather_code,temperature_2m_max,temperature_2m_min&timezone=Asia%2FTaipei&forecast_days=7";
    }
    private static JSONObject fetchForecast(int city)throws Exception {
        HttpURLConnection connection=(HttpURLConnection)new URL(forecastUrl(city)).openConnection();
        connection.setConnectTimeout(5000);connection.setReadTimeout(6000);
        try{
            if(connection.getResponseCode()!=HttpURLConnection.HTTP_OK)throw new IOException("Weather request failed");
            try(InputStream in=connection.getInputStream()){
                return new JSONObject(new String(FeedRepository.readLimited(in,64000),StandardCharsets.UTF_8));
            }
        }finally{connection.disconnect();}
    }
    static Reading parseForecast(String city,JSONObject payload,long updatedAtMillis,boolean stale)throws JSONException {
        if(payload==null||payload.optBoolean("error",false))throw new JSONException("Weather response unavailable");
        return currentReading(city,payload.optJSONObject("current"),parseDays(payload.optJSONObject("daily")),updatedAtMillis,stale);
    }
    static List<Day> parseDays(JSONObject daily){
        if(daily==null)return Collections.emptyList();
        JSONArray dates=daily.optJSONArray("time"),codes=daily.optJSONArray("weather_code"),maxima=daily.optJSONArray("temperature_2m_max"),minima=daily.optJSONArray("temperature_2m_min");
        if(dates==null||codes==null||maxima==null||minima==null)return Collections.emptyList();
        int count=dates.length();
        if(count==0||count>7||codes.length()!=count||maxima.length()!=count||minima.length()!=count)return Collections.emptyList();
        List<Day> days=new ArrayList<>();LocalDate previous=null;
        for(int index=0;index<count;index++){
            Object raw=dates.opt(index);if(!(raw instanceof String))return Collections.emptyList();
            String date=(String)raw;if(!date.matches("[0-9]{4}-[0-9]{2}-[0-9]{2}"))return Collections.emptyList();LocalDate parsed;
            try{parsed=LocalDate.parse(date);if(!parsed.toString().equals(date)||(previous!=null&&!parsed.equals(previous.plusDays(1))))return Collections.emptyList();}
            catch(Exception ignored){return Collections.emptyList();}
            Double max=finite(maxima.opt(index)),min=finite(minima.opt(index));
            if(max!=null&&min!=null&&max<min){max=null;min=null;}
            days.add(new Day(date,weatherCode(codes.opt(index)),max,min));previous=parsed;
        }
        return Collections.unmodifiableList(days);
    }
    private static Reading currentReading(String city,JSONObject current,List<Day> days,long updatedAtMillis,boolean stale){
        Double temperature=current==null?null:finite(current.opt("temperature_2m"));
        int code=current==null?-1:weatherCode(current.opt("weather_code"));
        boolean day=current==null||current.optInt("is_day",1)==1;
        if(temperature==null||code<0)return new Reading(city,"—",-1,day,days,updatedAtMillis,stale);
        return new Reading(city,Math.round(temperature)+"°  "+description(code),code,day,days,updatedAtMillis,stale);
    }
    private static Reading unavailable(String city){return new Reading(city,"—",-1,true,Collections.emptyList(),0,true);}
    private static Double finite(Object value){
        if(!(value instanceof Number))return null;
        double number=((Number)value).doubleValue();return Double.isFinite(number)?number:null;
    }
    private static int weatherCode(Object value){
        Double number=finite(value);if(number==null||number!=Math.rint(number))return -1;
        int code=number.intValue();
        switch(code){case 0:case 1:case 2:case 3:case 45:case 48:case 51:case 53:case 55:case 56:case 57:case 61:case 63:case 65:case 66:case 67:case 71:case 73:case 75:case 77:case 80:case 81:case 82:case 85:case 86:case 95:case 96:case 97:case 99:return code;default:return -1;}
    }
    static String description(int code){
        switch(code){
            case 0:return "晴朗";case 1:case 2:return "晴時多雲";case 3:return "多雲";
            case 45:case 48:return "有霧";
            case 51:case 53:case 55:case 56:case 57:case 61:case 63:case 65:case 66:case 67:return "有雨";
            case 71:case 73:case 75:case 77:return "降雪";
            case 80:case 81:case 82:return "陣雨";case 85:case 86:return "陣雪";
            case 95:case 96:case 97:case 99:return "雷雨";default:return "暫不可用";
        }
    }
    @Override public void close(){
        synchronized(gate){if(closed)return;closed=true;inFlight.clear();ui.removeCallbacksAndMessages(null);}
        work.shutdownNow();
    }
}
