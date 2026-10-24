package com.akai.livingtv;

import android.app.Activity;
import android.os.Looper;
import android.view.View;
import android.widget.FrameLayout;
import java.lang.reflect.Field;
import java.time.Duration;
import org.junit.*;
import org.junit.runner.RunWith;
import org.robolectric.*;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;
import org.robolectric.util.ReflectionHelpers;
import static org.junit.Assert.*;
import static org.robolectric.Shadows.shadowOf;

/** Playback-state motion must stop when paused, hidden, or removed. No audio session. */
@RunWith(RobolectricTestRunner.class) @Config(sdk=28)
public class NowPlayingIndicatorTest {
    private ActivityController<Activity> controller;private FrameLayout host;private NowPlayingIndicator icon;
    @Before public void setup(){
        controller=Robolectric.buildActivity(Activity.class).setup();Activity activity=controller.get();host=new FrameLayout(activity);activity.setContentView(host);icon=new NowPlayingIndicator(activity);host.addView(icon,new FrameLayout.LayoutParams(28,45));
        // Setting new content after ActivityController.setup() queues the window
        // traversal; measuring the host alone does not attach it to ViewRootImpl.
        controller.visible();shadowOf(Looper.getMainLooper()).idle();layout();shadowOf(Looper.getMainLooper()).idle();setWindowVisibility(View.VISIBLE);
        assertTrue("Fixture must attach the icon to a real visible window",icon.isAttachedToWindow());assertTrue("Fixture parent chain must be shown",icon.isShown());assertEquals(View.VISIBLE,icon.getWindowVisibility());
    }
    @After public void close(){controller.pause().stop().destroy();}
    private void layout(){host.measure(View.MeasureSpec.makeMeasureSpec(400,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(200,View.MeasureSpec.EXACTLY));host.layout(0,0,400,200);}
    private void setWindowVisibility(int visibility){
        // API28 Robolectric attaches the new content but leaves AttachInfo's
        // window visibility GONE. Model the window event explicitly; production
        // still requires the real attached, shown, visible-window conditions.
        Object attachInfo=ReflectionHelpers.getField(icon,"mAttachInfo");ReflectionHelpers.setField(attachInfo,"mWindowVisibility",visibility);host.dispatchWindowVisibilityChanged(visibility);
    }
    private int phase()throws Exception{Field field=NowPlayingIndicator.class.getDeclaredField("phase");field.setAccessible(true);return field.getInt(icon);}
    private boolean animating()throws Exception{Field field=NowPlayingIndicator.class.getDeclaredField("animating");field.setAccessible(true);return field.getBoolean(icon);}
    @Test public void playingAdvancesAtBoundedRateAndPausedCancelsTheLoop()throws Exception{
        icon.setPlaybackState("playing");assertTrue(animating());int initial=phase();
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(66));assertEquals(initial,phase());
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(1));assertNotEquals(initial,phase());
        icon.setPlaybackState("paused");assertFalse(animating());int stopped=phase();shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(2));assertEquals(stopped,phase());
    }
    @Test public void hiddenOrDetachedIconRetainsNoScheduledFrames()throws Exception{
        icon.setPlaybackState("playing");assertTrue(animating());host.setVisibility(View.GONE);assertFalse(animating());int stopped=phase();shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(1));assertEquals(stopped,phase());
        host.setVisibility(View.VISIBLE);assertTrue(animating());setWindowVisibility(View.GONE);assertFalse(animating());setWindowVisibility(View.VISIBLE);assertTrue(animating());host.removeView(icon);assertFalse(animating());stopped=phase();shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(1));assertEquals(stopped,phase());
    }
    @Test public void nonPlayingStatesNeverAnimate()throws Exception{
        for(String state:new String[]{"stopped","preparing","external","finished","error"}){icon.setPlaybackState(state);assertFalse(state,animating());}
        assertFalse("The status glyph is not a second playback button",icon.isClickable());assertFalse(icon.isFocusable());
    }
}
