package com.akai.livingtv;

import android.accessibilityservice.AccessibilityService;
import android.app.Application;
import android.content.*;
import android.content.pm.*;
import android.graphics.Rect;
import android.os.*;
import android.view.KeyEvent;
import android.view.accessibility.AccessibilityNodeInfo;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;
import org.json.*;
import org.junit.*;
import org.junit.runner.RunWith;
import org.robolectric.*;
import org.robolectric.android.controller.ServiceController;
import org.robolectric.annotation.*;
import org.robolectric.shadows.ShadowAccessibilityService;
import static org.junit.Assert.*;
import static org.robolectric.Shadows.shadowOf;
import static com.akai.livingtv.TvUiSnapshot.jsonObject;

@RunWith(RobolectricTestRunner.class) @Config(sdk=28,shadows=TvAndroidToolsTest.UiServiceShadow.class)
@LooperMode(LooperMode.Mode.PAUSED)
public class TvAndroidToolsTest {
    @Implements(AccessibilityService.class)
    public static class UiServiceShadow extends ShadowAccessibilityService {
        static AccessibilityNodeInfo activeRoot;
        @Implementation protected AccessibilityNodeInfo getRootInActiveWindow(){return activeRoot==null?null:AccessibilityNodeInfo.obtain(activeRoot);}
    }
    private Application app;
    private ServiceController<RemoteService> controller;
    private RemoteService service;
    private AccessibilityNodeInfo root,button;
    @Before public void setup(){
        app=RuntimeEnvironment.getApplication();app.getSharedPreferences("living",0).edit().clear().commit();
        controller=Robolectric.buildService(RemoteService.class).create();service=controller.get();service.onServiceConnected();
        root=node("",false);root.setClassName("android.widget.FrameLayout");button=node("搜尋",true);shadowOf(root).addChild(button);UiServiceShadow.activeRoot=root;
    }
    @After public void close(){UiServiceShadow.activeRoot=null;controller.destroy();}
    private AccessibilityNodeInfo node(String text,boolean click){AccessibilityNodeInfo n=AccessibilityNodeInfo.obtain();n.setPackageName("com.example.video");n.setClassName("android.widget.Button");n.setText(text);n.setEnabled(true);n.setVisibleToUser(true);n.setBoundsInScreen(new Rect(100,100,350,180));if(click)n.addAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_CLICK);return n;}
    private JSONObject run(JSONObject command){AtomicReference<JSONObject> value=new AtomicReference<>();TvCommandDispatcher.execute(app,command,value::set);shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(500));assertNotNull(value.get());return value.get();}
    private JSONObject command(String operation,TvUiSnapshot state){return jsonObject("action","android","operation",operation,"snapshotId",state.id);}
    private TvUiSnapshot state(){return RemoteService.readSnapshot(app);}
    private String target(TvUiSnapshot snapshot){return snapshot.targets.values().iterator().next().id;}

    @Test public void stateReportsTheActualAppAndOnlyDeclaredNodeActions(){
        button.setFocused(true);TvUiSnapshot snapshot=state();assertEquals("com.example.video",snapshot.data.optString("packageName"));assertTrue(snapshot.data.optJSONObject("permission").optBoolean("accessibilityConnected"));
        TvUiSnapshot.Target item=snapshot.targets.get(snapshot.focused);assertNotNull(item);assertEquals("搜尋",item.text);assertEquals(Collections.singleton("click"),item.actions);assertFalse(snapshot.data.optJSONObject("capabilities").optBoolean("shell"));
    }
    @Test public void passwordTextIsRedactedAndItsEntireScreenIsProtected(){
        AccessibilityNodeInfo password=node("private-test-password",false);password.setPassword(true);password.setContentDescription("private-test-description");password.setEditable(true);password.addAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SET_TEXT);shadowOf(root).addChild(password);
        TvUiSnapshot snapshot=state();assertTrue(snapshot.sensitive);assertFalse(snapshot.data.toString().contains("private-test"));assertFalse(snapshot.data.optJSONObject("capabilities").optBoolean("nodeActions"));
        JSONObject request=command("click",snapshot);try{request.put("targetId",target(snapshot));}catch(JSONException e){throw new AssertionError(e);}JSONObject receipt=run(request);assertFalse(receipt.optBoolean("dispatched"));assertEquals("protected_ui",receipt.optString("errorCode"));
    }
    @Test public void boundedSnapshotMarksTruncationAndFitsTheReceiptBudget(){
        for(int i=0;i<150;i++){AccessibilityNodeInfo child=node("節目"+i+String.join("",Collections.nCopies(120,"影")),true);child.setContentDescription(String.join("",Collections.nCopies(120,"畫")));shadowOf(root).addChild(child);}
        TvUiSnapshot snapshot=state();assertTrue(snapshot.truncated);assertTrue(snapshot.targets.size()<=TvUiSnapshot.MAX_TARGETS);assertTrue("Two full snapshots must fit under the 128 KiB acknowledgement limit",snapshot.data.toString().getBytes(StandardCharsets.UTF_8).length<48*1024);
    }
    @Test public void changedScreenAndReusedNodePathAreRejectedBeforeDispatch()throws Exception{
        TvUiSnapshot snapshot=state();JSONObject request=command("click",snapshot).put("targetId",target(snapshot));button.setText("另一部影片");JSONObject receipt=run(request);
        assertEquals("stale_snapshot",receipt.optString("errorCode"));assertFalse(receipt.optBoolean("dispatched"));assertTrue(shadowOf(button).getPerformedActions().isEmpty());
    }
    @Test public void aSupersededSnapshotCannotPerformEvenGlobalNavigation(){
        TvUiSnapshot first=state();state();JSONObject receipt=run(command("back",first));assertEquals("stale_snapshot",receipt.optString("errorCode"));assertTrue(shadowOf(service).getGlobalActionsPerformed().isEmpty());
    }
    @Test public void typedHomeReturnsToMeadowWithoutTogglingThePhysicalHomeHdmiShortcut(){
        TvUiSnapshot snapshot=state();JSONObject receipt=run(command("home",snapshot));Intent home=shadowOf(app).getNextStartedActivity();assertNotNull(home);assertEquals(MainActivity.class.getName(),home.getComponent().getClassName());assertTrue(home.hasCategory(Intent.CATEGORY_HOME));assertTrue(home.getBooleanExtra("fromShortcut",false));assertTrue(receipt.optBoolean("dispatched"));
    }
    @Test public void nodeClickAcceptanceDoesNotClaimPlaybackOrGoalSuccess()throws Exception{
        shadowOf(button).setOnPerformActionListener((action,args)->true);TvUiSnapshot snapshot=state();JSONObject receipt=run(command("click",snapshot).put("targetId",target(snapshot)));
        assertTrue(receipt.optBoolean("dispatched"));assertFalse(receipt.optBoolean("verified"));assertEquals("dispatch_only",receipt.optString("verificationScope"));assertNotNull(receipt.optJSONObject("before"));assertNotNull(receipt.optJSONObject("after"));
    }
    @Test public void focusIsVerifiedOnlyFromTheObservedInputFocus()throws Exception{
        button.addAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_FOCUS);shadowOf(button).setOnPerformActionListener((action,args)->{if(action==AccessibilityNodeInfo.ACTION_FOCUS)button.setFocused(true);return true;});TvUiSnapshot snapshot=state();JSONObject receipt=run(command("focus",snapshot).put("targetId",target(snapshot)));
        assertTrue(receipt.optBoolean("verified"));assertEquals("input_focus",receipt.optString("verificationScope"));assertFalse(receipt.optJSONObject("after").optString("focusedTargetId").isEmpty());
    }
    @Test public void textInputIsVerifiedFromTheNewEditableValueNotActionAcceptance()throws Exception{
        button.setEditable(true);button.addAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SET_TEXT);shadowOf(button).setOnPerformActionListener((action,args)->{if(action==AccessibilityNodeInfo.ACTION_SET_TEXT)button.setText(args.getCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE));return true;});TvUiSnapshot snapshot=state();JSONObject receipt=run(command("set_text",snapshot).put("targetId",target(snapshot)).put("text","台灣 Podcast"));
        assertTrue(receipt.optBoolean("verified"));assertEquals("editable_text",receipt.optString("verificationScope"));assertEquals("台灣 Podcast",button.getText().toString());
    }
    @Test public void missingNativeScrollCapabilityDoesNotFallBackToGestures()throws Exception{
        TvUiSnapshot snapshot=state();JSONObject receipt=run(command("scroll",snapshot).put("targetId",target(snapshot)).put("direction","down"));assertEquals("unsupported",receipt.optString("errorCode"));assertFalse(receipt.optBoolean("dispatched"));assertTrue(shadowOf(service).getGesturesDispatched().isEmpty());
    }
    @Test public void arbitraryFieldsAndControlCharactersDoNotReachNodeActions(){
        assertFalse(TvUiExecutor.validKeys(jsonObject("action","android","operation","click","snapshotId","s1","targetId","n1","shell","input keyevent 23"),"click"));
        assertFalse(TvUiExecutor.validText("hello\nworld"));assertFalse(TvUiExecutor.validText("\u0000"));assertFalse(TvUiExecutor.validText(String.join("",Collections.nCopies(241,"a"))));assertTrue(TvUiExecutor.validText("台灣 Podcast"));
        assertFalse(TvUiExecutor.validKeys(jsonObject("action","android","operation","scroll","snapshotId","s1","targetId","n1","direction","diagonal"),"scroll"));
    }
    @Test public void noReadableWindowCannotBeUsedAsAGlobalActionSnapshot(){
        UiServiceShadow.activeRoot=null;TvUiSnapshot snapshot=state();JSONObject receipt=run(command("home",snapshot));assertEquals("window_unavailable",receipt.optString("errorCode"));assertFalse(receipt.optBoolean("dispatched"));
    }
    @Test public void missingAccessibilityServiceRemainsUnavailableWithoutEnablingIt(){
        controller.destroy();controller=Robolectric.buildService(RemoteService.class).create();service=controller.get();JSONObject read=run(jsonObject("action","android","operation","state"));assertFalse(read.optJSONObject("state").optJSONObject("permission").optBoolean("accessibilityConnected"));
        JSONObject request=jsonObject("action","android","operation","back","snapshotId",read.optJSONObject("state").optString("snapshotId"));JSONObject receipt=run(request);assertEquals("permission_missing",receipt.optString("errorCode"));
    }
    @Test public void protectedSystemPackagesAreExcludedFromMutations()throws Exception{
        root.setPackageName("com.xiaomi.mitv.settings");TvUiSnapshot snapshot=state();JSONObject receipt=run(command("click",snapshot).put("targetId",target(snapshot)));assertEquals("protected_ui",receipt.optString("errorCode"));assertFalse(receipt.optBoolean("dispatched"));
    }
    @Test public void appDirectoryContainsOnlyInstalledLeanbackPackages()throws Exception{
        android.content.pm.PackageManager pm=app.getPackageManager();android.content.ComponentName component=new ComponentName("com.example.actual","com.example.actual.TvMain");ActivityInfo installed=shadowOf(pm).addActivityIfNotPresent(component);installed.enabled=true;installed.exported=true;installed.applicationInfo.enabled=true;
        shadowOf(pm).addIntentFilterForActivity(component,new IntentFilter(){ {addAction(Intent.ACTION_MAIN);addCategory(Intent.CATEGORY_LEANBACK_LAUNCHER);} });
        JSONArray apps=TvUiExecutor.apps(app);boolean actual=false;for(int i=0;i<apps.length();i++){String pkg=apps.getJSONObject(i).getString("packageName");actual|=pkg.equals("com.example.actual");assertNotEquals("com.netflix.ninja",pkg);}assertTrue(actual);
        assertNull(TvUiExecutor.launchIntent(app,"com.not.installed"));assertNull(TvUiExecutor.launchIntent(app,"com.xiaomi.mitv.settings"));
    }
    @Test public void searchAndVoiceKeysOpenThePanelWithoutStartingMicrophone(){
        for(int key:new int[]{KeyEvent.KEYCODE_SEARCH,KeyEvent.KEYCODE_VOICE_ASSIST,KeyEvent.KEYCODE_ASSIST}){
            assertTrue(service.onKeyEvent(new KeyEvent(KeyEvent.ACTION_DOWN,key)));assertNull(shadowOf(service).getNextStartedActivity());assertTrue(service.onKeyEvent(new KeyEvent(KeyEvent.ACTION_UP,key)));Intent intent=shadowOf(service).getNextStartedActivity();assertNotNull(intent);assertEquals("assistant",intent.getStringExtra("action"));assertTrue(intent.getBooleanExtra("fromShortcut",false));assertFalse(intent.hasExtra("startMic"));
        }
        assertFalse(service.onKeyEvent(new KeyEvent(KeyEvent.ACTION_DOWN,KeyEvent.KEYCODE_BACK)));assertFalse(service.onKeyEvent(new KeyEvent(KeyEvent.ACTION_UP,KeyEvent.KEYCODE_BACK)));assertNull(shadowOf(service).getNextStartedActivity());
    }
    @Test public void canceledVoiceKeyDoesNotOpenThePanel(){
        KeyEvent canceled=new KeyEvent(0,0,KeyEvent.ACTION_UP,KeyEvent.KEYCODE_VOICE_ASSIST,0,0,0,0,KeyEvent.FLAG_CANCELED);assertTrue(service.onKeyEvent(canceled));assertNull(shadowOf(service).getNextStartedActivity());
    }
    @Test public void missingWeatherTemperatureIsNotReportedAsZero()throws Exception{
        app.getSharedPreferences("living",0).edit().putString("weather0","{\"weather_code\":2}").commit();JSONObject receipt=run(jsonObject("action","status"));assertTrue(receipt.optString("weather").contains("尚未提供"));assertFalse(receipt.optString("weather").contains("0°"));
    }
}
