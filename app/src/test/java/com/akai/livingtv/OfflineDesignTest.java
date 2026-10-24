package com.akai.livingtv;

import android.app.*;import android.content.*;import android.graphics.*;import android.os.*;import android.view.*;import android.widget.*;
import org.json.*;import org.junit.*;import org.junit.runner.RunWith;import org.robolectric.*;import org.robolectric.android.controller.ActivityController;import org.robolectric.annotation.*;
import java.io.*;import java.lang.reflect.*;import java.nio.charset.StandardCharsets;import java.time.Duration;import java.util.*;
import static org.junit.Assert.*;import static org.robolectric.Shadows.shadowOf;

/** Fixture-only native SDK28 checks. Never pairs a TV or starts Codex/voice. */
@RunWith(RobolectricTestRunner.class) @Config(sdk=28,qualifiers="w960dp-h540dp-xhdpi") @GraphicsMode(GraphicsMode.Mode.NATIVE)
public class OfflineDesignTest {
    ActivityController<MainActivity> controller;MainActivity activity;MediaQueue queue;
    @Before public void setup()throws Exception{
        Context c=RuntimeEnvironment.getApplication();reset(MediaQueue.class);reset(PodcastRepository.class);
        TestArtworkFixtures.installCatalog(c);
        c.getSharedPreferences("living",0).edit().clear().putBoolean("hdmiPreview",false).putLong("weather0At",System.currentTimeMillis()).putString("weather0","{\"temperature_2m\":26,\"weather_code\":61,\"is_day\":1}").commit();
        JSONArray catalog;try(InputStream in=c.getAssets().open("official-catalog.json")){catalog=new JSONObject(new String(in.readAllBytes(),StandardCharsets.UTF_8)).getJSONArray("items");}
        write(c,"feed.json",new JSONObject().put("fetchedAt",System.currentTimeMillis()).put("items",catalog));
        JSONArray seeds;try(InputStream in=c.getAssets().open("podcast-seed.json")){seeds=new JSONArray(new String(in.readAllBytes(),StandardCharsets.UTF_8));}
        JSONObject publisher=seeds.getJSONObject(2);JSONArray episodes=new JSONArray();String[] titles={"為什麼越休息越累？你可能根本沒照顧到自己｜哇賽一起聊ep36","生活好累，想躺平｜哇賽心觀點","告別瞎忙，留一點時間給自己｜哇賽心觀點"};
        for(int i=0;i<3;i++){MediaItem m=new MediaItem();m.id="pod:offline-preview-"+i;m.title=titles[i];m.show=publisher.getString("title");m.image=publisher.getString("poster");m.audio="https://example.org/fixture-"+i+".mp3";episodes.put(m.json());}
        write(c,"podcast-library.json",new JSONObject().put("at",System.currentTimeMillis()).put("defaultsAt",System.currentTimeMillis()).put("items",episodes));new File(c.getFilesDir(),"media-queue.json").delete();
        controller=Robolectric.buildActivity(MainActivity.class).setup();activity=controller.get();queue=MediaQueue.get(activity);settle();
    }
    @After public void close(){if(controller!=null)controller.pause().stop().destroy();}
    static void reset(Class<?> type)throws Exception{Field f=type.getDeclaredField("instance");f.setAccessible(true);f.set(null,null);}
    void write(Context c,String name,JSONObject o)throws Exception{try(FileOutputStream f=new FileOutputStream(new File(c.getFilesDir(),name))){f.write(o.toString().getBytes(StandardCharsets.UTF_8));}}
    Object field(Object o,String name)throws Exception{Field f=o.getClass().getDeclaredField(name);f.setAccessible(true);return f.get(o);}
    void set(Object o,String name,Object value)throws Exception{Field f=o.getClass().getDeclaredField(name);f.setAccessible(true);f.set(o,value);}
    void settle()throws Exception{for(int i=0;i<6;i++){Thread.sleep(60);shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(350));}layout(activity.getWindow().getDecorView(),1920,1080);}
    void layout(View view,int width,int height){view.measure(View.MeasureSpec.makeMeasureSpec(width,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(height,View.MeasureSpec.EXACTLY));view.layout(0,0,width,height);}
    void capture(String name,AlertDialog dialog)throws Exception{Bitmap bitmap=Bitmap.createBitmap(1920,1080,Bitmap.Config.ARGB_8888);Canvas canvas=new Canvas(bitmap);activity.getWindow().getDecorView().draw(canvas);if(dialog!=null){WindowManager.LayoutParams bounds=dialog.getWindow().getAttributes();int w=bounds.width>0?bounds.width:1766,h=bounds.height>0?bounds.height:961;View decor=dialog.getWindow().getDecorView();layout(decor,w,h);canvas.save();canvas.translate((1920-w)/2f,(1080-h)/2f);decor.draw(canvas);canvas.restore();}File out=new File("../artifacts/offline-0.8.12/"+name);out.getParentFile().mkdirs();try(FileOutputStream f=new FileOutputStream(out)){bitmap.compress(Bitmap.CompressFormat.PNG,100,f);}}
    void seedQueue(){List<MediaItem> items=PodcastRepository.get(activity).items();queue.add(items.get(0));queue.add(items.get(1));queue.select(0);queue.update("paused","");}
    void assertCompactPanel(AlertDialog dialog){android.util.DisplayMetrics metrics=activity.getResources().getDisplayMetrics();WindowManager.LayoutParams bounds=dialog.getWindow().getAttributes();assertEquals(Math.round(metrics.widthPixels*.70f),bounds.width);assertEquals(Math.round(metrics.heightPixels*.70f),bounds.height);}
    void captureComponent(String name,View view)throws Exception{Bitmap bitmap=Bitmap.createBitmap(view.getWidth(),view.getHeight(),Bitmap.Config.ARGB_8888);view.draw(new Canvas(bitmap));File out=new File("../artifacts/offline-0.8.12/"+name);out.getParentFile().mkdirs();try(FileOutputStream f=new FileOutputStream(out)){bitmap.compress(Bitmap.CompressFormat.PNG,100,f);}}
    @Test public void homeReservesMediaSpaceAndFocusReachesMiniControls()throws Exception{
        seedQueue();FrameLayout[] apps=(FrameLayout[])field(activity,"appTiles");apps[1].requestFocusFromTouch();settle();
        View rail=(View)field(activity,"programScroll"),preview=(View)field(activity,"hdmiPreview"),mini=(View)field(activity,"miniPlayer");assertTrue(rail.getRight()<preview.getLeft());assertNotNull(rail.getClipBounds());assertTrue(preview.getY()+preview.getHeight()<mini.getTop());
        List<FrameLayout> programs=(List<FrameLayout>)field(activity,"programTiles");assertFalse(programs.isEmpty());View cover=programs.get(0);assertSame(preview,cover.focusSearch(View.FOCUS_DOWN));MiniPlayerDock dock=(MiniPlayerDock)mini;assertSame(dock.open,preview.focusSearch(View.FOCUS_DOWN));View pause=dock.open.focusSearch(View.FOCUS_DOWN);assertNotSame(dock.open,pause);assertSame(dock.stop,pause.focusSearch(View.FOCUS_DOWN));
        TextView dockTitle=(TextView)field(dock,"title"),dockState=(TextView)field(dock,"state");assertTrue(dockState.getBottom()<=dockTitle.getTop());assertNotNull(dockTitle.getLayout());assertTrue("The last Chinese title line must fit above transport controls",dockTitle.getLayout().getLineBottom(dockTitle.getLayout().getLineCount()-1)<=dockTitle.getHeight());
        capture("home-native.png",null);queue.update("stopped","");shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(31));assertFalse(dock.showing());assertEquals(0,preview.getTranslationY(),.1f);
    }
    @Test public void libraryFocusAndVideoTabPreserveTruthfulPlaybackControls()throws Exception{
        seedQueue();QueueDialog panel=new QueueDialog(activity);panel.open();settle();LinearLayout rows=(LinearLayout)field(panel,"libraryRows");assertEquals(3,rows.getChildCount());View row=((LinearLayout)rows.getChildAt(0)).getChildAt(0);assertTrue(row.requestFocusFromTouch());settle();TextView title=(TextView)((FrameLayout)row).getChildAt(1);assertEquals(0xFF142D20,title.getCurrentTextColor());assertFalse(title.getText().toString().contains("ep36"));
        capture("queue-native.png",(AlertDialog)field(panel,"dialog"));((TextView)field(panel,"videoTab")).performClick();settle();assertTrue(rows.getChildCount()>0);assertFalse(((TextView)field(panel,"libraryHeading")).getText().toString().contains("聽"));MediaItem video=queue.availableVideos().get(0);queue.select(queue.add(video));queue.update("external","");settle();assertFalse(((View)field(panel,"toggle")).isEnabled());((AlertDialog)field(panel,"dialog")).dismiss();
    }
    @Test public void controlCenterRowsStayWithinGlassAndFocusedLabelsRemainDark()throws Exception{
        ((FrameLayout[])field(activity,"appTiles"))[1].requestFocusFromTouch();settle();((View)field(activity,"controlBadge")).performClick();settle();
        FrameLayout controls=(FrameLayout)field(activity,"controls");
        for(String name:new String[]{"profile","power","wifi","audio","timer","input","settings","cast"}){
            View row=(View)field(activity,name);assertTrue(name+" stays within the panel",row.getLeft()>=0&&row.getRight()<=controls.getWidth()&&row.getBottom()<=controls.getHeight());assertTrue(row.requestFocusFromTouch());
            TextView label=(TextView)row.findViewWithTag("label");assertTrue("Pale focus needs a readable dark label",Color.luminance(label.getCurrentTextColor())<.15);
        }
        ((View)field(activity,"profile")).requestFocusFromTouch();capture("control-center-native.png",null);captureComponent("control-center-detail.png",controls);
    }
    @Test public void managementTargetsItemIdentityAndClearRequiresConfirmation()throws Exception{
        seedQueue();QueueDialog panel=new QueueDialog(activity);panel.open();settle();MediaItem original=queue.items().get(0);AlertDialog main=(AlertDialog)field(panel,"dialog");
        Method manage=QueueDialog.class.getDeclaredMethod("manage");manage.setAccessible(true);manage.invoke(panel);AlertDialog manager=(AlertDialog)org.robolectric.shadows.ShadowDialog.getLatestDialog();assertCompactPanel(manager);
        View chosen=manager.getWindow().getDecorView().findViewWithTag("controlMenu:queue-item:"+original.id);assertNotNull(chosen);assertTrue(chosen.performClick());
        AlertDialog options=(AlertDialog)org.robolectric.shadows.ShadowDialog.getLatestDialog();assertNotSame(manager,options);assertNotNull(options.getWindow().getDecorView().findViewWithTag("controlMenuRoot"));assertTrue(manager.isShowing());assertTrue(main.isShowing());
        assertTrue(options.getWindow().getDecorView().findViewWithTag("controlMenuClose").performClick());assertFalse(options.isShowing());assertTrue("Closing item options returns to management",manager.isShowing());assertTrue(chosen.performClick());
        options=(AlertDialog)org.robolectric.shadows.ShadowDialog.getLatestDialog();queue.move(0,1);assertTrue(options.getWindow().getDecorView().findViewWithTag("controlMenu:queue-remove").performClick());
        assertEquals(1,queue.items().size());assertNotEquals("A moved item is still removed by its stable ID",original.id,queue.items().get(0).id);assertFalse(options.isShowing());assertTrue(main.isShowing());
        manager=(AlertDialog)org.robolectric.shadows.ShadowDialog.getLatestDialog();assertTrue("Management refreshes after an item action",manager.isShowing());assertTrue(manager.getWindow().getDecorView().findViewWithTag("controlMenuClose").performClick());assertTrue(main.isShowing());
        Method clear=QueueDialog.class.getDeclaredMethod("confirmClear");clear.setAccessible(true);clear.invoke(panel);AlertDialog confirm=(AlertDialog)org.robolectric.shadows.ShadowDialog.getLatestDialog();assertEquals(1,queue.items().size());
        assertTrue(confirm.getWindow().getDecorView().findViewWithTag("controlMenu:queue-keep").performClick());shadowOf(Looper.getMainLooper()).idle();assertEquals(1,queue.items().size());assertFalse(confirm.isShowing());
        clear.invoke(panel);confirm=(AlertDialog)org.robolectric.shadows.ShadowDialog.getLatestDialog();assertTrue(confirm.getWindow().getDecorView().findViewWithTag("controlMenu:queue-clear").performClick());shadowOf(Looper.getMainLooper()).idle();assertTrue(queue.items().isEmpty());assertFalse(confirm.isShowing());assertTrue(main.isShowing());main.dismiss();
    }
    @Test public void assistantOfflinePreviewKeepsVoiceExplicitAndModelDetailsHidden()throws Exception{
        AssistantPanel panel=new AssistantPanel(activity);set(panel,"lastUser","想找一部輕鬆一點的電影。");set(panel,"lastAnswer","好啊。想笑一笑，還是看個溫暖的故事？");panel.open();settle();assertFalse(panel.online());assertEquals(View.GONE,((View)field(panel,"connection")).getVisibility());assertTrue(((TextView)field(panel,"mic")).getText().toString().contains("離線"));assertEquals(0xFF8FCF98,((TextView)field(panel,"said")).getCurrentTextColor());assertTrue(((TextView)field(panel,"status")).getText().toString().contains("配對"));((View)field(panel,"mic")).requestFocusFromTouch();capture("meadow-offline-native.png",(AlertDialog)field(panel,"dialog"));
        // Matched-state visual fixture only: never creates a voice peer or contacts Codex.
        set(panel,"connected",true);Method render=AssistantPanel.class.getDeclaredMethod("renderConnection");render.setAccessible(true);render.invoke(panel);((TextView)field(panel,"orbLabel")).setText("等你開口");((TextView)field(panel,"status")).setText("連線完成，可以開始說話。");assertNull(field(panel,"voice"));settle();capture("meadow-conversation-native.png",(AlertDialog)field(panel,"dialog"));panel.close();
    }
    @Test public void weatherFixtureHasACompactWeekAndIndependentFullWidthCities()throws Exception{
        WeatherLayoutTest.seedForecasts(activity);((FrameLayout[])field(activity,"appTiles"))[1].requestFocusFromTouch();settle();WeatherDialog panel=new WeatherDialog(activity,new WeatherRepository.Reading("台北","22°  有雨",61,true));panel.open();settle();AlertDialog dialog=(AlertDialog)field(panel,"dialog");
        View week=(View)field(panel,"weeklyPanel");assertEquals(1344,week.getLayoutParams().width);assertEquals(756,week.getLayoutParams().height);assertEquals(7,((LinearLayout)field(panel,"daysRow")).getChildCount());assertEquals("22°",((TextView)field(panel,"temperature")).getText().toString());capture("weather-native.png",dialog);
        ((View)field(panel,"otherCities")).performClick();settle();FrameLayout[] cities=(FrameLayout[])field(panel,"cities");assertEquals(9,cities.length);assertTrue(cities[4].requestFocusFromTouch());settle();capture("weather-cities-native.png",dialog);dialog.dismiss();
    }
    @Test public void pausedPlayerFixtureKeepsLongChineseTitleWithinItsTextBox()throws Exception{
        seedQueue();queue.current().title="下班後，留一點時間給自己：一起聊聊最近的生活、放鬆與重新出發";((FrameLayout[])field(activity,"appTiles"))[1].requestFocusFromTouch();settle();PlayerDialog panel=new PlayerDialog(activity);panel.open();settle();AlertDialog dialog=(AlertDialog)field(panel,"dialog");assertCompactPanel(dialog);capture("player-paused-native.png",dialog);
        TextView title=(TextView)field(panel,"title");assertNotNull(title.getLayout());assertTrue("The final Chinese player-title line must fit",title.getLayout().getLineBottom(title.getLayout().getLineCount()-1)<=title.getHeight());assertEquals("paused",queue.state);dialog.dismiss();
    }
}
