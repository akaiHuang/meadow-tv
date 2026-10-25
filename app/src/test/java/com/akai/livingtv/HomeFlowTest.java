package com.akai.livingtv;

import android.app.*;
import android.graphics.*;
import android.os.*;
import android.view.*;
import android.widget.*;
import java.io.*;
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

@RunWith(RobolectricTestRunner.class) @Config(sdk=28,qualifiers="w1280dp-h720dp-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
public class HomeFlowTest {
    ActivityController<MainActivity> controller;MainActivity activity;
    @Before public void setup()throws Exception{TestArtworkFixtures.installCatalog(RuntimeEnvironment.getApplication());RuntimeEnvironment.getApplication().getSharedPreferences("living",0).edit().putLong("weather0At",System.currentTimeMillis()).putString("weather0","{\"temperature_2m\":24,\"weather_code\":2,\"is_day\":1}").commit();controller=Robolectric.buildActivity(MainActivity.class).setup();activity=controller.get();layout();}
    @After public void close(){controller.pause().stop().destroy();}
    Object field(String n)throws Exception{Field f=MainActivity.class.getDeclaredField(n);f.setAccessible(true);return f.get(activity);}
    void invoke(String n,Class<?>[] types,Object...args)throws Exception{Method m=MainActivity.class.getDeclaredMethod(n,types);m.setAccessible(true);m.invoke(activity,args);}
    void settle()throws Exception{for(int i=0;i<4;i++){Thread.sleep(80);shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(400));}layout();}
    void layout(){View v=activity.getWindow().getDecorView();v.measure(View.MeasureSpec.makeMeasureSpec(1920,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(1080,View.MeasureSpec.EXACTLY));v.layout(0,0,1920,1080);}
    @SuppressWarnings("unchecked") List<FrameLayout> programs()throws Exception{return (List<FrameLayout>)field("programTiles");}
    @Test public void emptyYouTubeDoesNotBorrowOtherProviders()throws Exception{settle();assertEquals(0,programs().size());assertEquals(View.INVISIBLE,((View)field("controls")).getVisibility());assertEquals("",((TextView)field("heroMeta")).getText().toString());}
    @Test public void missingProviderUsesANeutralNativeLabelWithoutLoadingALogoBitmap()throws Exception{
        FrameLayout apple=((FrameLayout[])field("appTiles"))[3];ImageView banner=apple.findViewWithTag("appBanner");View face=apple.findViewWithTag("appFace");
        assertNotNull(banner);assertTrue("Only the artwork face should clip its rounded corners",face.getClipToOutline());assertFalse("The focus light must be able to extend beyond the artwork",apple.getClipToOutline());assertFalse(apple.getClipChildren());
        assertNull("The public build must not decode a bundled provider logo",banner.getDrawable());assertEquals(View.GONE,banner.getVisibility());
        TextView label=apple.findViewWithTag("appFallbackLabel");assertNotNull(label);assertEquals("Apple TV",label.getText().toString());assertEquals(2,label.getMaxLines());
        assertEquals("The neutral face remains a landscape TV card",16f/9f,apple.getWidth()/(float)apple.getHeight(),.015f);
    }
    @Test public void controlCenterAlignsToBothHeaderActionsWithoutMovingEitherAction()throws Exception{
        View meadow=(View)field("assistantBadge"),badge=(View)field("controlBadge"),panel=(View)field("controls");
        assertEquals("The panel starts directly below the Meadow capsule",meadow.getLeft(),panel.getLeft());assertEquals("The panel ends at the control-center capsule",badge.getRight(),panel.getRight());assertTrue(panel.getTop()>meadow.getBottom());
    }
    @Test public void weatherCapsuleFitsItsContentsAndShrinksAfterALongerStatus()throws Exception{
        View pill=(View)field("weatherBadge");TextView value=(TextView)field("weatherText");
        invoke("paintWeather",new Class<?>[]{WeatherRepository.Reading.class},new WeatherRepository.Reading("台北","天氣暫不可用",-1,true));layout();int longWidth=pill.getWidth();
        invoke("paintWeather",new Class<?>[]{WeatherRepository.Reading.class},new WeatherRepository.Reading("台北","22°  有雨",61,true));layout();
        assertTrue("A short reading must not retain the unavailable-status width",pill.getWidth()<longWidth);
        float textWidth=value.getPaint().measureText(value.getText().toString());int trailing=pill.getWidth()-value.getLeft()-Math.round(textWidth);
        assertTrue("The city and temperature need enough room without truncation",value.getWidth()>=Math.ceil(textWidth));assertTrue("The capsule should have a compact, intentional trailing inset",trailing>=19&&trailing<=32);
    }
    @Test public void hdmiFallbackHasOneSourceTitleAndOneActionInsteadOfDuplicatedCaptions()throws Exception{
        HdmiPreview preview=(HdmiPreview)field("hdmiPreview");List<String> text=new ArrayList<>();collectText(preview,text);
        assertEquals(1,Collections.frequency(text,"第四台"));assertEquals(1,Collections.frequency(text,"選擇輸入來源"));assertFalse(text.stream().anyMatch(t->t.contains("點選")));assertFalse(text.stream().anyMatch(t->t.contains("尚未驗證")));
    }
    private void collectText(View view,List<String> text){if(view instanceof TextView)text.add(((TextView)view).getText().toString());if(view instanceof ViewGroup){ViewGroup group=(ViewGroup)view;for(int i=0;i<group.getChildCount();i++)collectText(group.getChildAt(i),text);}}
    @Test public void podcastSelectionKeepsAnAmbientBackgroundInsteadOfEnlargingItsLogo()throws Exception{settle();((FrameLayout[])field("appTiles"))[4].requestFocusFromTouch();settle();settle();assertFalse(programs().isEmpty());assertTrue(((String)field("showingArtwork")).startsWith("ambient:"));}
    @Test public void podcastHomeRecommendationsRefreshFromTheLocalLibraryAfterFollowingAShow()throws Exception{
        settle();PodcastRepository podcasts=PodcastRepository.get(activity);String id="show:apple:1477164549";PodcastShow guaji=podcasts.show(id);assertNotNull("The requested Guaji show must be in the public seed",guaji);
        boolean followed=guaji.followed,personalized=podcasts.personalizationEnabled();
        try{
            podcasts.setPersonalizationEnabled(true);((FrameLayout[])field("appTiles"))[4].requestFocusFromTouch();settle();assertTrue(podcasts.follow(id));
            invoke("updatePrograms",new Class<?>[]{boolean.class},false);settle();
            List<FeedRepository.Entry> visible=(List<FeedRepository.Entry>)field("visiblePrograms");List<PodcastShow> expected=podcasts.recommendations();assertEquals(expected.size(),visible.size());
            for(int index=0;index<expected.size();index++)assertEquals("Home follows the current local ranking",expected.get(index).title,visible.get(index).title);
            assertTrue(visible.stream().anyMatch(entry->entry.title.equals("呱吉")));
        }finally{if(!followed)podcasts.unfollow(id);podcasts.setPersonalizationEnabled(personalized);}
    }
    @Test public void landscapeRailScrollsBeyondFourAndTracksArtwork()throws Exception{
        settle();List<FeedRepository.Entry> list=(List<FeedRepository.Entry>)field("entries");FeedRepository.Entry original=fixtureCard("netflix",601,320,180);list.clear();for(int i=0;i<9;i++){FeedRepository.Entry e=FeedRepository.Entry.parse(original.json());e.id=100+i;e.wide=false;list.add(e);}
        assertTrue("TV D-pad focus could not enter Netflix",((FrameLayout[])field("appTiles"))[1].requestFocusFromTouch());settle();assertEquals(9,programs().size());FrameLayout ninth=programs().get(8);assertTrue(ninth.requestFocusFromTouch());settle();
        assertTrue(ninth.hasFocus());assertTrue(ninth.isSelected());assertEquals(380,ninth.getLayoutParams().width);assertEquals(214,ninth.getLayoutParams().height);assertEquals(ImageView.ScaleType.FIT_CENTER,((ImageView)ninth.getChildAt(0)).getScaleType());assertTrue(((HorizontalScrollView)field("programScroll")).getScrollX()>0);
        assertEquals("近期與精選節目",((TextView)field("heroMeta")).getText().toString());capture("native-rail-simulation.png");
    }
    private FeedRepository.Entry fixtureCard(String provider,int id,int width,int height)throws Exception{
        List<FeedRepository.Entry> entries=(List<FeedRepository.Entry>)field("entries");FeedRepository.Entry base=entries.stream().filter(e->e.provider.equals(provider)).findFirst().get();FeedRepository.Entry entry=FeedRepository.Entry.parse(base.json());
        entry.id=id;entry.title="Artwork ratio "+id;entry.poster="https://cards.nflximg.net/ratio-"+id+".png";entry.wide=true;
        Bitmap bitmap=Bitmap.createBitmap(width,height,Bitmap.Config.ARGB_8888);bitmap.eraseColor(Color.CYAN);Canvas canvas=new Canvas(bitmap);Paint paint=new Paint();paint.setColor(Color.RED);canvas.drawRect(0,0,width*.1f,height,paint);paint.setColor(Color.BLUE);canvas.drawRect(width*.9f,0,width,height,paint);
        byte[] digest=java.security.MessageDigest.getInstance("SHA-256").digest(entry.poster.getBytes(java.nio.charset.StandardCharsets.UTF_8));StringBuilder key=new StringBuilder();for(byte b:digest)key.append(String.format(java.util.Locale.ROOT,"%02x",b));File cache=new File(activity.getCacheDir(),"art");cache.mkdirs();try(FileOutputStream out=new FileOutputStream(new File(cache,key.toString()))){bitmap.compress(Bitmap.CompressFormat.PNG,100,out);}bitmap.recycle();return entry;
    }
    @Test public void recommendationCardsUseDecodedPortraitLandscapeAndSquareRatiosWithoutCropping()throws Exception{
        settle();FeedRepository.Entry portrait=fixtureCard("netflix",611,120,180),landscape=fixtureCard("netflix",612,320,180),square=fixtureCard("netflix",613,160,160);
        List<FeedRepository.Entry> entries=(List<FeedRepository.Entry>)field("entries");entries.clear();entries.add(portrait);entries.add(landscape);entries.add(square);
        ((FrameLayout[])field("appTiles"))[1].requestFocusFromTouch();settle();settle();int[][] expected={{178,267},{380,214},{267,267}};
        for(int i=0;i<3;i++){
            FrameLayout card=programs().get(i);ImageView image=(ImageView)card.getChildAt(0);assertNotNull(image.getDrawable());assertEquals(expected[i][0],card.getWidth());assertEquals(expected[i][1],card.getHeight());assertEquals(ImageView.ScaleType.FIT_CENTER,image.getScaleType());
            Bitmap painted=Bitmap.createBitmap(card.getWidth(),card.getHeight(),Bitmap.Config.ARGB_8888);image.draw(new Canvas(painted));int left=painted.getPixel(4,card.getHeight()/2),right=painted.getPixel(card.getWidth()-5,card.getHeight()/2);
            assertTrue("The left strip of the source cover must remain visible",Color.red(left)>220&&Color.blue(left)<30);assertTrue("The right strip of the source cover must remain visible",Color.blue(right)>220&&Color.red(right)<30);painted.recycle();
        }
    }
    @Test public void delayedPosterFromPreviousProviderCannotResizeOrReplaceTheReusedCard()throws Exception{
        settle();FeedRepository.Entry old=fixtureCard("netflix",621,120,180),current=fixtureCard("hbo",622,320,180);List<FeedRepository.Entry> entries=(List<FeedRepository.Entry>)field("entries");entries.clear();entries.add(old);entries.add(current);
        ((FeedRepository)field("repository")).close();FeedRepository images=new FeedRepository(activity);Field repository=MainActivity.class.getDeclaredField("repository");repository.setAccessible(true);repository.set(activity,images);
        Field workerField=FeedRepository.class.getDeclaredField("images");workerField.setAccessible(true);java.util.concurrent.ThreadPoolExecutor workers=(java.util.concurrent.ThreadPoolExecutor)workerField.get(images);
        java.util.concurrent.CountDownLatch entered=new java.util.concurrent.CountDownLatch(2),release=new java.util.concurrent.CountDownLatch(1);
        Runnable hold=()->{entered.countDown();try{release.await();}catch(InterruptedException interrupted){Thread.currentThread().interrupt();}};
        try{
            workers.execute(hold);workers.execute(hold);assertTrue(entered.await(5,java.util.concurrent.TimeUnit.SECONDS));FrameLayout[] apps=(FrameLayout[])field("appTiles");apps[1].requestFocusFromTouch();settle();FrameLayout reused=programs().get(0);apps[2].requestFocusFromTouch();settle();assertSame(reused,programs().get(0));release.countDown();settle();settle();
            ImageView image=(ImageView)reused.getChildAt(0);assertEquals(current.title,reused.getContentDescription());assertNotNull(image.getDrawable());assertEquals(380,reused.getWidth());assertEquals(214,reused.getHeight());Bitmap bitmap=((android.graphics.drawable.BitmapDrawable)image.getDrawable()).getBitmap();assertEquals(320,bitmap.getWidth());assertEquals(180,bitmap.getHeight());
        }finally{release.countDown();}
    }
    @Test public void reusedCardsFollowTheNewProviderAndKeepTheCorrectClickTarget()throws Exception{
        settle();FrameLayout[] apps=(FrameLayout[])field("appTiles");apps[1].requestFocusFromTouch();settle();
        FrameLayout first=programs().get(0);String netflix=first.getContentDescription().toString();
        apps[2].requestFocusFromTouch();settle();assertSame("Switching providers should reuse native cards",first,programs().get(0));
        assertNotEquals(netflix,first.getContentDescription().toString());first.performClick();settle();
        AlertDialog dialog=(AlertDialog)org.robolectric.shadows.ShadowDialog.getLatestDialog();
        View openProgram=dialog.getWindow().getDecorView().findViewWithTag("controlMenu:program-open");TextView openLabel=openProgram.findViewWithTag("menuLabel");assertEquals("Reused card must open the newly selected provider","前往 HBO Max",openLabel.getText().toString());dialog.dismiss();
        apps[0].requestFocusFromTouch();settle();assertEquals(0,programs().size());assertEquals(View.GONE,first.getVisibility());
        apps[1].requestFocusFromTouch();settle();assertSame(first,programs().get(0));assertEquals(netflix,first.getContentDescription().toString());assertTrue(first.isFocusable());
    }
    @Test public void fastDownUsesTheFinalProviderAndTrimmedHomeLoadsAgain()throws Exception{
        settle();FrameLayout[] apps=(FrameLayout[])field("appTiles");apps[1].requestFocusFromTouch();apps[2].requestFocusFromTouch();
        activity.dispatchKeyEvent(new KeyEvent(KeyEvent.ACTION_DOWN,KeyEvent.KEYCODE_DPAD_DOWN));settle();
        assertEquals(2,field("appIndex"));assertFalse(programs().isEmpty());
        List<FeedRepository.Entry> visible=(List<FeedRepository.Entry>)field("visiblePrograms");assertTrue(visible.stream().allMatch(e->e.provider.equals("hbo")));
        controller.pause().stop();activity.onTrimMemory(android.content.ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN);
        assertNull(((ImageView)programs().get(0).getChildAt(0)).getDrawable());
        controller.start().resume();settle();settle();assertNotNull("Returning Home must restore covers after trimming",((ImageView)programs().get(0).getChildAt(0)).getDrawable());
    }
    @Test public void artworkToggleAndCancelledFadeDoNotKeepThePreviousProvider()throws Exception{
        settle();FrameLayout[] apps=(FrameLayout[])field("appTiles");apps[1].requestFocusFromTouch();settle();settle();
        String committed=(String)field("showingArtwork");assertFalse(committed.isEmpty());
        Bitmap pending=Bitmap.createBitmap(20,20,Bitmap.Config.RGB_565);
        invoke("crossfade",new Class<?>[]{Bitmap.class,String.class},pending,"uncommitted-test-art");
        ((android.animation.AnimatorSet)field("backgroundFade")).cancel();assertEquals(committed,field("showingArtwork"));
        activity.getSharedPreferences("living",0).edit().putBoolean("artwork",false).commit();apps[2].requestFocusFromTouch();settle();
        activity.getSharedPreferences("living",0).edit().putBoolean("artwork",true).commit();invoke("updatePrograms",new Class<?>[]{boolean.class},false);settle();settle();
        List<FeedRepository.Entry> visible=(List<FeedRepository.Entry>)field("visiblePrograms");FeedRepository.Entry selected=visible.get((int)field("artIndex"));
        assertEquals(selected.backdrop.isEmpty()?selected.poster:selected.backdrop,field("showingArtwork"));
    }
    @Test public void refreshedCatalogKeepsSelectionAlignedWhenFocusedTitleIsRemoved()throws Exception{
        settle();((FrameLayout[])field("appTiles"))[1].requestFocusFromTouch();settle();assertTrue(programs().size()>2);
        assertTrue(programs().get(2).requestFocusFromTouch());settle();assertTrue("Precondition: third card has focus",programs().get(2).hasFocus());
        // Robolectric shadows Activity#getCurrentFocus with a separate field.
        shadowOf(activity).setCurrentFocus(programs().get(2));
        List<FeedRepository.Entry> list=(List<FeedRepository.Entry>)field("entries");
        int removed=((List<FeedRepository.Entry>)field("visiblePrograms")).get(2).id;list.removeIf(e->e.id==removed);invoke("updatePrograms",new Class<?>[]{boolean.class},true);assertTrue("Refresh must keep immediate focus",programs().contains(activity.getCurrentFocus()));settle();
        int index=programs().indexOf(activity.getCurrentFocus());assertTrue("Focus must stay in the refreshed rail",index>=0);assertTrue("Focused card must also be selected",programs().get(index).isSelected());assertEquals(index,field("artIndex"));
    }
    @Test public void controlCenterTrapsFocusAndProfileSwitchIsOneAction()throws Exception{
        settle();((View)field("controlBadge")).performClick();settle();assertEquals(View.VISIBLE,((View)field("controls")).getVisibility());assertFalse(((FrameLayout[])field("appTiles"))[0].isFocusable());
        HorizontalScrollView rail=(HorizontalScrollView)field("appScroll");assertNotNull("Expanded controls should not overlap another app banner",rail.getClipBounds());assertTrue(rail.getLeft()+rail.getClipBounds().right<((View)field("controls")).getLeft());
        View power=(View)field("power"),wifi=(View)field("wifi"),input=(View)field("input"),settings=(View)field("settings");assertTrue(power.getRight()<wifi.getLeft());assertEquals(wifi.getId(),power.getNextFocusRightId());assertEquals(settings.getId(),input.getNextFocusRightId());assertTrue(((View)field("controls")).getHeight()<700);capture("native-control-simulation.png");
        ((View)field("profile")).performClick();settle();AlertDialog d=(AlertDialog)org.robolectric.shadows.ShadowDialog.getLatestDialog();assertNull("Profile uses the shared capsule menu rather than an Android list",d.getListView());View selected=d.getWindow().getDecorView().findViewWithTag("controlMenu:profile-current");assertNotNull(selected);assertTrue(selected.requestFocusFromTouch());TextView label=selected.findViewWithTag("menuLabel");assertNotNull(label);assertTrue("Selected glass row needs a dark caption",Color.luminance(label.getCurrentTextColor())<.2f);selected.performClick();settle();assertEquals("訪客",activity.getSharedPreferences("living",0).getString("profile",""));assertFalse("One activation switches the profile and closes its menu",d.isShowing());activity.onBackPressed();settle();assertEquals(View.INVISIBLE,((View)field("controls")).getVisibility());assertNull("Closing controls must restore the full app rail",rail.getClipBounds());
    }
    @Test public void selectedAppRendersItsOwnBackground()throws Exception{
        settle();assertTrue(((FrameLayout[])field("appTiles"))[1].requestFocusFromTouch());settle();assertTrue(programs().size()>0);assertTrue(programs().get(0).isSelected());
        settle();ImageView background=(ImageView)field("backdrop");assertNotNull("Selected program artwork was not loaded",background.getDrawable());assertTrue("Crossfade must leave a visible background",background.getAlpha()>0.5f);capture("native-home-simulation.png");
        ((View)field("controlBadge")).performClick();settle();assertEquals("Control-center focus must preserve selected provider",1,field("appIndex"));capture("native-glass-simulation.png");
    }
    @Test public void controlCenterBlursAndDimsHomeWhileKeepingItsActionsAboveTheBackdrop()throws Exception{
        settle();Bitmap stripes=Bitmap.createBitmap(320,180,Bitmap.Config.ARGB_8888);Canvas canvas=new Canvas(stripes);Paint paint=new Paint();
        for(int x=0;x<320;x++){paint.setColor((x/4)%2==0?Color.WHITE:Color.BLACK);canvas.drawRect(x,0,x+1,180,paint);}
        ImageView background=(ImageView)field("backdrop");background.setImageBitmap(stripes);background.setAlpha(1);((ImageView)field("incomingBackdrop")).setAlpha(0);
        FrameLayout stage=(FrameLayout)field("stage");Bitmap before=Bitmap.createBitmap(1920,1080,Bitmap.Config.ARGB_8888);stage.draw(new Canvas(before));
        ((View)field("controlBadge")).performClick();settle();View frost=(View)field("controlBackdrop");assertNotNull(frost.getBackground());assertEquals(View.VISIBLE,frost.getVisibility());assertFalse(frost.isFocusable());
        assertTrue("Only home is covered; control-center actions remain crisp above the blur",stage.indexOfChild((View)field("controls"))>stage.indexOfChild(frost));
        Bitmap after=Bitmap.createBitmap(1920,1080,Bitmap.Config.ARGB_8888);stage.draw(new Canvas(after));
        double oldEdges=0,newEdges=0,oldLight=0,newLight=0;
        for(int x=100;x<400;x++){oldLight+=Color.red(before.getPixel(x,900));newLight+=Color.red(after.getPixel(x,900));oldEdges+=Math.abs(Color.red(before.getPixel(x,900))-Color.red(before.getPixel(x+1,900)));newEdges+=Math.abs(Color.red(after.getPixel(x,900))-Color.red(after.getPixel(x+1,900)));}
        assertTrue("The home image must visibly soften, not just acquire a dark overlay",newEdges<oldEdges*.25);
        assertTrue("The captured home must also become darker",newLight<oldLight*.8);
        android.graphics.drawable.Drawable firstScene=frost.getBackground();Bitmap loaded=Bitmap.createBitmap(320,180,Bitmap.Config.ARGB_8888);loaded.eraseColor(Color.rgb(40,100,160));
        invoke("crossfade",new Class<?>[]{Bitmap.class,String.class},loaded,"loaded-after-opening-controls");settle();
        assertNotSame("Artwork arriving after opening must refresh the snapshot once",firstScene,frost.getBackground());
        stage.draw(new Canvas(after));assertTrue("The refreshed blur follows the new blue artwork",Color.blue(after.getPixel(150,900))>Color.red(after.getPixel(150,900)));
        activity.onBackPressed();settle();assertEquals(View.INVISIBLE,frost.getVisibility());assertNull("Closing releases the captured scene",frost.getBackground());
        before.recycle();after.recycle();stripes.recycle();
    }
    @Test public void rapidControlReopenAndLifecycleReturnCannotLeaveAStaleBackdrop()throws Exception{
        settle();View badge=(View)field("controlBadge");badge.performClick();activity.onBackPressed();badge.performClick();settle();
        View frost=(View)field("controlBackdrop");assertEquals(View.VISIBLE,frost.getVisibility());assertNotNull(frost.getBackground());assertTrue((Boolean)field("controlsOpen"));
        controller.pause().stop();assertEquals(View.INVISIBLE,frost.getVisibility());assertNull(frost.getBackground());controller.start().resume();settle();assertEquals(View.VISIBLE,frost.getVisibility());assertNotNull("Returning from system settings refreshes the home snapshot",frost.getBackground());
        assertTrue("The backdrop intercepts pointer clicks instead of opening apps behind the controls",frost.isClickable());frost.performClick();settle();assertEquals(View.INVISIBLE,frost.getVisibility());assertNull(frost.getBackground());assertTrue(((View)field("controlBadge")).isFocusable());
    }
    @Test public void hdmiPreviewFallsBackWithoutHardwareAndSuspendsBehindControls()throws Exception{
        settle();HdmiPreview preview=(HdmiPreview)field("hdmiPreview");assertFalse("No hardware session should be running without an HDMI input",preview.isRunning());assertEquals(View.VISIBLE,preview.getVisibility());
        ((View)field("controlBadge")).performClick();settle();assertEquals(View.INVISIBLE,preview.getVisibility());assertFalse(preview.isRunning());activity.onBackPressed();settle();assertEquals(View.VISIBLE,preview.getVisibility());
        preview.performClick();android.content.Intent next=shadowOf(activity).getNextStartedActivity();assertNotNull(next);assertEquals("inputs",next.getStringExtra("action"));assertEquals(MainActivity.class.getName(),next.getComponent().getClassName());
    }
    @Test public void unpairedAssistantCannotSendOrExecute()throws Exception{assertFalse(new AssistantClient(activity).configured());assertTrue(AssistantClient.privateHost("192.168.50.2"));assertFalse(AssistantClient.privateHost("example.com"));assertFalse(AssistantClient.privateHost("192.168.999.2"));}
    @Test public void downNavigationReachesPreviewAndHomeCollapsesDialogsWithoutStartingHdmi()throws Exception{
        settle();FrameLayout[] apps=(FrameLayout[])field("appTiles");View preview=(View)field("hdmiPreview");assertEquals(preview.getId(),apps[0].getNextFocusDownId());assertTrue(apps[1].requestFocusFromTouch());settle();assertEquals(preview.getId(),programs().get(0).getNextFocusDownId());
        ((View)field("controlBadge")).performClick();settle();((View)field("profile")).performClick();settle();AlertDialog dialog=(AlertDialog)org.robolectric.shadows.ShadowDialog.getLatestDialog();assertTrue(dialog.isShowing());RemoteService remote=Robolectric.buildService(RemoteService.class).create().get();assertTrue(remote.onKeyEvent(new KeyEvent(KeyEvent.ACTION_UP,KeyEvent.KEYCODE_HOME)));android.content.Intent home=shadowOf(remote).getNextStartedActivity();assertTrue(home.hasCategory(android.content.Intent.CATEGORY_HOME));assertFalse(home.getBooleanExtra("fromShortcut",false));activity.onNewIntent(home);assertFalse(dialog.isShowing());assertTrue(home.getBooleanExtra("fromShortcut",false));
    }
    @Test public void assistantEntryConnectsOnceOnOpenAndCanCollapseWithoutDisconnecting()throws Exception{
        java.net.ServerSocket local=new java.net.ServerSocket(0,1,java.net.InetAddress.getLoopbackAddress());local.setSoTimeout(1500);java.util.concurrent.atomic.AtomicInteger connections=new java.util.concurrent.atomic.AtomicInteger();
        java.util.concurrent.CountDownLatch release=new java.util.concurrent.CountDownLatch(1);Thread server=new Thread(()->{try(java.net.Socket socket=local.accept()){connections.incrementAndGet();release.await(3,java.util.concurrent.TimeUnit.SECONDS);}catch(Exception ignored){}});server.setDaemon(true);server.start();
        activity.getSharedPreferences("living",0).edit().putString("bridgeEndpoint","https://127.0.0.1:"+local.getLocalPort()).putString("bridgePin",new String(new char[64]).replace('\0','a')).putString("bridgeToken","synthetic-local-test").commit();
        AssistantPanel panel=new AssistantPanel(activity);panel.open();layout();shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(200));
        Field f=AssistantPanel.class.getDeclaredField("mic");f.setAccessible(true);TextView start=(TextView)f.get(panel);assertEquals("連線中",start.getText().toString());
        start.performClick();assertEquals("連線中",start.getText().toString());for(int i=0;i<20&&connections.get()==0;i++)Thread.sleep(25);assertEquals(1,connections.get());Field connected=AssistantPanel.class.getDeclaredField("connected");connected.setAccessible(true);connected.setBoolean(panel,true);panel.hidePanel();assertTrue("Collapsing to Home must retain the connection",connected.getBoolean(panel));panel.close();release.countDown();local.close();
    }
    @Test public void assistantIdleReturnsToDisconnectedStateAfterThreeMinutes()throws Exception{
        AssistantPanel panel=new AssistantPanel(activity);panel.open();Field connected=AssistantPanel.class.getDeclaredField("connected");connected.setAccessible(true);connected.setBoolean(panel,true);Method touch=AssistantPanel.class.getDeclaredMethod("touch");touch.setAccessible(true);touch.invoke(panel);
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(179));assertTrue(connected.getBoolean(panel));shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(2));assertFalse(connected.getBoolean(panel));
        Field mic=AssistantPanel.class.getDeclaredField("mic");mic.setAccessible(true);assertEquals("● 離線",((TextView)mic.get(panel)).getText().toString());panel.close();
    }
    void capture(String name)throws Exception{View v=activity.getWindow().getDecorView();Bitmap bitmap=Bitmap.createBitmap(1920,1080,Bitmap.Config.ARGB_8888);v.draw(new Canvas(bitmap));File f=new File("../artifacts/"+name);f.getParentFile().mkdirs();try(FileOutputStream out=new FileOutputStream(f)){bitmap.compress(Bitmap.CompressFormat.PNG,100,out);}}
}
