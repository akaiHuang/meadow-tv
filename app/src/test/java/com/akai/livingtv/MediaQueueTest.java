package com.akai.livingtv;

import android.content.*;
import android.media.*;
import android.os.*;
import org.json.*;
import org.junit.*;
import org.junit.runner.RunWith;
import org.robolectric.*;
import org.robolectric.annotation.Config;
import org.robolectric.android.controller.ServiceController;
import org.robolectric.shadows.ShadowMediaPlayer;
import org.robolectric.shadows.util.DataSource;
import java.lang.reflect.Field;
import java.util.concurrent.atomic.AtomicReference;
import static org.junit.Assert.*;
import static org.robolectric.Shadows.shadowOf;

@RunWith(RobolectricTestRunner.class) @Config(sdk=28)
public class MediaQueueTest {
    Context context; MediaQueue queue;
    @Before public void setup()throws Exception {
        context=RuntimeEnvironment.getApplication();
        for(Class<?> type:new Class<?>[]{MediaQueue.class,PodcastRepository.class}) {Field f=type.getDeclaredField("instance");f.setAccessible(true);f.set(null,null);}
        context.deleteFile("media-queue.json");context.deleteFile("podcast-library.json");
        queue=MediaQueue.get(context);context.getSharedPreferences("podcast_preferences",0).edit().clear().commit();ShadowMediaPlayer.resetStaticState();
    }
    MediaItem episode(String id) {MediaItem i=new MediaItem();i.id="pod:"+id;i.title="Episode "+id;i.show="Publisher";i.audio="https://example.org/"+id+".mp3";return i;}
    @Test public void reorderingKeepsCurrentItemAndAddingDeduplicates() {
        MediaItem a=episode("a"),b=episode("b"),c=episode("c");queue.add(a);queue.add(b);queue.add(c);queue.select(1);
        assertTrue(queue.move(1,0));assertSame(b,queue.current());assertEquals(0,queue.index);assertEquals(0,queue.add(b));assertEquals(3,queue.items().size());
        assertFalse(queue.move(0,99));assertTrue(queue.remove(2));assertSame(b,queue.current());
        assertFalse(queue.snapshot().optBoolean("externalAppAutoAdvance",true));assertFalse(queue.snapshot().toString().contains("example.org"));
    }
    @Test public void unknownModelIdCannotPlayAnotherQueuedEpisode()throws Exception {
        queue.add(episode("a"));AtomicReference<JSONObject> result=new AtomicReference<>();
        QueueController.execute(context,new JSONObject().put("operation","play").put("itemId","https://evil.invalid/audio.mp3"),result::set);
        assertFalse(result.get().getBoolean("ok"));assertEquals(-1,queue.index);assertEquals("stopped",queue.state);
        assertNull(shadowOf((android.app.Application)context).getNextStartedService());
    }
    @Test public void publicMediaRejectsLocalAndCredentialedUrls() {
        for(String u:new String[]{"http://example.org/a.mp3","https://127.0.0.1/a","https://192.168.50.31/a","https://localhost/a","https://tv.local/a","https://u:p@example.org/a","https://example.org:8443/a"})assertFalse(u,PodcastRepository.safe(u,false));
        assertTrue(PodcastRepository.safe("https://cdn.example.org/episode.mp3",false));
    }
    @Test public void realCompletionAdvancesOnePlayerAndPauseRetainsQueue()throws Exception {
        MediaItem a=episode("a"),b=episode("b");queue.add(a);queue.add(b);queue.select(0);
        ShadowMediaPlayer.addMediaInfo(DataSource.toDataSource(a.audio),new ShadowMediaPlayer.MediaInfo(60000,0));
        ShadowMediaPlayer.addMediaInfo(DataSource.toDataSource(b.audio),new ShadowMediaPlayer.MediaInfo(60000,0));
        shadowOf((AudioManager)context.getSystemService(Context.AUDIO_SERVICE)).setNextFocusRequestResponse(AudioManager.AUDIOFOCUS_REQUEST_GRANTED);
        ServiceController<MediaQueueService> controller=Robolectric.buildService(MediaQueueService.class).create();MediaQueueService service=controller.get();
        service.onStartCommand(new Intent(context,MediaQueueService.class).setAction("PLAY"),0,1);shadowOf(Looper.getMainLooper()).idle();
        assertEquals("playing",queue.state);Field f=MediaQueueService.class.getDeclaredField("player");f.setAccessible(true);MediaPlayer first=(MediaPlayer)f.get(service);assertTrue(first.isPlaying());
        shadowOf(first).invokeCompletionListener();shadowOf(Looper.getMainLooper()).idle();assertSame(b,queue.current());assertEquals("playing",queue.state);assertNotSame(first,f.get(service));
        service.onStartCommand(new Intent(context,MediaQueueService.class).setAction("PAUSE"),0,2);assertEquals("paused",queue.state);assertEquals(2,queue.items().size());controller.destroy();
    }
    @Test public void recommendationHistoryStartsOnlyAfterAudioStartsAndNotOnQueueOrFailure()throws Exception {
        PodcastRepository repository=PodcastRepository.get(context);PodcastShow show=repository.recommendations().get(0);MediaItem item=episode("preference");item.showId=show.showId;item.show=show.title;queue.add(item);queue.select(0);assertEquals(0,repository.successfulPlayCount(show.showId));
        ShadowMediaPlayer.addMediaInfo(DataSource.toDataSource(item.audio),new ShadowMediaPlayer.MediaInfo(60000,2000));shadowOf((AudioManager)context.getSystemService(Context.AUDIO_SERVICE)).setNextFocusRequestResponse(AudioManager.AUDIOFOCUS_REQUEST_GRANTED);
        ServiceController<MediaQueueService> controller=Robolectric.buildService(MediaQueueService.class).create();MediaQueueService service=controller.get();service.onStartCommand(new Intent(context,MediaQueueService.class).setAction("PLAY"),0,1);
        assertEquals("preparing",queue.state);assertEquals(0,repository.successfulPlayCount(show.showId));shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofSeconds(2));assertEquals("playing",queue.state);assertEquals(1,repository.successfulPlayCount(show.showId));
        service.onStartCommand(new Intent(context,MediaQueueService.class).setAction("PAUSE"),0,2);service.onStartCommand(new Intent(context,MediaQueueService.class).setAction("TOGGLE"),0,3);assertEquals(1,repository.successfulPlayCount(show.showId));
        service.onStartCommand(new Intent(context,MediaQueueService.class).setAction("PLAY"),0,4);shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofSeconds(2));assertEquals(1,repository.successfulPlayCount(show.showId));controller.destroy();
        MediaItem invalid=episode("invalid");invalid.showId=show.showId;invalid.show=show.title;invalid.audio="https://localhost/private.mp3";queue.add(invalid);queue.select(1);ServiceController<MediaQueueService> bad=Robolectric.buildService(MediaQueueService.class).create();bad.get().onStartCommand(new Intent(context,MediaQueueService.class).setAction("PLAY"),0,1);assertEquals("error",queue.state);assertEquals(1,repository.successfulPlayCount(show.showId));bad.destroy();
    }
    @Test public void progressDoesNotTouchPlayerUntilPreparationCompletes()throws Exception {
        MediaItem item=episode("slow");queue.add(item);queue.select(0);ShadowMediaPlayer.addMediaInfo(DataSource.toDataSource(item.audio),new ShadowMediaPlayer.MediaInfo(60000,2000));shadowOf((AudioManager)context.getSystemService(Context.AUDIO_SERVICE)).setNextFocusRequestResponse(AudioManager.AUDIOFOCUS_REQUEST_GRANTED);
        ServiceController<MediaQueueService> controller=Robolectric.buildService(MediaQueueService.class).create();MediaQueueService service=controller.get();service.onStartCommand(new Intent(context,MediaQueueService.class).setAction("PLAY"),0,1);assertEquals("preparing",queue.state);assertArrayEquals(new long[]{0,0},MediaQueueService.progress());MediaQueueService.seek(15000);assertEquals("preparing",queue.state);
        shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofSeconds(2));assertEquals("playing",queue.state);assertEquals(60000,MediaQueueService.progress()[1]);controller.destroy();
    }
}
