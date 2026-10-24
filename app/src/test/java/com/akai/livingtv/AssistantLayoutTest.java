package com.akai.livingtv;

import android.app.AlertDialog;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.text.Spanned;
import android.text.style.ImageSpan;
import android.view.View;
import android.widget.EditText;
import android.widget.ImageButton;
import android.widget.TextView;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.List;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.GraphicsMode;
import android.os.Looper;
import java.time.Duration;
import static org.robolectric.Shadows.shadowOf;
import static org.junit.Assert.*;

/** TV navigation and readable connection states without a voice/network session. */
@RunWith(org.robolectric.RobolectricTestRunner.class)
@Config(sdk=28,qualifiers="w960dp-h540dp-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
public class AssistantLayoutTest {
    private ActivityController<MainActivity> controller;
    private AssistantPanel panel;

    @Before public void setup()throws Exception{
        RuntimeEnvironment.getApplication().getSharedPreferences("living",0).edit().clear().commit();
        controller=Robolectric.buildActivity(MainActivity.class).setup();
        View decor=controller.get().getWindow().getDecorView();decor.measure(View.MeasureSpec.makeMeasureSpec(1920,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(1080,View.MeasureSpec.EXACTLY));decor.layout(0,0,1920,1080);
        panel=new AssistantPanel(controller.get());panel.open();for(int i=0;i<4;i++){Thread.sleep(50);shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(350));}layout();
    }
    @After public void close(){panel.close();controller.pause().stop().destroy();}
    private Object field(String name)throws Exception{Field f=AssistantPanel.class.getDeclaredField(name);f.setAccessible(true);return f.get(panel);}
    private void connected(boolean value)throws Exception{flag("textReady",value);flag("connected",value);refreshConnection();}
    private void layout()throws Exception{AlertDialog d=(AlertDialog)field("dialog");View root=d.getWindow().getDecorView();int w=d.getWindow().getAttributes().width,h=d.getWindow().getAttributes().height;root.measure(View.MeasureSpec.makeMeasureSpec(w,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(h,View.MeasureSpec.EXACTLY));root.layout(0,0,w,h);}
    private void flag(String name,boolean value)throws Exception{Field field=AssistantPanel.class.getDeclaredField(name);field.setAccessible(true);field.setBoolean(panel,value);}
    private void refreshConnection()throws Exception{Method render=AssistantPanel.class.getDeclaredMethod("renderConnection");render.setAccessible(true);render.invoke(panel);layout();}
    private ImageButton closeAction()throws Exception{return ((AlertDialog)field("dialog")).getWindow().getDecorView().findViewWithTag("assistantClose");}
    /** Inspect the selected native surface, not a copied implementation color. */
    private void assertReadableCapsule(TextView action,boolean focused){
        assertTrue("The action must have a measured surface",action.getWidth()>0&&action.getHeight()>0);assertEquals(focused,action.hasFocus());
        Bitmap sample=Bitmap.createBitmap(action.getWidth(),action.getHeight(),Bitmap.Config.ARGB_8888);Canvas canvas=new Canvas(sample);canvas.drawColor(0xFF182126);
        android.graphics.drawable.Drawable background=action.getBackground();android.graphics.Rect previous=new android.graphics.Rect(background.getBounds());
        // Layout has not drawn the View yet. View.drawBackground normally assigns
        // these bounds before drawing; reproduce that step for a background-only sample.
        background.setBounds(0,0,action.getWidth(),action.getHeight());background.draw(canvas);background.setBounds(previous);
        double surface=Color.luminance(sample.getPixel(sample.getWidth()/2,sample.getHeight()/2)),caption=Color.luminance(action.getCurrentTextColor());sample.recycle();
        double contrast=(Math.max(surface,caption)+.05)/(Math.min(surface,caption)+.05);
        assertTrue(action.getText()+" needs at least 4.5:1 foreground/surface contrast; got "+contrast,contrast>=4.5);
        if(focused){assertTrue("Focused capsule needs the pale surface",surface>.65);assertTrue("Focused caption must be dark on its pale surface",caption<.18);}
        else {assertTrue("Idle capsule retains the dark glass",surface<.30);assertTrue("Idle caption must remain light enough, including the semantic offline color",caption>.25);}
    }
    private void assertReadableClose(ImageButton action,boolean focused){
        assertEquals(focused,action.hasFocus());assertEquals(64,action.getWidth());assertEquals(64,action.getHeight());
        Bitmap sample=Bitmap.createBitmap(action.getWidth(),action.getHeight(),Bitmap.Config.ARGB_8888);Canvas canvas=new Canvas(sample);canvas.drawColor(0xFF182126);action.draw(canvas);
        double surface=Color.luminance(sample.getPixel(32,10)),glyph=Color.luminance(sample.getPixel(32,32));
        assertEquals("The glass circle leaves its outer corner transparent",0xFF182126,sample.getPixel(0,0));sample.recycle();double contrast=(Math.max(surface,glyph)+.05)/(Math.min(surface,glyph)+.05);
        assertTrue("The centered X needs readable foreground/surface contrast; got "+contrast,contrast>=4.5);
        if(focused){assertTrue("Focused close uses a pale circle",surface>.65);assertTrue("Focused X stays dark",glyph<.18);}else{assertTrue("Idle close retains dark glass",surface<.30);assertTrue("Idle X remains light",glyph>.65);}
    }

    @Test public void offlineQueueKeepsFocusAndContrastDuringRepeatedStatusRefreshes()throws Exception{
        TextView mic=(TextView)field("mic"),queue=((List<TextView>)field("suggestions")).get(1);ImageButton close=closeAction();
        assertTrue("Queue caption colors must follow Android drawable state, including an availability refresh",queue.getTextColors().isStateful());
        for(int cycle=0;cycle<3;cycle++){
            assertTrue(mic.requestFocusFromTouch());assertReadableCapsule(mic,true);
            assertTrue(queue.requestFocusFromTouch());assertReadableCapsule(queue,true);
            for(int refresh=0;refresh<3;refresh++){
                refreshConnection();assertTrue("The offline queue must never be temporarily disabled",queue.isEnabled());assertEquals(1f,queue.getAlpha(),0f);assertTrue("Refreshing status must not eject focus from the queue",queue.hasFocus());assertReadableCapsule(queue,true);
            }
            assertTrue(close.requestFocusFromTouch());assertReadableClose(close,true);assertReadableCapsule(queue,false);assertTrue("The idle queue caption returns to its light color",Color.luminance(queue.getCurrentTextColor())>.65);
        }
    }

    @Test public void connectionPhasesKeepCapsuleCaptionAndFocusSurfaceInSync()throws Exception{
        TextView mic=(TextView)field("mic"),queue=((List<TextView>)field("suggestions")).get(1);ImageButton close=closeAction();
        // UI states only: no health check, voice connection, recognition or model request.
        boolean[][] phases={{false,false,false,false},{false,true,false,false},{true,false,false,false},{true,false,true,false},{true,false,false,true}};
        for(boolean[] phase:phases){
            flag("textReady",phase[0]);flag("connected",phase[0]);flag("connecting",phase[1]);flag("listening",phase[2]);flag("busy",phase[3]);refreshConnection();
            assertTrue(queue.requestFocusFromTouch());refreshConnection();assertTrue("Queue remains available in every connection phase",queue.isEnabled());assertReadableCapsule(queue,true);
            assertTrue(mic.requestFocusFromTouch());assertReadableCapsule(mic,true);assertReadableCapsule(queue,false);
            assertTrue(close.requestFocusFromTouch());assertReadableClose(close,true);assertReadableCapsule(mic,false);
        }
    }

    @Test public void healthReadinessKeepsTextAvailableWhenVoiceFails()throws Exception{
        Object pairedClient=field("client");flag("connecting",true);Method health=AssistantPanel.class.getDeclaredMethod("bridgeReady");health.setAccessible(true);health.invoke(panel);layout();
        assertTrue((boolean)field("textReady"));assertFalse((boolean)field("connected"));assertTrue(((EditText)field("input")).isEnabled());assertTrue(((TextView)field("send")).isEnabled());assertTrue(((List<TextView>)field("suggestions")).get(0).isEnabled());
        assertEquals("● 文字已連線",((TextView)field("liveBadge")).getText().toString());assertEquals("語音連線中",((TextView)field("mic")).getText().toString());assertFalse((boolean)field("listening"));assertNull(field("voice"));
        Method failed=AssistantPanel.class.getDeclaredMethod("voiceFailed",String.class);failed.setAccessible(true);failed.invoke(panel,"fixture voice failure");layout();
        assertSame("The working text client must survive voice failure",pairedClient,field("client"));assertTrue(panel.online());assertTrue((boolean)field("textReady"));assertFalse((boolean)field("connected"));assertFalse((boolean)field("connecting"));assertFalse((boolean)field("listening"));assertNull(field("voice"));
        assertEquals("● 文字已連線",((TextView)field("liveBadge")).getText().toString());assertTrue(((TextView)field("status")).getText().toString().contains("語音暫不可用"));assertEquals(View.GONE,((View)field("loading")).getVisibility());
        TextView mic=(TextView)field("mic"),recommend=((List<TextView>)field("suggestions")).get(0);assertEquals("● 重試語音",mic.getText().toString());assertTrue(mic.requestFocusFromTouch());assertReadableCapsule(mic,true);assertEquals(recommend.getId(),mic.getNextFocusRightId());
        assertTrue(recommend.requestFocusFromTouch());refreshConnection();assertTrue(recommend.isEnabled());assertReadableCapsule(recommend,true);assertReadableCapsule(mic,false);assertTrue(((EditText)field("input")).isEnabled());assertTrue(((TextView)field("send")).isEnabled());
        long generation=(long)field("connectionGeneration");panel.hidePanel();panel.open();layout();assertTrue((boolean)field("textReady"));assertFalse((boolean)field("connecting"));assertNull(field("voice"));assertEquals("Reopening the panel must not retry voice without a press",generation,(long)field("connectionGeneration"));assertSame(pairedClient,field("client"));assertEquals("● 文字已連線",((TextView)field("liveBadge")).getText().toString());
    }

    @Test public void circularCloseKeepsHeaderFocusNavigationAndHideCleanup()throws Exception{
        ImageButton close=closeAction();TextView mic=(TextView)field("mic");AlertDialog dialog=(AlertDialog)field("dialog");View wordmark=dialog.getWindow().getDecorView().findViewWithTag("assistantWordmark");
        assertEquals("關閉 Meadow",close.getContentDescription().toString());assertTrue(close.isFocusable());assertTrue(close.isClickable());assertNotEquals(View.NO_ID,close.getId());assertEquals(close.getId(),mic.getNextFocusUpId());assertEquals(mic.getId(),close.getNextFocusDownId());
        assertEquals(wordmark.getTop()+wordmark.getHeight()/2,close.getTop()+close.getHeight()/2);assertEquals(((TextView)field("send")).getRight(),close.getRight());assertReadableClose(close,false);assertTrue(close.requestFocusFromTouch());assertReadableClose(close,true);
        flag("listening",true);flag("awaitingTranscript",true);assertTrue(close.performClick());shadowOf(Looper.getMainLooper()).idle();assertFalse(dialog.isShowing());assertNull(field("dialog"));assertFalse((boolean)field("listening"));assertFalse((boolean)field("awaitingTranscript"));
    }

    @Test public void disconnectedVoiceActionKeepsContrastAndCanReachAvailableQueue()throws Exception{
        TextView mic=(TextView)field("mic"),send=(TextView)field("send");
        List<TextView> suggestions=(List<TextView>)field("suggestions");
        assertTrue(mic.requestFocusFromTouch());assertEquals("● 離線",mic.getText().toString());
        assertEquals("Light focus background needs dark caption",0xFF6E302E,mic.getCurrentTextColor());
        assertFalse(send.isEnabled());assertFalse(((EditText)field("input")).isEnabled());
        assertFalse(suggestions.get(0).isEnabled());assertTrue(suggestions.get(1).isEnabled());
        assertEquals("Offline navigation must skip the unavailable recommendation",suggestions.get(1).getId(),mic.getNextFocusRightId());
        connected(true);assertTrue(suggestions.get(0).isEnabled());assertTrue(send.isEnabled());
        assertEquals(suggestions.get(0).getId(),mic.getNextFocusRightId());
        assertTrue(mic.getText() instanceof Spanned);
        assertEquals(1,((Spanned)mic.getText()).getSpans(0,mic.length(),ImageSpan.class).length);
    }

    @Test public void conversationDoesNotCollideWithActionsOrStatus()throws Exception{
        TextView said=(TextView)field("said"),reply=(TextView)field("reply"),status=(TextView)field("status"),send=(TextView)field("send");
        List<TextView> suggestions=(List<TextView>)field("suggestions");
        Method show=AssistantPanel.class.getDeclaredMethod("showUser",String.class);show.setAccessible(true);show.invoke(panel,"我想找一部和家人一起看的輕鬆電影，也想把喜歡的節目加到清單裡。");layout();
        assertTrue("TV transcript should remain readable at a distance",said.getTextSize()>=40);
        assertEquals(2,said.getMaxLines());assertTrue(said.getBottom()<((View)reply.getParent()).getTop());
        assertNotNull(said.getLayout());assertTrue("CJK transcript lines="+said.getLayout().getLineCount()+", lastBottom="+said.getLayout().getLineBottom(said.getLayout().getLineCount()-1)+", height="+said.getHeight(),said.getLayout().getLineBottom(said.getLayout().getLineCount()-1)<=said.getHeight());
        assertTrue(((View)reply.getParent()).getBottom()<suggestions.get(0).getTop());
        assertTrue(suggestions.get(0).getBottom()<((EditText)field("input")).getTop());
        assertTrue(send.getBottom()<status.getTop());
        assertEquals("Conversation and its actions share the same inner left edge",said.getLeft(),suggestions.get(0).getLeft());
        assertEquals("The text input remains aligned with the conversation",said.getLeft(),((EditText)field("input")).getLeft());
        assertEquals("The action rail ends at the same frame inset as the transcript",said.getRight(),suggestions.get(1).getRight());
        assertEquals("Send stays inside the same right inset",said.getRight(),send.getRight());
        View frame=(View)said.getParent(),wordmark=frame.findViewWithTag("assistantWordmark"),close=frame.findViewWithTag("assistantClose");
        assertEquals("Wordmark and close action align on the header centre",wordmark.getTop()+wordmark.getHeight()/2,close.getTop()+close.getHeight()/2);
    }

    @Test public void emptyPanelUsesGuidanceUntilARealTranscriptArrives()throws Exception{
        AlertDialog dialog=(AlertDialog)field("dialog");android.util.DisplayMetrics metrics=controller.get().getResources().getDisplayMetrics();assertEquals(Math.round(metrics.widthPixels*.70f),dialog.getWindow().getAttributes().width);assertEquals(Math.round(metrics.heightPixels*.70f),dialog.getWindow().getAttributes().height);
        TextView said=(TextView)field("said"),reply=(TextView)field("reply"),welcome=(TextView)field("welcomeTitle");
        assertEquals("Empty UI must not invent a user message","",said.getText().toString());assertEquals("Empty UI must not invent an assistant reply","",reply.getText().toString());
        assertEquals(View.INVISIBLE,((View)field("userLabel")).getVisibility());assertEquals(View.INVISIBLE,((View)field("replyLabel")).getVisibility());assertEquals(View.VISIBLE,welcome.getVisibility());
        assertEquals("今晚，想看什麼？",welcome.getText().toString());assertFalse(((View)field("replyScroll")).isFocusable());
        assertEquals(0,((org.json.JSONArray)field("history")).length());
        Method show=AssistantPanel.class.getDeclaredMethod("showUser",String.class);show.setAccessible(true);show.invoke(panel,"找一部適合家人一起看的電影");layout();
        assertEquals(View.GONE,welcome.getVisibility());assertEquals(View.VISIBLE,((View)field("userLabel")).getVisibility());assertEquals(View.VISIBLE,said.getVisibility());assertEquals(0xFF8FCF98,said.getCurrentTextColor());
        assertEquals("找一部適合家人一起看的電影",said.getText().toString());assertEquals("Showing an input must not fabricate a response","",reply.getText().toString());
    }
}
