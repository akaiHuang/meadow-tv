package com.akai.livingtv;

import android.widget.*;import android.text.*;import android.text.style.ImageSpan;import android.view.View;import android.os.Looper;
import org.junit.*;import org.junit.runner.RunWith;import org.robolectric.*;import org.robolectric.annotation.Config;import org.robolectric.annotation.LooperMode;
import org.robolectric.android.controller.ActivityController;
import java.lang.reflect.*;import java.util.*;
import java.util.concurrent.atomic.AtomicLong;
import static org.junit.Assert.*;

/** No microphone, model request, WebRTC negotiation or Google recognizer is started. */
@RunWith(RobolectricTestRunner.class) @Config(sdk=28)
public class VoiceTurnTest {
    @Before public void clearPairingFixture(){RuntimeEnvironment.getApplication().getSharedPreferences("living",0).edit().clear().commit();}
    Object get(Object p,String name)throws Exception{Field f=p.getClass().getDeclaredField(name);f.setAccessible(true);return f.get(p);}
    void set(Object p,String name,Object value)throws Exception{Field f=p.getClass().getDeclaredField(name);f.setAccessible(true);f.set(p,value);}
    void invoke(AssistantPanel panel,String name)throws Exception{Method method=AssistantPanel.class.getDeclaredMethod(name);method.setAccessible(true);method.invoke(panel);}
    void failVoice(AssistantPanel panel)throws Exception{Method method=AssistantPanel.class.getDeclaredMethod("voiceFailed",String.class);method.setAccessible(true);method.invoke(panel,"fixture voice unavailable");}
    @Test public void finalCodexPartsReplaceTheirDeltasAndDuplicateFinalsAreNotRepeated(){
        VoicePeer.TranscriptTurn turn=new VoicePeer.TranscriptTurn();turn.accept("我想",false);turn.accept("聽 Podcast",false);assertEquals("我想聽 Podcast",turn.text());assertFalse(turn.hasFinal());
        turn.accept("我想聽 Podcast",true);turn.accept("我想聽 Podcast",true);assertEquals("我想聽 Podcast",turn.text());assertTrue(turn.complete());
        turn.accept("心理",false);turn.accept("學的節目",false);assertEquals("我想聽 Podcast，心理學的節目",turn.text());assertFalse(turn.complete());
        turn.accept("心理學的節目",true);assertEquals("我想聽 Podcast，心理學的節目",turn.text());assertTrue(turn.hasFinal());assertTrue(turn.complete());turn.reset();assertEquals("",turn.text());assertFalse(turn.hasFinal());assertFalse(turn.complete());
    }
    @Test public void everyKindOfUtteranceUsesTheSameHarnessInputContract(){
        for(String text:new String[]{"今天有點累，陪我聊一下","你叫什麼名字？","音量小聲一點","第二個","找心理學節目"})assertEquals(text,AssistantPanel.requestForHarness(text,""));
        String text="最近有什麼適合聽的？";String request=AssistantPanel.requestForHarness(text,"podcast");assertTrue(request.startsWith("目前是 Podcast 語音搜尋。"));assertTrue(request.endsWith(text));assertTrue(request.contains("真實單集 ID"));
    }
    @Test public void partialTranscriptIsVisibleButCannotStartATaskUntilFinal()throws Exception{
        ActivityController<MainActivity> controller=Robolectric.buildActivity(MainActivity.class).setup();AssistantPanel panel=new AssistantPanel(controller.get());
        try{panel.open();set(panel,"textReady",true);set(panel,"connected",true);set(panel,"listening",true);set(panel,"busy",true);
            Method input=AssistantPanel.class.getDeclaredMethod("onCodexTranscript",String.class,boolean.class);input.setAccessible(true);
            input.invoke(panel,"我想要聽心理學的節目",false);assertTrue((boolean)get(panel,"listening"));assertEquals("",get(panel,"lastUser"));assertNull(get(panel,"task"));assertEquals("我想要聽心理學的節目",((TextView)get(panel,"said")).getText().toString());
            input.invoke(panel,"我想要聽心理學的節目",true);assertFalse((boolean)get(panel,"listening"));assertNull("Busy test guard prevents any real task",get(panel,"task"));
            input.invoke(panel,"過期的上一輪內容",false);assertEquals("我想要聽心理學的節目",((TextView)get(panel,"said")).getText().toString());
        }finally{panel.close();controller.pause().stop().destroy();}
    }
    @Test public void podcastSearchOpensContextWithoutAutomaticallyCapturing()throws Exception{
        ActivityController<MainActivity> controller=Robolectric.buildActivity(MainActivity.class).setup();AssistantPanel panel=new AssistantPanel(controller.get());
        try{panel.openForSearch("podcast");assertEquals("podcast",get(panel,"searchDomain"));assertEquals("找點想聽的",((TextView)get(panel,"welcomeTitle")).getText().toString());assertFalse((boolean)get(panel,"listening"));assertNull(get(panel,"voice"));assertEquals("",((TextView)get(panel,"said")).getText().toString());}finally{panel.close();controller.pause().stop().destroy();}
    }
    @Test public void microphoneIconIsInlineWithCenteredCaption()throws Exception{
        ActivityController<MainActivity> controller=Robolectric.buildActivity(MainActivity.class).setup();AssistantPanel panel=new AssistantPanel(controller.get());
        try{panel.open();set(panel,"textReady",true);set(panel,"connected",true);Method render=AssistantPanel.class.getDeclaredMethod("renderConnection");render.setAccessible(true);render.invoke(panel);TextView mic=(TextView)get(panel,"mic");assertTrue(mic.getText() instanceof Spanned);assertEquals(1,((Spanned)mic.getText()).getSpans(0,mic.length(),ImageSpan.class).length);assertNull(mic.getCompoundDrawables()[0]);assertEquals(0xFF8FCF98,((TextView)get(panel,"liveBadge")).getCurrentTextColor());}finally{panel.close();controller.pause().stop().destroy();}
    }
    @Test public void cancelledCaptureGenerationCannotDeliverOldTranscripts()throws Exception{
        List<String> seen=new ArrayList<>();VoicePeer peer=new VoicePeer(RuntimeEnvironment.getApplication(),new VoicePeer.Listener(){public void state(String s){}public void level(float l){}public void connected(){}public void ended(){}public void failed(String s){}public void inputTranscript(String s,boolean done){seen.add(s);}},false);
        try{set(peer,"captureGeneration",10L);Method input=VoicePeer.class.getDeclaredMethod("acceptTranscript",long.class,String.class,boolean.class);input.setAccessible(true);input.invoke(peer,9L,"舊聲音",true);assertTrue(seen.isEmpty());input.invoke(peer,10L,"新聲音",true);assertEquals(Collections.singletonList("新聲音"),seen);peer.cancelListening();input.invoke(peer,10L,"已取消的聲音",true);assertEquals(1,seen.size());}finally{peer.close();}
    }
    @Test @LooperMode(LooperMode.Mode.PAUSED)
    public void textFallbackCanHandOffTheSameHarnessWithoutVoiceOrAMicrophone()throws Exception{
        ActivityController<MainActivity> controller=Robolectric.buildActivity(MainActivity.class).setup();AssistantPanel panel=new AssistantPanel(controller.get());TvToolSession pending=null;
        try{panel.open();Object client=get(panel,"client");set(panel,"connecting",true);invoke(panel,"bridgeReady");failVoice(panel);
            assertTrue((boolean)get(panel,"textReady"));assertFalse((boolean)get(panel,"connected"));assertTrue(panel.online());assertSame(client,get(panel,"client"));assertNull(get(panel,"voice"));
            invoke(panel,"startVoice");assertFalse("Text readiness cannot open the microphone",(boolean)get(panel,"listening"));assertNull(get(panel,"voice"));
            assertFalse("Fixture is deliberately unpaired; its queued task cannot authenticate",((AssistantClient)client).configured());
            panel.ask("請幫我找心理學的 Podcast");pending=(TvToolSession)get(panel,"task");assertNotNull("Text uses the real task handoff rather than a voice-only branch",pending);assertTrue(pending.isActive());
            assertEquals("請幫我找心理學的 Podcast",get(panel,"lastUser"));assertTrue((boolean)get(panel,"busy"));assertFalse((boolean)get(panel,"speaking"));assertNull(get(panel,"voice"));
            failVoice(panel);assertSame("Voice failure cannot cancel an active text task",pending,get(panel,"task"));assertTrue(pending.isActive());assertTrue((boolean)get(panel,"busy"));
            // The paused looper prevents the service from starting. Cancel before
            // teardown; neither Codex, WebRTC nor AudioRecord is initialized.
            pending.cancel();assertFalse(pending.isActive());assertTrue((boolean)get(panel,"textReady"));assertFalse((boolean)get(panel,"connected"));assertFalse((boolean)get(panel,"speaking"));
        }finally{if(pending!=null&&pending.isActive())pending.cancel();panel.close();controller.pause().stop().destroy();}
    }
    @Test public void explicitDisconnectAndIdleClearTextAndVoiceReadiness()throws Exception{
        ActivityController<MainActivity> controller=Robolectric.buildActivity(MainActivity.class).setup();AssistantPanel panel=new AssistantPanel(controller.get());
        try{panel.open();invoke(panel,"bridgeReady");set(panel,"connected",true);set(panel,"listening",true);
            Method disconnect=AssistantPanel.class.getDeclaredMethod("disconnect",String.class,boolean.class);disconnect.setAccessible(true);disconnect.invoke(panel,"fixture disconnected",true);
            assertFalse(panel.online());assertFalse((boolean)get(panel,"textReady"));assertFalse((boolean)get(panel,"connected"));assertFalse((boolean)get(panel,"listening"));assertFalse(((EditText)get(panel,"input")).isEnabled());assertFalse(((TextView)get(panel,"send")).isEnabled());
            invoke(panel,"bridgeReady");failVoice(panel);assertTrue(panel.online());((Runnable)get(panel,"idle")).run();assertFalse(panel.online());assertFalse((boolean)get(panel,"textReady"));assertFalse((boolean)get(panel,"connected"));assertNull(get(panel,"voice"));assertTrue(((TextView)get(panel,"status")).getText().toString().contains("三分鐘"));
        }finally{panel.close();controller.pause().stop().destroy();}
    }
    @Test public void detachedReceiptIsIndependentDeduplicatedAndNeverBecomesInventedDialogue()throws Exception{
        ActivityController<MainActivity> controller=Robolectric.buildActivity(MainActivity.class).setup();AssistantPanel panel=new AssistantPanel(controller.get());
        try{controller.get().getSharedPreferences("living",0).edit().putString("latestTaskResult","{\"text\":\"已開啟 YouTube，搜尋結果仍待確認。\",\"ok\":true,\"finishedAt\":1000}").commit();panel.open();
            assertEquals("上次任務",((TextView)get(panel,"replyLabel")).getText().toString());assertEquals(View.INVISIBLE,((TextView)get(panel,"userLabel")).getVisibility());assertEquals(View.INVISIBLE,((TextView)get(panel,"said")).getVisibility());assertEquals("",get(panel,"lastUser"));assertEquals("",get(panel,"lastAnswer"));assertEquals(0,((org.json.JSONArray)get(panel,"history")).length());assertFalse((boolean)get(panel,"speaking"));assertNull(get(panel,"voice"));
            controller.get().getSharedPreferences("living",0).edit().putString("latestTaskResult","{\"text\":\"同一時間不可當新結果\",\"finishedAt\":1000}").commit();panel.hidePanel();panel.open();assertEquals("已開啟 YouTube，搜尋結果仍待確認。",((TextView)get(panel,"reply")).getText().toString());assertEquals(1000L,get(panel,"seenTaskFinishedAt"));
            Method show=AssistantPanel.class.getDeclaredMethod("showUser",String.class);show.setAccessible(true);show.invoke(panel,"現在幾點？");assertEquals("Meadow",((TextView)get(panel,"replyLabel")).getText().toString());assertEquals(View.VISIBLE,((TextView)get(panel,"userLabel")).getVisibility());assertEquals(View.VISIBLE,((TextView)get(panel,"said")).getVisibility());assertEquals("",((TextView)get(panel,"reply")).getText().toString());assertEquals("",get(panel,"receiptText"));assertTrue(((FrameLayout.LayoutParams)((TextView)get(panel,"replyLabel")).getLayoutParams()).topMargin>300);
        }finally{panel.close();controller.pause().stop().destroy();}
    }
    @Test public void emptyOrMalformedReceiptsCannotAdvanceSeenAndNormalCallbacksCanMarkSeen()throws Exception{
        ActivityController<MainActivity> controller=Robolectric.buildActivity(MainActivity.class).setup();AssistantPanel panel=new AssistantPanel(controller.get());
        try{controller.get().getSharedPreferences("living",0).edit().putString("latestTaskResult","{\"text\":\"\",\"finishedAt\":9000}").commit();panel.open();assertEquals(0L,get(panel,"seenTaskFinishedAt"));assertEquals(View.VISIBLE,((TextView)get(panel,"welcomeTitle")).getVisibility());
            Method restore=AssistantPanel.class.getDeclaredMethod("restoreTaskReceipt");restore.setAccessible(true);controller.get().getSharedPreferences("living",0).edit().putString("latestTaskResult","not JSON").commit();restore.invoke(panel);assertEquals(0L,get(panel,"seenTaskFinishedAt"));
            controller.get().getSharedPreferences("living",0).edit().putString("latestTaskResult","{\"text\":\"正常回覆已經顯示\",\"finishedAt\":9100}").commit();Method mark=AssistantPanel.class.getDeclaredMethod("markTaskReceiptSeen");mark.setAccessible(true);mark.invoke(panel);restore.invoke(panel);assertEquals(9100L,get(panel,"seenTaskFinishedAt"));assertEquals("",get(panel,"receiptText"));assertEquals(0,((org.json.JSONArray)get(panel,"history")).length());
        }finally{panel.close();controller.pause().stop().destroy();}
    }
    @Test public void staleOutputCallbacksCannotUnmuteOrEndANewerUtterance()throws Exception{
        List<String> events=new ArrayList<>();VoicePeer peer=new VoicePeer(RuntimeEnvironment.getApplication(),new VoicePeer.Listener(){public void state(String s){events.add(s);}public void level(float l){}public void connected(){}public void ended(){events.add("ended");}public void failed(String s){}},false);
        try{AtomicLong epoch=(AtomicLong)get(peer,"outputEpoch");long old=epoch.get();peer.muteOutput();Method allow=VoicePeer.class.getDeclaredMethod("allowOutput",long.class);allow.setAccessible(true);Method finish=VoicePeer.class.getDeclaredMethod("finishOutput",long.class,String.class);finish.setAccessible(true);allow.invoke(peer,old);assertFalse((boolean)get(peer,"outputAllowed"));finish.invoke(peer,old,"舊錯誤");Shadows.shadowOf(Looper.getMainLooper()).idle();assertTrue(events.isEmpty());
            long current=epoch.get();allow.invoke(peer,current);assertTrue((boolean)get(peer,"outputAllowed"));set(peer,"utteranceDone",false);finish.invoke(peer,current,"文字回覆已保留");Shadows.shadowOf(Looper.getMainLooper()).idle();assertFalse((boolean)get(peer,"outputAllowed"));assertEquals(Arrays.asList("ended","文字回覆已保留"),events);finish.invoke(peer,current,"重複結束");Shadows.shadowOf(Looper.getMainLooper()).idle();assertEquals(2,events.size());
            long next=epoch.get();set(peer,"capturing",true);allow.invoke(peer,next);assertFalse((boolean)get(peer,"outputAllowed"));set(peer,"capturing",false);set(peer,"captureEndAccepted",true);allow.invoke(peer,next);assertFalse((boolean)get(peer,"outputAllowed"));
        }finally{peer.close();}
    }
}
