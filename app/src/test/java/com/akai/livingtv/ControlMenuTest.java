package com.akai.livingtv;

import android.app.*;
import android.content.SharedPreferences;
import android.media.AudioManager;
import android.os.Looper;
import android.view.*;
import android.widget.*;
import java.lang.reflect.*;
import java.time.Duration;
import java.util.*;
import org.junit.*;
import org.junit.runner.RunWith;
import org.robolectric.*;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.*;
import static org.junit.Assert.*;
import static org.robolectric.Shadows.shadowOf;

/** Native menu structure, safe D-pad paths and nested modal ownership; no external actions. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk=28,qualifiers="w960dp-h540dp-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@LooperMode(LooperMode.Mode.PAUSED)
public class ControlMenuTest {
    private ActivityController<MainActivity> controller;
    private MainActivity activity;
    private SharedPreferences prefs;

    @Before public void setup()throws Exception{
        prefs=RuntimeEnvironment.getApplication().getSharedPreferences("living",0);prefs.edit().clear().putString("profile","家庭").putBoolean("artwork",true).putBoolean("reduceMotion",true).putBoolean("hdmiPreview",false).putLong("weather0At",System.currentTimeMillis()).putString("weather0","{\"temperature_2m\":24,\"weather_code\":2,\"is_day\":1}").commit();
        controller=Robolectric.buildActivity(MainActivity.class).setup();activity=controller.get();layout(activity.getWindow().getDecorView(),1920,1080);settle();
    }
    @After public void close(){if(controller!=null)controller.pause().stop().destroy();}
    private Object field(String name)throws Exception{Field f=MainActivity.class.getDeclaredField(name);f.setAccessible(true);return f.get(activity);}
    private int openCount()throws Exception{return (Integer)field("openDialogs");}
    private AlertDialog open(String method)throws Exception{Method m=MainActivity.class.getDeclaredMethod(method);m.setAccessible(true);m.invoke(activity);settle();AlertDialog d=(AlertDialog)org.robolectric.shadows.ShadowDialog.getLatestDialog();assertNotNull(d);assertTrue(d.isShowing());layout(d);return d;}
    private void settle()throws Exception{for(int i=0;i<3;i++){Thread.sleep(35);shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(180));}layout(activity.getWindow().getDecorView(),1920,1080);}
    private void layout(AlertDialog dialog){WindowManager.LayoutParams bounds=dialog.getWindow().getAttributes();layout(dialog.getWindow().getDecorView(),bounds.width,bounds.height);}
    private void layout(View view,int width,int height){assertTrue("Menu width must be explicit",width>0);assertTrue("Menu height must be explicit",height>0);view.measure(View.MeasureSpec.makeMeasureSpec(width,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(height,View.MeasureSpec.EXACTLY));view.layout(0,0,width,height);}
    private View option(AlertDialog dialog,String key){View row=dialog.getWindow().getDecorView().findViewWithTag("controlMenu:"+key);assertNotNull("Missing menu row "+key,row);return row;}
    private View closeAction(AlertDialog dialog){View close=dialog.getWindow().getDecorView().findViewWithTag("controlMenuClose");assertNotNull(close);return close;}
    private List<View> enabledOptions(View parent){List<View> rows=new ArrayList<>();collectOptions(parent,rows);return rows;}
    private void collectOptions(View view,List<View> rows){if(view.getTag() instanceof String&&((String)view.getTag()).startsWith("controlMenu:")&&view.isEnabled()&&view.isFocusable()&&view.getVisibility()==View.VISIBLE)rows.add(view);if(view instanceof ViewGroup){ViewGroup group=(ViewGroup)view;for(int i=0;i<group.getChildCount();i++)collectOptions(group.getChildAt(i),rows);}}
    private void assertNoList(View view){assertFalse("Control subpages must not retain a default Android ListView",view instanceof ListView);if(view instanceof ViewGroup){ViewGroup parent=(ViewGroup)view;for(int i=0;i<parent.getChildCount();i++)assertNoList(parent.getChildAt(i));}}
    private void assertSharedShell(AlertDialog dialog){
        View decor=dialog.getWindow().getDecorView(),root=decor.findViewWithTag("controlMenuRoot");assertNotNull(root);assertNoList(root);assertNull(dialog.getListView());
        for(int which:new int[]{AlertDialog.BUTTON_POSITIVE,AlertDialog.BUTTON_NEGATIVE,AlertDialog.BUTTON_NEUTRAL}){Button defaultButton=dialog.getButton(which);assertTrue("Only the shared X and capsule actions should be visible",defaultButton==null||defaultButton.getVisibility()!=View.VISIBLE);}
        android.util.DisplayMetrics metrics=activity.getResources().getDisplayMetrics();assertEquals(Math.round(metrics.widthPixels*.70f),dialog.getWindow().getAttributes().width);assertEquals(Math.round(metrics.heightPixels*.70f),dialog.getWindow().getAttributes().height);
        View close=closeAction(dialog);assertTrue(close.isFocusable());assertTrue(close.isClickable());assertTrue(close.getWidth()>0);assertEquals("The X uses a circular hit target",close.getWidth(),close.getHeight());assertNotEquals(View.NO_ID,close.getId());
        assertTrue(close.requestFocusFromTouch());assertTrue(close.hasFocus());assertEquals("The upper edge must keep focus on X",close.getId(),close.getNextFocusUpId());
        for(View row:enabledOptions(root)){assertNotEquals(View.NO_ID,row.getId());assertNotNull("Every capsule has an accessible visible caption",row.findViewWithTag("menuLabel"));assertTrue(row.isClickable());assertNotNull(row.getBackground());}
    }
    private void move(View from,View expected,int direction){assertTrue(from.requestFocusFromTouch());View next=from.focusSearch(direction);assertSame("D-pad navigation from "+from.getTag()+" direction="+direction,expected,next);assertTrue(next.requestFocus());assertTrue(next.hasFocus());}
    private void close(AlertDialog dialog)throws Exception{assertTrue(closeAction(dialog).performClick());settle();assertFalse(dialog.isShowing());}

    @Test public void builtInPagesUseOneGlassShellAndHaveSafeVerticalClosePaths()throws Exception{
        for(String method:new String[]{"showAccounts","showPower","showNetwork","showAudio","showTimer","showInputs","showSettings"}){
            AlertDialog dialog=open(method);assertEquals(method+" increments one modal",1,openCount());assertSharedShell(dialog);View root=dialog.getWindow().getDecorView().findViewWithTag("controlMenuRoot"),close=closeAction(dialog);List<View> rows=enabledOptions(root);
            View content=dialog.getWindow().getDecorView().findViewWithTag("controlVolumeSeek");
            if(!rows.isEmpty()){
                View first=rows.get(0),last=rows.get(rows.size()-1);move(close,content==null?first:content,View.FOCUS_DOWN);move(first,content==null?close:content,View.FOCUS_UP);
                for(int i=rows.size()-1;i>0;i--)move(rows.get(i),rows.get(i-1),View.FOCUS_UP);
                assertEquals("Holding Down at the last option must not select X",last.getId(),last.getNextFocusDownId());
            }
            close(dialog);assertEquals(method+" releases its modal",0,openCount());
        }
        assertNull("Safe menu inspection must not launch system settings",shadowOf(activity).getNextStartedActivity());
    }

    @Test public void currentProfileSwitchesInOneActivationAndUpdatesTheHeader()throws Exception{
        AlertDialog dialog=open("showAccounts");View current=option(dialog,"profile-current");TextView caption=current.findViewWithTag("menuLabel");assertTrue(caption.getText().toString().contains("家庭"));assertTrue(current.performClick());settle();
        assertEquals("訪客",prefs.getString("profile",""));assertEquals("訪客",((TextView)field("profileLabel")).getText().toString());assertFalse(dialog.isShowing());assertEquals(0,openCount());
        dialog=open("showAccounts");current=option(dialog,"profile-current");caption=current.findViewWithTag("menuLabel");assertTrue(caption.getText().toString().contains("訪客"));assertTrue(current.performClick());settle();assertEquals("家庭",prefs.getString("profile",""));assertEquals(0,openCount());
    }

    @Test public void childCloseReturnsToTheSameSettingsRowAndPreservesItsParent()throws Exception{
        AlertDialog parent=open("showSettings");View appearance=option(parent,"settings-appearance");assertTrue(appearance.requestFocusFromTouch());assertTrue(appearance.performClick());settle();AlertDialog child=(AlertDialog)org.robolectric.shadows.ShadowDialog.getLatestDialog();layout(child);
        assertNotSame(parent,child);assertSharedShell(child);assertTrue(parent.isShowing());assertEquals(2,openCount());close(child);assertTrue(parent.isShowing());assertEquals(1,openCount());assertTrue("Closing the child restores its triggering capsule",appearance.hasFocus());close(parent);assertEquals(0,openCount());
    }

    @Test public void childBackReturnsToAboutAndRepeatedVisitsDoNotLeakModalCount()throws Exception{
        AlertDialog parent=open("showSettings");View about=option(parent,"settings-about");
        for(int visit=0;visit<2;visit++){
            assertTrue(about.requestFocusFromTouch());assertTrue(about.performClick());settle();AlertDialog child=(AlertDialog)org.robolectric.shadows.ShadowDialog.getLatestDialog();layout(child);assertSharedShell(child);assertTrue(parent.isShowing());assertEquals(2,openCount());child.onBackPressed();settle();assertFalse(child.isShowing());assertTrue(parent.isShowing());assertEquals(1,openCount());assertTrue("Back restores the parent about action",about.hasFocus());
        }
        close(parent);assertEquals(0,openCount());
    }

    @Test public void appearanceChangesPersistWithoutLosingTheFocusedCapsule()throws Exception{
        AlertDialog parent=open("showSettings");option(parent,"settings-appearance").performClick();settle();AlertDialog child=(AlertDialog)org.robolectric.shadows.ShadowDialog.getLatestDialog();layout(child);
        String[] tags={"artwork","motion","hdmi"},keys={"artwork","reduceMotion","hdmiPreview"};
        for(int i=0;i<tags.length;i++){
            View row=option(child,"appearance-"+tags[i]);boolean before=prefs.getBoolean(keys[i],false);assertTrue(row.requestFocusFromTouch());assertTrue(row.performClick());settle();layout(child);assertEquals(!before,prefs.getBoolean(keys[i],before));assertTrue("Toggling must preserve the same capsule's focus",row.hasFocus());assertTrue(row.isEnabled());assertTrue(child.isShowing());assertTrue(parent.isShowing());assertEquals(2,openCount());
            assertTrue(row.performClick());settle();assertEquals(before,prefs.getBoolean(keys[i],!before));assertTrue(row.hasFocus());
        }
        close(child);close(parent);assertEquals(0,openCount());
    }

    @Test public void volumeSeekNavigatesToCloseAndOptionsWithoutChangingVolume()throws Exception{
        AudioManager audio=(AudioManager)activity.getSystemService(android.content.Context.AUDIO_SERVICE);int original=audio.getStreamVolume(AudioManager.STREAM_MUSIC);AlertDialog dialog=open("showAudio");SeekBar seek=dialog.getWindow().getDecorView().findViewWithTag("controlVolumeSeek");assertNotNull(seek);assertEquals(original,seek.getProgress());View close=closeAction(dialog),bluetooth=option(dialog,"audio-bluetooth"),system=option(dialog,"audio-system");
        move(seek,close,View.FOCUS_UP);move(close,seek,View.FOCUS_DOWN);move(seek,bluetooth,View.FOCUS_DOWN);move(bluetooth,seek,View.FOCUS_UP);move(bluetooth,system,View.FOCUS_DOWN);assertEquals(system.getId(),system.getNextFocusDownId());assertEquals("Only focus moved; the test must not alter TV audio",original,audio.getStreamVolume(AudioManager.STREAM_MUSIC));close(dialog);
    }

    @Test public void inactiveSleepCancelIsSkippedWhileEnabledTimerRowsRemainReachable()throws Exception{
        prefs.edit().remove("sleepAt").commit();AlertDialog dialog=open("showTimer");View off=option(dialog,"timer-off"),first=option(dialog,"timer-30"),second=option(dialog,"timer-60"),third=option(dialog,"timer-90");assertFalse(off.isEnabled());assertFalse(off.isFocusable());move(closeAction(dialog),first,View.FOCUS_DOWN);move(first,second,View.FOCUS_DOWN);move(second,third,View.FOCUS_DOWN);assertEquals(third.getId(),third.getNextFocusDownId());assertFalse("Navigation must not create a sleep timer",prefs.contains("sleepAt"));close(dialog);
    }
}
