package com.akai.livingtv;

import android.content.res.Resources;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.Handler;
import android.os.Looper;
import android.os.Process;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;

/** Local weather imagery. A dialog shares each decoded scene; no image or render polling. */
final class WeatherScenes implements AutoCloseable {
    enum Scene { UNKNOWN, SUNNY, CLOUDY, RAIN, NIGHT, FOG, STORM, SNOW }
    private final Resources resources;
    private final Handler ui=new Handler(Looper.getMainLooper());
    private final ExecutorService decode=Executors.newSingleThreadExecutor(task->{
        Thread thread=new Thread(()->{Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND);task.run();},"meadow-weather-art");
        thread.setDaemon(true);return thread;
    });
    private final EnumMap<Scene,Bitmap> images=new EnumMap<>(Scene.class);
    private final EnumMap<Scene,List<Consumer<Bitmap>>> waiting=new EnumMap<>(Scene.class);
    private boolean closed;

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

    private static int resource(Scene scene){
        switch(scene){
            case SUNNY:return R.drawable.weather_sunny;
            case CLOUDY:return R.drawable.weather_scene;
            case RAIN:return R.drawable.weather_rain;
            case NIGHT:return R.drawable.weather_night;
            case FOG:case SNOW:return R.drawable.weather_fog;
            case STORM:return R.drawable.weather_storm;
            default:return 0;
        }
    }

    void load(Scene scene,Consumer<Bitmap> ready){
        if(closed)return;
        if(scene==Scene.UNKNOWN){ready.accept(null);return;}
        // Snow uses the same mist photograph with an explicit cold/snow treatment.
        Scene image=scene==Scene.SNOW?Scene.FOG:scene;
        Bitmap cached=images.get(image);
        if(cached!=null){ready.accept(cached);return;}
        List<Consumer<Bitmap>> callbacks=waiting.get(image);
        if(callbacks!=null){callbacks.add(ready);return;}
        callbacks=new ArrayList<>();callbacks.add(ready);waiting.put(image,callbacks);
        int resource=resource(image);
        decode.execute(()->{
            BitmapFactory.Options size=new BitmapFactory.Options();size.inJustDecodeBounds=true;size.inScaled=false;
            BitmapFactory.decodeResource(resources,resource,size);
            BitmapFactory.Options options=new BitmapFactory.Options();options.inPreferredConfig=Bitmap.Config.RGB_565;options.inScaled=false;
            int sample=1;while(size.outWidth/sample>1024||size.outHeight/sample>640)sample*=2;options.inSampleSize=sample;
            Bitmap bitmap=BitmapFactory.decodeResource(resources,resource,options);
            ui.post(()->{
                if(closed){if(bitmap!=null)bitmap.recycle();return;}
                List<Consumer<Bitmap>> recipients=waiting.remove(image);
                if(bitmap!=null)images.put(image,bitmap);
                if(recipients!=null)for(Consumer<Bitmap> recipient:recipients)recipient.accept(bitmap);
            });
        });
    }

    @Override public void close(){
        closed=true;decode.shutdownNow();waiting.clear();
        for(Bitmap bitmap:images.values())bitmap.recycle();images.clear();
    }
}
