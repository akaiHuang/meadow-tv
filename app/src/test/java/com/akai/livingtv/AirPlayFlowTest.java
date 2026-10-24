package com.akai.livingtv;

import android.content.Intent;
import android.graphics.*;
import android.os.Looper;
import android.view.*;
import android.widget.TextView;
import java.io.*;
import java.time.Duration;
import org.junit.*;
import org.junit.runner.RunWith;
import org.robolectric.*;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.*;
import static org.junit.Assert.*;
import static org.robolectric.Shadows.shadowOf;

@RunWith(RobolectricTestRunner.class) @Config(sdk=28,qualifiers="w1280dp-h720dp-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
public class AirPlayFlowTest {
    @Test public void waitingGuideDoesNotInventReceiverReadinessAndReturnsCleanly()throws Exception{
        // Even an old shortcut must never reopen the retired receiver screen.
        ActivityController<AirPlayActivity> controller=Robolectric.buildActivity(AirPlayActivity.class,new Intent().putExtra("openSettings",true)).setup();
        try{
            AirPlayActivity activity=controller.get();View root=activity.getWindow().getDecorView();root.measure(View.MeasureSpec.makeMeasureSpec(1920,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(1080,View.MeasureSpec.EXACTLY));root.layout(0,0,1920,1080);
            assertNotNull(find(root,"把螢幕，\n帶到這裡。"));assertNotNull(find(root,"Xiaomi-AirPlay"));assertNull(find(root,"●  接收器已偵測到"));
            assertNull(find(root,"M E A D O W"));assertNull(find(root,"接收設定"));assertNull(shadowOf(activity).getNextStartedActivity());
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(9));assertNotNull(find(root,"接收狀態尚未確認"));
            find(root,"重新偵測").performClick();assertNull(shadowOf(activity).getNextStartedActivity());
            Bitmap image=Bitmap.createBitmap(1920,1080,Bitmap.Config.ARGB_8888);root.draw(new Canvas(image));try(FileOutputStream out=new FileOutputStream("../artifacts/native-airplay-simulation.png")){image.compress(Bitmap.CompressFormat.PNG,100,out);}
            find(root,"返回主畫面").performClick();assertTrue(activity.isFinishing());Intent home=shadowOf(activity).getNextStartedActivity();assertEquals(MainActivity.class.getName(),home.getComponent().getClassName());assertTrue(home.getBooleanExtra("fromShortcut",false));
        }finally{controller.pause().stop().destroy();}
    }
    @Test public void ribbonCycleHasNoPhaseJumpInItsRenderedSineWaves(){
        assertEquals(RibbonView.phaseAt(0),RibbonView.phaseAt(24000),0);
        assertEquals(RibbonView.phaseAt(1234),RibbonView.phaseAt(25234),0);
        assertEquals(Math.sin(RibbonView.phaseAt(23999)),Math.sin(RibbonView.phaseAt(24001)),.001);
        assertEquals(50,RibbonView.FRAME_MS);
    }
    @Test public void controlCenterCastOpensModernGuideDirectly()throws Exception{
        ActivityController<MainActivity> controller=Robolectric.buildActivity(MainActivity.class).setup();
        try{MainActivity activity=controller.get();java.lang.reflect.Method cast=MainActivity.class.getDeclaredMethod("showCast");cast.setAccessible(true);cast.invoke(activity);Intent intent=shadowOf(activity).getNextStartedActivity();assertNotNull(intent);assertEquals(AirPlayActivity.class.getName(),intent.getComponent().getClassName());assertFalse(intent.hasExtra("openSettings"));}
        finally{controller.pause().stop().destroy();}
    }
    private TextView find(View v,String text){if(v instanceof TextView&&((TextView)v).getText().toString().equals(text))return (TextView)v;if(v instanceof ViewGroup){ViewGroup g=(ViewGroup)v;for(int i=0;i<g.getChildCount();i++){TextView found=find(g.getChildAt(i),text);if(found!=null)return found;}}return null;}
}
