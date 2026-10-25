package com.akai.livingtv;

import android.app.AlertDialog;
import android.content.Context;
import android.os.Looper;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.*;
import org.junit.runner.RunWith;
import org.robolectric.*;
import org.robolectric.annotation.*;
import java.io.File;
import java.io.IOException;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.Assert.*;
import static org.robolectric.Shadows.shadowOf;

/** Real IDs and local cache fixtures only: opening a library never starts voice or a directory fetch. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk=28,qualifiers="w960dp-h540dp-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
public class PodcastLibraryUiTest {
    private static final String SHOW_ID="show:apple:918273645";
    private static final String EPISODE_ID="pod:arbitrary-library-episode";
    private OfflineDesignTest fixture;
    private PodcastRepository repository;
    private ExecutorService executor;
    private final AtomicInteger fetches=new AtomicInteger();
    private final List<AlertDialog> dialogs=new ArrayList<>();

    @Before public void setup()throws Exception{
        fixture=new OfflineDesignTest();fixture.setup();fixture.seedQueue();
        Context context=fixture.activity;long now=System.currentTimeMillis();
        context.getSharedPreferences("podcast_preferences",0).edit().clear().commit();
        PodcastShow show=new PodcastShow(SHOW_ID,"這是任意新增的中文節目，並不在預設的三個節目之中","離線測試出版社","https://example.org/arbitrary-show.xml","","",918273645L,true,now);
        JSONArray cached=new JSONArray();
        for(MediaItem old:PodcastRepository.get(context).items()){
            JSONObject item=old.json();item.put("image","");cached.put(item);
        }
        MediaItem episode=new MediaItem();episode.id=EPISODE_ID;episode.showId=SHOW_ID;episode.show=show.title;
        episode.title="下班後，留一點時間給自己：一起聊聊最近的生活、放鬆與重新出發";
        episode.audio="https://example.org/arbitrary-library-episode.mp3";episode.date="2026-10-24";cached.put(episode.json());
        new File(context.getFilesDir(),"podcast-shows.json.bak").delete();new File(context.getFilesDir(),"podcast-library.json.bak").delete();
        fixture.write(context,"podcast-shows.json",new JSONObject().put("version",1).put("shows",new JSONArray().put(show.json())));
        fixture.write(context,"podcast-library.json",new JSONObject().put("at",now).put("defaultsAt",now).put("showFetched",new JSONObject().put(SHOW_ID,now)).put("items",cached));
        executor=Executors.newSingleThreadExecutor();
        repository=new PodcastRepository(context,(url,max,prefix)->{fetches.incrementAndGet();throw new IOException("Unexpected directory/feed request in an offline UI test");},executor,()->now);
        Field instance=PodcastRepository.class.getDeclaredField("instance");instance.setAccessible(true);instance.set(null,repository);
    }
    @After public void close(){
        for(int i=dialogs.size()-1;i>=0;i--)if(dialogs.get(i).isShowing())dialogs.get(i).dismiss();
        if(executor!=null)executor.shutdownNow();if(fixture!=null)fixture.close();
    }

    @Test public void queueSearchEntriesHaveACompleteRemoteRouteAndVideoHidesPodcastActions()throws Exception{
        QueueDialog panel=new QueueDialog(fixture.activity);panel.open();AlertDialog dialog=track(panel);settle(dialog);
        View voice=dialog.getWindow().getDecorView().findViewWithTag("queuePodcastVoice"),shows=dialog.getWindow().getDecorView().findViewWithTag("queuePodcastLibrary");
        EditText query=(EditText)fixture.field(panel,"query");View search=(View)fixture.field(panel,"searchButton");
        assertNotNull(voice);assertNotNull(shows);assertEquals(View.VISIBLE,voice.getVisibility());assertTrue(voice.isFocusable());
        int podcastWidth=query.getWidth();assertTrue(voice.requestFocusFromTouch());assertSame(query,voice.focusSearch(View.FOCUS_RIGHT));
        assertTrue(query.requestFocusFromTouch());assertSame(search,query.focusSearch(View.FOCUS_RIGHT));
        LinearLayout rows=(LinearLayout)fixture.field(panel,"libraryRows");View first=((LinearLayout)rows.getChildAt(0)).getChildAt(0);
        assertTrue(search.requestFocusFromTouch());assertSame(first,search.focusSearch(View.FOCUS_DOWN));
        ((View)fixture.field(panel,"videoTab")).performClick();settle(dialog);
        assertEquals(View.GONE,voice.getVisibility());assertFalse(voice.isFocusable());assertEquals(View.GONE,shows.getVisibility());assertFalse(shows.isFocusable());
        assertTrue("Video search uses the space released by the podcast voice action",query.getWidth()>podcastWidth);
        ((View)fixture.field(panel,"podcastTab")).performClick();settle(dialog);
        assertEquals(View.VISIBLE,voice.getVisibility());assertTrue(voice.isFocusable());assertEquals(podcastWidth,query.getWidth());assertEquals(0,fetches.get());
    }

    @Test public void emptyFavoritesStillOfferSearchAndMeadowWithoutConnectingOrFetching()throws Exception{
        assertTrue(repository.unfollow(SHOW_ID));Object assistantBefore=fixture.field(fixture.activity,"assistant");
        PodcastLibraryDialog panel=new PodcastLibraryDialog(fixture.activity);panel.open();AlertDialog dialog=track(panel);settle(dialog);fixture.assertCompactPanel(dialog);
        View decor=dialog.getWindow().getDecorView();String text=visibleText(decor);
        assertTrue("Empty favorites provide a concrete next step",text.contains("搜尋")&&text.contains("Meadow"));
        assertNull("Seed suggestions must not masquerade as the user's saved shows",decor.findViewWithTag(SHOW_ID));
        View voice=(View)fixture.field(panel,"voiceSearch"),query=(View)fixture.field(panel,"query");
        assertTrue(voice.isEnabled());assertTrue(query.isEnabled());assertTrue(voice.requestFocusFromTouch());
        assertSame("Opening or focusing voice search does not create an assistant connection",assistantBefore,fixture.field(fixture.activity,"assistant"));assertEquals(0,fetches.get());
    }

    @Test public void followAndUnfollowUseTheChosenShowIdWithoutChangingPlayback()throws Exception{
        List<String> before=queueIds();String current=fixture.queue.current().id;int index=fixture.queue.index;String state=fixture.queue.state;
        PodcastLibraryDialog panel=new PodcastLibraryDialog(fixture.activity);panel.open();AlertDialog dialog=track(panel);settle(dialog);
        View row=dialog.getWindow().getDecorView().findViewWithTag(SHOW_ID);assertNotNull("A non-seed followed show is visible",row);assertTrue(row.performClick());settle(dialog);
        View follow=(View)fixture.field(panel,"followButton");assertTrue(follow.isEnabled());assertTrue(repository.show(SHOW_ID).followed);
        assertTrue(follow.performClick());settle(dialog);assertFalse(repository.show(SHOW_ID).followed);assertPlayback(before,current,index,state);
        assertTrue("The selected details stay available for undo",follow.isEnabled());assertTrue(follow.performClick());settle(dialog);assertTrue(repository.show(SHOW_ID).followed);
        assertPlayback(before,current,index,state);assertEquals(0,fetches.get());
    }

    @Test public void addingAnEpisodeKeepsTheCurrentItemAndPausedStateAndDoesNotDuplicateIt()throws Exception{
        List<String> before=queueIds();String current=fixture.queue.current().id;int index=fixture.queue.index;String state=fixture.queue.state;
        PodcastLibraryDialog panel=new PodcastLibraryDialog(fixture.activity);panel.open();AlertDialog dialog=track(panel);settle(dialog);
        View row=dialog.getWindow().getDecorView().findViewWithTag(SHOW_ID);assertNotNull(row);assertTrue(row.requestFocusFromTouch());assertEquals("Focus alone does not fetch episodes",0,fetches.get());
        assertTrue(row.performClick());settle(dialog);View add=dialog.getWindow().getDecorView().findViewWithTag("podcastEpisodeAdd:"+EPISODE_ID);assertNotNull("Cached real-ID episodes offer an add action",add);
        View episodeRow=dialog.getWindow().getDecorView().findViewWithTag("podcastEpisode:"+EPISODE_ID);
        TextView episodeTitle=episodeRow.findViewWithTag("podcastEpisodeTitle"),episodeDate=episodeRow.findViewWithTag("podcastEpisodeDate");
        assertTrue("Long CJK episode titles keep both lines inside their measured view",episodeTitle.getLayout().getLineBottom(episodeTitle.getLayout().getLineCount()-1)<=episodeTitle.getHeight());
        assertTrue("Episode metadata starts below the title",episodeTitle.getBottom()<episodeDate.getTop());assertTrue("Episode metadata fits its natural row",episodeDate.getBottom()<=episodeRow.getHeight());
        assertTrue(add.performClick());shadowOf(Looper.getMainLooper()).idle();assertEquals(before.size()+1,fixture.queue.items().size());assertEquals(EPISODE_ID,fixture.queue.items().get(before.size()).id);
        assertEquals(current,fixture.queue.current().id);assertEquals(index,fixture.queue.index);assertEquals(state,fixture.queue.state);
        assertTrue(add.performClick());shadowOf(Looper.getMainLooper()).idle();assertEquals("Adding the same episode twice is idempotent",before.size()+1,fixture.queue.items().size());
        assertEquals(current,fixture.queue.current().id);assertEquals(state,fixture.queue.state);assertEquals(0,fetches.get());
    }

    @Test public void recommendationTabAndPreferencesKeepRemoteFocusAndNeverStartPlayback()throws Exception {
        List<String> before=queueIds();String current=fixture.queue.current().id;int index=fixture.queue.index;String state=fixture.queue.state;
        PodcastLibraryDialog panel=new PodcastLibraryDialog(fixture.activity);panel.open();AlertDialog dialog=track(panel);settle(dialog);
        View followed=(View)fixture.field(panel,"followedTab"),recommend=(View)fixture.field(panel,"recommendedTab"),results=(View)fixture.field(panel,"resultsTab"),preferences=(View)fixture.field(panel,"preferencesButton"),close=(View)fixture.field(panel,"close");
        assertTrue(followed.requestFocusFromTouch());assertSame(recommend,followed.focusSearch(View.FOCUS_RIGHT));assertTrue(recommend.performClick());settle(dialog);assertTrue(recommend.isSelected());assertFalse(results.isSelected());
        assertTrue("The requested Guaji show is a real recommendation",visibleText(dialog.getWindow().getDecorView()).contains("呱吉"));assertTrue(visibleText(dialog.getWindow().getDecorView()).contains("收藏與播放"));
        assertTrue(results.requestFocusFromTouch());assertTrue("Moving focus does not change the current list",recommend.isSelected());assertSame(preferences,results.focusSearch(View.FOCUS_RIGHT));assertTrue(preferences.requestFocusFromTouch());assertSame(close,preferences.focusSearch(View.FOCUS_RIGHT));
        MediaItem played=new MediaItem();played.id="pod:local-history";played.showId=SHOW_ID;played.show=repository.show(SHOW_ID).title;assertTrue(repository.recordSuccessfulPlayback(played));
        preferences.performClick();AlertDialog settings=(AlertDialog)org.robolectric.shadows.ShadowDialog.getLatestDialog();dialogs.add(settings);settle(settings);View toggle=settings.getWindow().getDecorView().findViewWithTag("controlMenu:podcast-personalization");assertNotNull(toggle);toggle.performClick();assertFalse(repository.personalizationEnabled());
        settings.getWindow().getDecorView().findViewWithTag("controlMenuClose").performClick();settle(dialog);assertTrue(preferences.hasFocus());assertTrue(recommend.isSelected());
        preferences.performClick();settings=(AlertDialog)org.robolectric.shadows.ShadowDialog.getLatestDialog();dialogs.add(settings);settle(settings);settings.getWindow().getDecorView().findViewWithTag("controlMenu:podcast-personalization").performClick();assertTrue(repository.personalizationEnabled());settings.getWindow().getDecorView().findViewWithTag("controlMenu:podcast-clear-preferences").performClick();assertEquals(0,repository.successfulPlayCount(SHOW_ID));assertTrue(repository.show(SHOW_ID).followed);settings.getWindow().getDecorView().findViewWithTag("controlMenuClose").performClick();settle(dialog);
        assertPlayback(before,current,index,state);assertEquals(0,fetches.get());
    }

    private AlertDialog track(Object panel)throws Exception{AlertDialog dialog=(AlertDialog)fixture.field(panel,"dialog");dialogs.add(dialog);return dialog;}
    private void settle(AlertDialog dialog)throws Exception{fixture.settle();WindowManager.LayoutParams bounds=dialog.getWindow().getAttributes();fixture.layout(dialog.getWindow().getDecorView(),bounds.width,bounds.height);}
    private List<String> queueIds(){List<String> ids=new ArrayList<>();for(MediaItem item:fixture.queue.items())ids.add(item.id);return ids;}
    private void assertPlayback(List<String> ids,String current,int index,String state){assertEquals(ids,queueIds());assertEquals(current,fixture.queue.current().id);assertEquals(index,fixture.queue.index);assertEquals(state,fixture.queue.state);}
    private String visibleText(View view){
        if(view.getVisibility()!=View.VISIBLE)return "";StringBuilder text=new StringBuilder();if(view instanceof TextView)text.append(((TextView)view).getText()).append(' ');
        if(view instanceof ViewGroup){ViewGroup group=(ViewGroup)view;for(int i=0;i<group.getChildCount();i++)text.append(visibleText(group.getChildAt(i)));}return text.toString();
    }
}
