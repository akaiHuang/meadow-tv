package com.akai.livingtv;

import android.graphics.Bitmap;
import android.os.Looper;
import java.lang.reflect.Field;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.GraphicsMode;
import org.robolectric.annotation.LooperMode;
import static org.junit.Assert.*;
import static org.robolectric.Shadows.shadowOf;

@RunWith(RobolectricTestRunner.class) @Config(sdk=28)
@GraphicsMode(GraphicsMode.Mode.NATIVE) @LooperMode(LooperMode.Mode.PAUSED)
public class WeatherCityArtTest {
    private Bitmap load(WeatherScenes scenes,String city,boolean large)throws Exception{
        AtomicReference<Bitmap> result=new AtomicReference<>();scenes.load(city,large,result::set);
        long until=System.currentTimeMillis()+5000;
        while(result.get()==null&&System.currentTimeMillis()<until){Thread.sleep(15);shadowOf(Looper.getMainLooper()).idle();}
        assertNotNull("Bundled city photo must decode",result.get());return result.get();
    }
    @SuppressWarnings("unchecked") private Map<String,Bitmap> cache(WeatherScenes scenes)throws Exception{
        Field field=WeatherScenes.class.getDeclaredField("images");field.setAccessible(true);return (Map<String,Bitmap>)field.get(scenes);
    }
    @Test public void cityCacheIsBoundedWhileVisibleOwnersCanSafelyRetainAnEvictedPhoto()throws Exception{
        WeatherScenes scenes=new WeatherScenes(RuntimeEnvironment.getApplication().getResources());
        Bitmap first=load(scenes,"台北",false);assertSame(first,load(scenes,"台北",false));
        assertTrue(first.getWidth()<=512);assertTrue(first.getHeight()<=320);assertTrue(first.getByteCount()<=512*320*2);
        Bitmap last=first;
        for(String city:WeatherRepository.CITIES){last=load(scenes,city,false);assertTrue(cache(scenes).size()<=5);}
        assertEquals(5,cache(scenes).size());assertFalse("Cache eviction cannot recycle a bitmap still owned by a visible card",first.isRecycled());
        scenes.close();assertTrue(cache(scenes).isEmpty());assertTrue("Closing releases cached images once all views are gone",last.isRecycled());first.recycle();
    }
    @Test public void weeklyAndCardSizesRemainBoundedAndLateCloseNeverDeliversToAView()throws Exception{
        WeatherScenes scenes=new WeatherScenes(RuntimeEnvironment.getApplication().getResources());
        Bitmap week=load(scenes,"宜蘭",true),card=load(scenes,"宜蘭",false);assertNotSame(week,card);
        assertTrue(week.getWidth()<=1024);assertTrue(week.getHeight()<=640);assertTrue(week.getByteCount()<=1024*640*2);assertTrue(card.getWidth()<=512);
        AtomicReference<Bitmap> late=new AtomicReference<>();scenes.load("高雄",false,late::set);scenes.close();
        for(int i=0;i<5;i++){Thread.sleep(20);shadowOf(Looper.getMainLooper()).idle();}
        assertNull("A dismissed weather panel must never receive a late decoded photo",late.get());assertTrue(week.isRecycled());assertTrue(card.isRecycled());
    }
}
