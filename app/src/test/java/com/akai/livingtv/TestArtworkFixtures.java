package com.akai.livingtv;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Set;
import org.json.JSONArray;
import org.json.JSONObject;
import org.json.JSONTokener;

/** Test-only neutral pixels, never copied into production resources or artwork assets. */
final class TestArtworkFixtures {
    static final String IMAGE_URL="https://static.tvmaze.com/uploads/images/original_untouched/meadow-neutral-test.png";
    static final int WIDTH=1280,HEIGHT=720;
    private TestArtworkFixtures(){}

    static String installImage(Context context)throws Exception{
        write(context,IMAGE_URL,neutralPng(WIDTH,HEIGHT));return IMAGE_URL;
    }

    /** Prime every metadata image URL so real repository decoding never needs a CDN. */
    static void installCatalog(Context context)throws Exception{
        Set<String> urls=new LinkedHashSet<>();
        for(String asset:new String[]{"official-catalog.json","feed-seed.json","ambient-seed.json","podcast-seed.json"}){
            try(InputStream in=context.getAssets().open(asset)){
                collect(new JSONTokener(new String(in.readAllBytes(),StandardCharsets.UTF_8)).nextValue(),urls);
            }
        }
        byte[] pixels=neutralPng(WIDTH,HEIGHT);
        for(String url:urls)write(context,url,pixels);
    }

    private static void collect(Object value,Set<String> urls)throws Exception{
        if(value instanceof JSONObject){JSONObject object=(JSONObject)value;Iterator<String> keys=object.keys();while(keys.hasNext())collect(object.get(keys.next()),urls);}
        else if(value instanceof JSONArray){JSONArray array=(JSONArray)value;for(int index=0;index<array.length();index++)collect(array.get(index),urls);}
        else if(value instanceof String&&ContentPolicy.artworkLink((String)value))urls.add((String)value);
    }

    private static byte[] neutralPng(int width,int height)throws Exception{
        Bitmap bitmap=Bitmap.createBitmap(width,height,Bitmap.Config.ARGB_8888);
        try{
            Canvas canvas=new Canvas(bitmap);canvas.drawColor(Color.rgb(35,47,52));Paint paint=new Paint();
            paint.setColor(Color.rgb(68,86,91));canvas.drawRect(width/4f,0,width/2f,height,paint);
            paint.setColor(Color.rgb(99,112,109));canvas.drawRect(width/2f,height/2f,width,height,paint);
            ByteArrayOutputStream bytes=new ByteArrayOutputStream();
            if(!bitmap.compress(Bitmap.CompressFormat.PNG,100,bytes))throw new IllegalStateException("Cannot encode neutral test image");
            return bytes.toByteArray();
        }finally{bitmap.recycle();}
    }

    private static void write(Context context,String url,byte[] bytes)throws Exception{
        File directory=new File(context.getCacheDir(),"art");if(!directory.isDirectory()&&!directory.mkdirs())throw new IllegalStateException("Cannot create test artwork cache");
        byte[] digest=MessageDigest.getInstance("SHA-256").digest(url.getBytes(StandardCharsets.UTF_8));StringBuilder name=new StringBuilder();for(byte part:digest)name.append(String.format(Locale.ROOT,"%02x",part));
        try(FileOutputStream out=new FileOutputStream(new File(directory,name.toString()))){out.write(bytes);}
    }
}
