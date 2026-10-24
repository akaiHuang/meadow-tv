package com.akai.livingtv;

import android.app.Application;
import android.content.Intent;
import android.os.Looper;
import java.time.Duration;
import java.util.concurrent.atomic.*;
import org.json.*;
import org.junit.*;
import org.junit.runner.RunWith;
import org.robolectric.*;
import org.robolectric.annotation.*;
import static org.junit.Assert.*;
import static org.robolectric.Shadows.shadowOf;

@RunWith(RobolectricTestRunner.class) @Config(sdk=28) @LooperMode(LooperMode.Mode.PAUSED)
public class TvToolSessionTest {
    Application app;TvToolSession session;
    @Before public void setup(){app=RuntimeEnvironment.getApplication();app.getSharedPreferences("living",0).edit().clear().commit();}
    @After public void close(){if(session!=null)session.cancel();shadowOf(Looper.getMainLooper()).idle();}
    void configure(){app.getSharedPreferences("living",0).edit().putString("bridgeEndpoint","https://127.0.0.1:8766").commit();}
    @Test public void unpairedTaskDoesNotStartAServiceOrRemainActive(){
        AtomicReference<AssistantClient.Reply> result=new AtomicReference<>();session=TvToolSession.start(app,"狀態",new JSONArray(),new JSONArray(),s->{},result::set);shadowOf(Looper.getMainLooper()).idle();assertFalse(session.isActive());assertNotNull(result.get());assertFalse(result.get().ok);assertNull(shadowOf(app).getNextStartedService());
    }
    @Test public void detachingUiKeepsOnlyTheExplicitTaskAndCancelReleasesIt()throws Exception{
        configure();AtomicInteger callbacks=new AtomicInteger();session=TvToolSession.start(app,"讀取電視",new JSONArray(),new JSONArray(),s->callbacks.incrementAndGet(),r->callbacks.incrementAndGet());shadowOf(Looper.getMainLooper()).idle();Intent service=shadowOf(app).getNextStartedService();assertNotNull(service);assertEquals(TvTaskService.class.getName(),service.getComponent().getClassName());
        session.detach();assertTrue(session.isActive());session.cancel();assertFalse(session.isActive());assertEquals(0,callbacks.get());JSONObject saved=new JSONObject(app.getSharedPreferences("living",0).getString("latestTaskResult",""));assertFalse(saved.getBoolean("ok"));assertTrue(saved.getLong("finishedAt")>0);
    }
    @Test public void unstartedServiceCannotLeaveATaskPollingBeyondTwoMinutes(){
        configure();AtomicInteger completions=new AtomicInteger();session=TvToolSession.start(app,"讀取電視",new JSONArray(),new JSONArray(),s->{},r->completions.incrementAndGet());shadowOf(Looper.getMainLooper()).idle();assertTrue(session.isActive());shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(TvToolSession.TIMEOUT_MS+1));assertFalse(session.isActive());assertEquals(1,completions.get());assertNull(TvToolSession.find(session.id));
    }
    @Test public void replacingATaskCancelsTheOldOneInsteadOfStartingParallelRuns(){
        configure();session=TvToolSession.start(app,"一",new JSONArray(),new JSONArray(),s->{},r->{});shadowOf(Looper.getMainLooper()).idle();TvToolSession original=session;session=TvToolSession.start(app,"二",new JSONArray(),new JSONArray(),s->{},r->{});shadowOf(Looper.getMainLooper()).idle();assertFalse(original.isActive());assertTrue(session.isActive());assertNull(TvToolSession.find(original.id));assertSame(session,TvToolSession.find(session.id));
    }
}
