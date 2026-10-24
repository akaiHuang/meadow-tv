package com.akai.livingtv;

import android.app.AlertDialog;
import android.content.Context;
import android.graphics.*;
import android.os.Looper;
import android.view.*;
import android.widget.*;
import java.io.*;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.time.Duration;
import org.junit.*;
import org.junit.runner.RunWith;
import org.robolectric.*;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.*;
import static org.junit.Assert.*;
import static org.robolectric.Shadows.shadowOf;

/** Native TV layout fixtures only; no media is started or paired. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk=28,qualifiers="w960dp-h540dp-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
public class PlayerLayoutTest {
    private ActivityController<MainActivity> controller;private MainActivity activity;private PlayerDialog panel;private MediaQueue queue;
    @Before public void setup()throws Exception{
        Context c=RuntimeEnvironment.getApplication();Field singleton=MediaQueue.class.getDeclaredField("instance");singleton.setAccessible(true);singleton.set(null,null);new File(c.getFilesDir(),"media-queue.json").delete();
        c.getSharedPreferences("living",0).edit().clear().putBoolean("hdmiPreview",false).putLong("weather0At",System.currentTimeMillis()).putString("weather0","{\"temperature_2m\":24,\"weather_code\":2,\"is_day\":1}").commit();
        controller=Robolectric.buildActivity(MainActivity.class).setup();activity=controller.get();layoutView(activity.getWindow().getDecorView(),1920,1080);
        queue=MediaQueue.get(activity);queue.clear();queue.add(item("one","為什麼越休息越累？試著慢下來，重新找回照顧自己的日常，和家人一起聊聊生活裡的小事"));queue.add(item("two","留一點時間給自己，也給在乎的人"));queue.select(0);queue.update("paused","");
        panel=new PlayerDialog(activity);panel.open();settle();
    }
    @After public void close()throws Exception{if(panel!=null)((AlertDialog)field("dialog")).dismiss();if(controller!=null)controller.pause().stop().destroy();}
    private MediaItem item(String id,String title){MediaItem m=new MediaItem();m.id="pod:layout-"+id;m.title=title;m.show="哇賽心理學";m.audio="https://example.org/layout-"+id+".mp3";return m;}
    private Object field(String name)throws Exception{Field f=PlayerDialog.class.getDeclaredField(name);f.setAccessible(true);return f.get(panel);}
    private void layoutView(View view,int width,int height){view.measure(View.MeasureSpec.makeMeasureSpec(width,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(height,View.MeasureSpec.EXACTLY));view.layout(0,0,width,height);}
    private void settle()throws Exception{for(int i=0;i<3;i++){Thread.sleep(50);shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(300));}AlertDialog d=(AlertDialog)field("dialog");WindowManager.LayoutParams p=d.getWindow().getAttributes();layoutView(d.getWindow().getDecorView(),p.width,p.height);}
    private void assertTextFits(TextView view){assertNotNull(view.getLayout());assertTrue(view.getText()+" last line must fit its view",view.getLayout().getLineBottom(view.getLayout().getLineCount()-1)<=view.getHeight());}
    @Test public void compactPlayerKeepsCjkAndTransportLabelsInsideTheFrame()throws Exception{
        TextView title=(TextView)field("title"),subtitle=(TextView)field("subtitle"),nextTitle=(TextView)field("nextTitle");View cover=(View)field("cover"),seek=(View)field("seek");FrameLayout stage=(FrameLayout)title.getParent();
        assertEquals("The player must follow the shared compact content-panel size",(int)(activity.getResources().getDisplayMetrics().widthPixels*.70),((AlertDialog)field("dialog")).getWindow().getAttributes().width);
        assertEquals((int)(activity.getResources().getDisplayMetrics().heightPixels*.70),((AlertDialog)field("dialog")).getWindow().getAttributes().height);
        assertEquals(2,title.getLayout().getLineCount());assertTextFits(title);assertTextFits(subtitle);assertTrue(title.getBottom()<subtitle.getTop());assertTrue(subtitle.getBottom()<seek.getTop());
        assertEquals("The frame keeps the approved generous cover inset",80,cover.getLeft());assertEquals("Title and next-item action share the same right inset",title.getRight(),nextTitle.getRight());assertTrue(nextTitle.getBottom()<=stage.getHeight()-28);
        View previous=null;for(String name:new String[]{"back","toggle","forward","stop","next"}){
            FrameLayout action=(FrameLayout)field(name);TextView caption=(TextView)action.getChildAt(1);assertTextFits(caption);assertTrue(caption.getBottom()<=action.getHeight());assertTrue(action.getBottom()<nextTitle.getTop());assertEquals("Unavailable seek must not trap the transport row",((View)field("library")).getId(),action.getNextFocusUpId());assertEquals(nextTitle.getId(),action.getNextFocusDownId());
            assertTrue(action.getChildAt(0) instanceof ImageView);assertNotNull(((ImageView)action.getChildAt(0)).getDrawable());
            if(previous!=null){assertTrue(previous.getRight()<action.getLeft());assertEquals(action.getId(),previous.getNextFocusRightId());}previous=action;
        }
        capture("player-native.png");
    }
    @Test public void nextItemUpdatesWhenQueueOrderChangesAndLastItemHasNoEmptyCoverGap()throws Exception{
        TextView nextTitle=(TextView)field("nextTitle");View nextCover=(View)field("nextCover"),nextAction=(View)field("next");MediaItem inserted=item("three","下次見面時，想和你說的話");queue.add(inserted);queue.move(2,1);settle();assertEquals(inserted.title,nextTitle.getText().toString());assertEquals(View.VISIBLE,nextCover.getVisibility());assertTrue(nextAction.isEnabled());
        queue.select(queue.items().size()-1);queue.update("paused","");settle();assertEquals(View.GONE,nextCover.getVisibility());assertEquals("The last-item hint should not retain a missing-art gutter",nextCover.getLeft(),nextTitle.getLeft());assertFalse(nextAction.isEnabled());assertEquals("已暫停",((TextView)field("state")).getText().toString());assertTrue(field("indicator") instanceof NowPlayingIndicator);
    }
    private View move(View from,int direction){
        assertTrue("The source must be available before navigating",from.requestFocusFromTouch());View target=from.focusSearch(direction);assertNotNull("The D-pad route must have an available target",target);assertTrue(target.isEnabled());assertTrue(target.isFocusable());assertTrue(target.requestFocusFromTouch());return target;
    }
    private void reportedProgress(long position,long length)throws Exception{Method method=PlayerDialog.class.getDeclaredMethod("renderProgress",long.class,long.class);method.setAccessible(true);method.invoke(panel,position,length);}
    @Test public void everyBottomActionReachesLibraryAndReturnsWithoutADuration()throws Exception{
        View library=(View)field("library"),footer=(View)field("nextTitle"),seek=(View)field("seek");assertFalse(seek.isEnabled());assertFalse(seek.isFocusable());
        View previous=null;for(String name:new String[]{"back","toggle","forward","stop","next"}){
            View action=(View)field(name);assertTrue(action.isEnabled());assertSame(footer,move(action,View.FOCUS_DOWN));assertSame("Footer Up remembers the transport it came from",action,move(footer,View.FOCUS_UP));assertSame("No duration still leaves a direct route to the header",library,move(action,View.FOCUS_UP));assertSame("Header Down returns to the originating transport",action,move(library,View.FOCUS_DOWN));
            if(previous!=null){assertSame(previous,move(action,View.FOCUS_LEFT));assertSame(action,move(previous,View.FOCUS_RIGHT));}previous=action;
        }
    }
    @Test public void availableSeekConnectsBothRowsAndHeaderWithoutLosingTheReturnAction()throws Exception{
        reportedProgress(3000,120000);View seek=(View)field("seek"),library=(View)field("library"),close=(View)field("close"),footer=(View)field("nextTitle");assertTrue(seek.isEnabled());assertTrue(seek.isFocusable());
        for(String name:new String[]{"back","toggle","forward","stop","next"}){
            View action=(View)field(name);assertSame(footer,move(action,View.FOCUS_DOWN));assertSame(action,move(footer,View.FOCUS_UP));assertSame(seek,move(action,View.FOCUS_UP));assertSame(library,move(seek,View.FOCUS_UP));assertSame(close,move(library,View.FOCUS_RIGHT));assertSame(library,move(close,View.FOCUS_LEFT));assertSame(seek,move(library,View.FOCUS_DOWN));assertSame("Seek Down returns to the originating transport",action,move(seek,View.FOCUS_DOWN));
        }
        assertEquals("Seek keeps horizontal arrows for scrubbing",seek.getId(),seek.getNextFocusLeftId());assertEquals(seek.getId(),seek.getNextFocusRightId());
        assertTrue(seek.requestFocusFromTouch());queue.update("stopped","");settle();reportedProgress(3000,120000);assertFalse("A stopped player cannot keep a seek focus target alive from cached duration",seek.isFocusable());View focused=((AlertDialog)field("dialog")).getCurrentFocus();assertNotNull(focused);assertNotSame(seek,focused);assertTrue(focused.isEnabled());assertSame(library,move((View)field("stop"),View.FOCUS_UP));
    }
    @Test public void lastItemAndExternalVideoSkipUnavailableControlsAndRecoverFocus()throws Exception{
        View library=(View)field("library"),next=(View)field("next"),stop=(View)field("stop"),forward=(View)field("forward"),toggle=(View)field("toggle");assertTrue(next.requestFocusFromTouch());queue.select(queue.items().size()-1);queue.update("stopped","");settle();
        assertFalse(next.isEnabled());assertFalse(next.isFocusable());View focused=((AlertDialog)field("dialog")).getCurrentFocus();assertNotNull(focused);assertNotSame(next,focused);assertTrue(focused.isEnabled());assertSame(forward,move(stop,View.FOCUS_LEFT));assertSame(stop,move(forward,View.FOCUS_RIGHT));assertSame(library,move(stop,View.FOCUS_UP));assertSame(stop,move(library,View.FOCUS_DOWN));
        MediaItem video=new MediaItem();video.id="video:player-focus";video.provider="netflix";video.title="影片";assertTrue(toggle.requestFocusFromTouch());queue.select(queue.add(video));queue.update("external","");settle();
        for(String name:new String[]{"back","toggle","forward","next"}){View action=(View)field(name);assertFalse(action.isEnabled());assertFalse(action.isFocusable());}
        focused=((AlertDialog)field("dialog")).getCurrentFocus();assertNotNull(focused);assertTrue(focused.isEnabled());assertNotSame(toggle,focused);assertSame(library,move(stop,View.FOCUS_UP));assertSame(stop,move(library,View.FOCUS_DOWN));assertSame((View)field("nextTitle"),move(stop,View.FOCUS_DOWN));assertSame(stop,move((View)field("nextTitle"),View.FOCUS_UP));
    }
    @Test public void clearingQueueRecoversHeaderFocusAndKeepsEmptyFooterReachable()throws Exception{
        View library=(View)field("library"),footer=(View)field("nextTitle"),close=(View)field("close");assertTrue(((View)field("stop")).requestFocusFromTouch());queue.clear();settle();
        View focused=((AlertDialog)field("dialog")).getCurrentFocus();assertSame("Clearing the active item restores an actionable header",library,focused);for(String name:new String[]{"back","toggle","forward","stop","next","seek"}){View action=(View)field(name);assertFalse(action.isEnabled());assertFalse(action.isFocusable());}
        assertNull(((ImageView)field("cover")).getDrawable());assertEquals(View.GONE,((View)field("nextCover")).getVisibility());assertEquals("從播放清單加入更多節目",((TextView)footer).getText().toString());assertSame(footer,move(library,View.FOCUS_DOWN));assertSame(library,move(footer,View.FOCUS_UP));assertSame(close,move(library,View.FOCUS_RIGHT));assertSame(footer,move(close,View.FOCUS_DOWN));assertSame(library,move(footer,View.FOCUS_UP));
        queue.select(queue.add(item("restored","重新選一集繼續聽")));queue.update("paused","");settle();assertTrue(((View)field("toggle")).isFocusable());View action=move(footer,View.FOCUS_UP);assertTrue(action.isEnabled());assertSame(library,move(action,View.FOCUS_UP));
    }
    @Test public void circularCloseIsCenteredAndReachableFromTheLibraryAction()throws Exception{
        View library=(View)field("library");ImageButton close=(ImageButton)field("close");assertEquals("關閉播放器",close.getContentDescription());assertEquals(62,close.getWidth());assertEquals(close.getWidth(),close.getHeight());assertEquals(close.getPaddingLeft(),close.getPaddingRight());assertEquals(close.getPaddingTop(),close.getPaddingBottom());assertEquals("Header controls share their visual centre",library.getTop()+library.getHeight()/2,close.getTop()+close.getHeight()/2);assertNotNull(close.getDrawable());assertSame(close,move(library,View.FOCUS_RIGHT));assertSame(library,move(close,View.FOCUS_LEFT));assertTrue(close.performClick());assertFalse(((AlertDialog)field("dialog")).isShowing());
    }
    private void capture(String name)throws Exception{
        Bitmap bitmap=Bitmap.createBitmap(1920,1080,Bitmap.Config.ARGB_8888);Canvas canvas=new Canvas(bitmap);activity.getWindow().getDecorView().draw(canvas);AlertDialog d=(AlertDialog)field("dialog");WindowManager.LayoutParams p=d.getWindow().getAttributes();canvas.save();canvas.translate((1920-p.width)/2f,(1080-p.height)/2f);d.getWindow().getDecorView().draw(canvas);canvas.restore();File target=new File("../artifacts/offline-0.8.8/"+name);target.getParentFile().mkdirs();try(FileOutputStream out=new FileOutputStream(target)){bitmap.compress(Bitmap.CompressFormat.PNG,100,out);}
    }
}
