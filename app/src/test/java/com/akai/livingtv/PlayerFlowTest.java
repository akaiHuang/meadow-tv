package com.akai.livingtv;
import android.os.*;import android.view.*;import org.junit.*;import org.junit.runner.RunWith;import org.robolectric.*;import org.robolectric.annotation.*;import org.robolectric.android.controller.ActivityController;import java.lang.reflect.*;import java.time.Duration;
import static org.junit.Assert.*;import static org.robolectric.Shadows.shadowOf;
@RunWith(RobolectricTestRunner.class) @Config(sdk=28) @GraphicsMode(GraphicsMode.Mode.NATIVE)
public class PlayerFlowTest {
    @Test public void stoppedDockRemainsForThirtySecondsThenRestoresPreview()throws Exception{
        Field singleton=MediaQueue.class.getDeclaredField("instance");singleton.setAccessible(true);singleton.set(null,null);
        ActivityController<MainActivity> controller=Robolectric.buildActivity(MainActivity.class).setup();MainActivity activity=controller.get();MediaQueue q=MediaQueue.get(activity);q.clear();MediaItem item=new MediaItem();item.id="pod:player-test";item.title="Podcast episode";item.show="Publisher";item.audio="https://example.org/podcast.mp3";q.add(item);q.select(0);q.update("playing","");shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(400));
        Field f=MainActivity.class.getDeclaredField("miniPlayer");f.setAccessible(true);MiniPlayerDock dock=(MiniPlayerDock)f.get(activity);assertTrue(dock.showing());assertEquals(View.VISIBLE,dock.getVisibility());
        dock.stop.performClick();shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(29));assertEquals("stopped",q.state);assertTrue(dock.showing());assertEquals(1,q.items().size());shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(2));assertFalse(dock.showing());assertEquals(View.GONE,dock.getVisibility());controller.pause().stop().destroy();
    }
    @Test public void playerTimeDisplaysLongPodcastsWithoutMinuteOverflow(){assertEquals("12:48",PlayerDialog.time(768000));assertEquals("1:02:03",PlayerDialog.time(3723000));}
}
