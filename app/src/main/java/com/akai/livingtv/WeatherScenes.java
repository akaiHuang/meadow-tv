package com.akai.livingtv;

import android.content.res.Resources;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.Handler;
import android.os.Looper;
import android.os.Process;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;

/** Local city photographs, shared at display size. Weather is a static treatment over the city. */
final class WeatherScenes implements AutoCloseable {
    enum Scene { UNKNOWN, SUNNY, CLOUDY, RAIN, NIGHT, FOG, STORM, SNOW }
    private static final int MAX_CACHE_ENTRIES=5;
    private final Resources resources;
    private final Handler ui=new Handler(Looper.getMainLooper());
    private final ExecutorService decode=Executors.newSingleThreadExecutor(task->{
        Thread thread=new Thread(()->{Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND);task.run();},"meadow-weather-art");
        thread.setDaemon(true);return thread;
    });
    private final LinkedHashMap<String,Bitmap> images=new LinkedHashMap<>(8,.75f,true);
    private final Map<String,List<Consumer<Bitmap>>> waiting=new HashMap<>();
    private volatile boolean closed;

    WeatherScenes(Resources resources){this.resources=resources;}

    static Scene scene(WeatherRepository.Reading reading){
        if(reading==null)return Scene.UNKNOWN;
        int code=reading.code;
        if(code==95||code==96||code==97||code==99)return Scene.STORM;
        if(code==71||code==73||code==75||code==77||code==85||code==86)return Scene.SNOW;
        if(code==45||code==48)return Scene.FOG;
        if(code==51||code==53||code==55||code==56||code==57||code==61||code==63||code==65||code==66||code==67||code==80||code==81||code==82)return Scene.RAIN;
        if(code==0||code==1)return reading.day?Scene.SUNNY:Scene.NIGHT;
        if(code==2||code==3)return Scene.CLOUDY;
        return Scene.UNKNOWN;
    }

    static int cityIndex(String city){
        for(int index=0;index<WeatherRepository.CITIES.length;index++)if(WeatherRepository.CITIES[index].equals(city))return index;
        return -1;
    }
    static int resource(String city){
        switch(cityIndex(city)){
            case 0:return R.drawable.city_taipei;
            case 1:return R.drawable.city_newtaipei;
            case 2:return R.drawable.city_taoyuan;
            case 3:return R.drawable.city_taichung;
            case 4:return R.drawable.city_tainan;
            case 5:return R.drawable.city_kaohsiung;
            case 6:return R.drawable.city_hsinchu;
            case 7:return R.drawable.city_yilan;
            case 8:return R.drawable.city_hualien;
            default:return 0;
        }
    }
    static float focalX(String city){return cityIndex(city)==1?.85f:cityIndex(city)==8?.35f:.5f;}

    void load(String city,boolean large,Consumer<Bitmap> ready){
        if(closed)return;
        int resource=resource(city);
        if(resource==0){ready.accept(null);return;}
        String key=cityIndex(city)+(large?":week":":card");
        Bitmap cached=images.get(key);
        if(cached!=null){ready.accept(cached);return;}
        List<Consumer<Bitmap>> callbacks=waiting.get(key);
        if(callbacks!=null){callbacks.add(ready);return;}
        callbacks=new ArrayList<>();callbacks.add(ready);waiting.put(key,callbacks);
        decode.execute(()->{
            if(closed)return;
            BitmapFactory.Options size=new BitmapFactory.Options();size.inJustDecodeBounds=true;size.inScaled=false;
            BitmapFactory.decodeResource(resources,resource,size);
            BitmapFactory.Options options=new BitmapFactory.Options();options.inPreferredConfig=Bitmap.Config.RGB_565;options.inScaled=false;
            int width=large?1024:512,height=large?640:320,sample=1;
            while(size.outWidth/sample>width||size.outHeight/sample>height)sample*=2;options.inSampleSize=sample;
            Bitmap bitmap=BitmapFactory.decodeResource(resources,resource,options);
            ui.post(()->{
                if(closed){if(bitmap!=null)bitmap.recycle();return;}
                List<Consumer<Bitmap>> recipients=waiting.remove(key);
                if(bitmap!=null){
                    images.put(key,bitmap);
                    while(images.size()>MAX_CACHE_ENTRIES){
                        // A visible PhotoView may still hold an evicted bitmap. Removing
                        // the cache reference is safe; recycling here would corrupt it.
                        String oldest=images.keySet().iterator().next();images.remove(oldest);
                    }
                }
                if(recipients!=null)for(Consumer<Bitmap> recipient:recipients)recipient.accept(bitmap);
            });
        });
    }

    @Override public void close(){
        closed=true;decode.shutdownNow();waiting.clear();
        // Callers clear every PhotoView first. Evicted images are reclaimed after
        // their visible owners release them, rather than recycled under a Canvas.
        for(Bitmap bitmap:images.values())bitmap.recycle();images.clear();
    }
}
