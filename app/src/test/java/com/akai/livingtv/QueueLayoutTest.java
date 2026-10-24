package com.akai.livingtv;

import android.app.AlertDialog;
import android.graphics.*;
import android.os.Looper;
import android.view.View;
import android.view.WindowManager;
import android.widget.*;
import org.junit.*;
import org.junit.runner.RunWith;
import org.robolectric.*;
import org.robolectric.annotation.*;
import static org.junit.Assert.*;
import static org.robolectric.Shadows.shadowOf;

/** Regressions for TV reading bounds, focus contrast and truthful transport state. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk=28,qualifiers="w960dp-h540dp-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
public class QueueLayoutTest {
    private OfflineDesignTest fixture;
    private QueueDialog panel;
    private AlertDialog dialog;
    @Before public void open()throws Exception{
        fixture=new OfflineDesignTest();fixture.setup();fixture.seedQueue();
        panel=new QueueDialog(fixture.activity);panel.open();fixture.settle();
        dialog=(AlertDialog)fixture.field(panel,"dialog");
        WindowManager.LayoutParams bounds=dialog.getWindow().getAttributes();
        fixture.layout(dialog.getWindow().getDecorView(),bounds.width,bounds.height);
    }
    @After public void close(){if(dialog!=null)dialog.dismiss();if(fixture!=null)fixture.close();}
    @Test public void longLibraryTitleAndPublisherHaveSeparateLinesAndFocusStaysReadable()throws Exception{
        LinearLayout rows=(LinearLayout)fixture.field(panel,"libraryRows");
        LinearLayout row=(LinearLayout)rows.getChildAt(0);
        FrameLayout primary=(FrameLayout)row.getChildAt(0);
        TextView title=primary.findViewWithTag("libraryTitle"),publisher=primary.findViewWithTag("libraryPublisher");
        assertEquals(2,title.getMaxLines());assertReadableTextPair(title,publisher,primary);
        assertEquals(primary.getChildAt(0).getWidth(),primary.getChildAt(0).getHeight());
        assertEquals("TV cover should keep the reference reading size",100,primary.getChildAt(0).getWidth());
        assertTrue("Title should sit near the cover without a hidden-icon column",title.getLeft()-primary.getChildAt(0).getRight()<=30);
        assertTrue(title.getTextSize()>=30);assertTrue(publisher.getTextSize()>=22);
        assertEquals("Normal results should not reserve a helper-text line","",((TextView)fixture.field(panel,"message")).getText().toString());
        assertTrue(primary.requestFocusFromTouch());assertTrue(row.isSelected());assertEquals(0xFF142D20,title.getCurrentTextColor());
        assertTrue(row.getChildAt(1).requestFocusFromTouch());assertTrue(row.isSelected());assertEquals(0xFF142D20,title.getCurrentTextColor());
    }
    @Test public void queueTitlesDoNotOverlapPublisherAndVideoCannotOfferAudioPause()throws Exception{
        LinearLayout rows=(LinearLayout)fixture.field(panel,"queueRows");FrameLayout row=(FrameLayout)rows.getChildAt(0);
        TextView title=row.findViewWithTag("queueTitle"),publisher=row.findViewWithTag("queuePublisher");assertReadableTextPair(title,publisher,row);
        View toggle=(View)fixture.field(panel,"toggle"),next=(View)fixture.field(panel,"next"),stop=(View)fixture.field(panel,"stop");
        assertEquals("播放",toggle.getContentDescription());assertEquals("下一項",next.getContentDescription());assertTrue(next.isEnabled());
        MediaItem video=new MediaItem();video.id="video:layout";video.title="影片";video.provider="netflix";
        fixture.queue.select(fixture.queue.add(video));fixture.queue.update("external","");shadowOf(Looper.getMainLooper()).idle();
        assertFalse(toggle.isEnabled());assertEquals("結束待播",stop.getContentDescription());
        assertTrue(toggle.getRight()<next.getLeft());assertTrue(next.getRight()<stop.getLeft());
    }
    @Test public void longCjkRowsGrowInsteadOfClippingThePublisher()throws Exception{
        LinearLayout rows=(LinearLayout)fixture.field(panel,"libraryRows");
        FrameLayout primary=(FrameLayout)((LinearLayout)rows.getChildAt(0)).getChildAt(0);
        TextView title=primary.findViewWithTag("libraryTitle"),publisher=primary.findViewWithTag("libraryPublisher");
        title.setText("這是一個比較長的中文節目名稱，還有後半段的內容需要完整顯示在兩行裡");
        WindowManager.LayoutParams bounds=dialog.getWindow().getAttributes();fixture.layout(dialog.getWindow().getDecorView(),bounds.width,bounds.height);
        assertTrue(primary.getHeight()>=124);assertEquals(2,title.getLayout().getLineCount());assertReadableTextPair(title,publisher,primary);
    }
    @Test public void currentAudioUsesStatusBarsAndFooterKeepsItsBottomInset()throws Exception{
        LinearLayout rows=(LinearLayout)fixture.field(panel,"queueRows");FrameLayout row=(FrameLayout)rows.getChildAt(0);NowPlayingIndicator indicator=findIndicator(row);assertNotNull("Current audio has status bars rather than a play triangle",indicator);assertFalse(indicator.isClickable());
        fixture.queue.update("playing","");shadowOf(Looper.getMainLooper()).idle();row=(FrameLayout)rows.getChildAt(0);indicator=findIndicator(row);assertNotNull(indicator);assertEquals("正在播放",indicator.getContentDescription());
        fixture.queue.update("paused","");shadowOf(Looper.getMainLooper()).idle();row=(FrameLayout)rows.getChildAt(0);indicator=findIndicator(row);assertEquals("已暫停",indicator.getContentDescription());
        View toggle=(View)fixture.field(panel,"toggle"),next=(View)fixture.field(panel,"next"),stop=(View)fixture.field(panel,"stop");assertEquals(toggle.getTop(),next.getTop());assertEquals(toggle.getTop(),stop.getTop());
        View stage=(View)toggle.getParent();assertTrue("Transport labels need breathing room above the rounded lower edge",stage.getHeight()-toggle.getBottom()>=24);
        View manage=stage.findViewWithTag("queueManage"),clear=stage.findViewWithTag("queueClear"),line=stage.findViewWithTag("queueTransportDivider");assertEquals(manage.getTop(),clear.getTop());assertTrue("Management remains above the transport separator",manage.getBottom()<line.getTop());
    }
    @Test public void lastPodcastSkipsDisabledNextInBothDirectionsWithoutLosingFocusOnRefresh()throws Exception{
        fixture.queue.select(fixture.queue.items().size()-1);fixture.queue.update("stopped","");shadowOf(Looper.getMainLooper()).idle();
        View toggle=(View)fixture.field(panel,"toggle"),next=(View)fixture.field(panel,"next"),stop=(View)fixture.field(panel,"stop");
        assertTrue(toggle.isEnabled());assertTrue(stop.isEnabled());assertFalse(next.isEnabled());assertFalse("An unavailable next item must not absorb D-pad focus",next.isFocusable());
        assertTrue(stop.requestFocusFromTouch());
        for(int cycle=0;cycle<3;cycle++){
            assertSame("Left from Stop must skip the unavailable Next action",toggle,stop.focusSearch(View.FOCUS_LEFT));assertTrue(toggle.requestFocusFromTouch());
            assertSame("Right from Play must skip the unavailable Next action",stop,toggle.focusSearch(View.FOCUS_RIGHT));assertTrue(stop.requestFocusFromTouch());
            fixture.queue.update("stopped","");shadowOf(Looper.getMainLooper()).idle();assertTrue("A queue refresh must keep the enabled Stop action focused",stop.hasFocus());
        }
    }
    @Test public void transportRoutesFollowQueueAvailabilityAndRecoverADisabledFocusedAction()throws Exception{
        View toggle=(View)fixture.field(panel,"toggle"),next=(View)fixture.field(panel,"next"),stop=(View)fixture.field(panel,"stop");
        assertTrue(next.isEnabled());assertTrue(stop.requestFocusFromTouch());assertSame(next,stop.focusSearch(View.FOCUS_LEFT));assertTrue(next.requestFocusFromTouch());assertSame(toggle,next.focusSearch(View.FOCUS_LEFT));assertSame(stop,next.focusSearch(View.FOCUS_RIGHT));
        assertTrue(next.requestFocusFromTouch());fixture.queue.select(fixture.queue.items().size()-1);shadowOf(Looper.getMainLooper()).idle();
        assertFalse(next.isFocusable());View focus=dialog.getCurrentFocus();assertNotNull(focus);assertTrue("When Next becomes unavailable focus stays on an enabled transport",focus==toggle||focus==stop);assertTrue(focus.isEnabled());
        MediaItem video=new MediaItem();video.id="video:focus-last";video.title="影片";video.provider="netflix";fixture.queue.select(fixture.queue.add(video));fixture.queue.update("external","");shadowOf(Looper.getMainLooper()).idle();
        assertFalse(toggle.isFocusable());assertFalse(next.isFocusable());assertTrue(stop.requestFocusFromTouch());assertSame("A single available video action still has a way out",((View)stop.getParent()).findViewWithTag("queueClear"),stop.focusSearch(View.FOCUS_UP));
        fixture.queue.clear();shadowOf(Looper.getMainLooper()).idle();focus=dialog.getCurrentFocus();assertNotNull(focus);assertTrue("Clearing the queue must not leave focus on a disabled transport",focus.isEnabled());assertNotSame(stop,focus);
    }
    @Test public void tabFocusDoesNotSwitchContentAndLooksDifferentFromTheCurrentTab()throws Exception{
        TextView podcast=(TextView)fixture.field(panel,"podcastTab"),video=(TextView)fixture.field(panel,"videoTab");
        assertTrue(podcast.isSelected());assertFalse(video.isSelected());assertTrue(podcast.getContentDescription().toString().contains("目前"));assertTrue(video.getContentDescription().toString().contains("確認"));
        assertTrue("Current tab is marked by a leading dot",podcast.getText().toString().startsWith("●"));assertTrue("Current tab keeps the requested white surface",surfaceLightness(podcast)>.85);
        assertTrue(video.requestFocusFromTouch());fixture.settle();assertFalse("Moving focus must not switch the visible library",(Boolean)fixture.field(panel,"videoMode"));assertTrue(podcast.isSelected());assertFalse(video.isSelected());
        assertTrue("Focused inactive tab has a visible translucent white fill",surfaceLightness(video)>.2);
        assertTrue("Focus remains distinct from the opaque current tab",surfaceLightness(video)<surfaceLightness(podcast)*.6);
        assertEquals("Translucent white focus keeps its caption readable",0xFF142D20,video.getCurrentTextColor());
        View close=dialog.getWindow().getDecorView().findViewWithTag("queueClose");assertTrue(close.requestFocusFromTouch());fixture.settle();
        assertTrue("Leaving the segment removes the inactive-side preview",surfaceLightness(video)<.2);assertTrue(surfaceLightness(podcast)>.85);
        assertTrue(video.requestFocusFromTouch());
        assertTrue(video.performClick());fixture.settle();assertTrue((Boolean)fixture.field(panel,"videoMode"));assertTrue(video.isSelected());assertFalse(podcast.isSelected());assertTrue(video.getContentDescription().toString().contains("目前"));assertTrue(((TextView)fixture.field(panel,"libraryHeading")).getText().toString().contains("看"));
        assertTrue(podcast.requestFocusFromTouch());fixture.settle();assertTrue((Boolean)fixture.field(panel,"videoMode"));assertTrue(surfaceLightness(podcast)<surfaceLightness(video)*.6);
        assertTrue(podcast.performClick());fixture.settle();assertFalse((Boolean)fixture.field(panel,"videoMode"));assertTrue(podcast.isSelected());
    }
    @Test public void tabRightReachesTheCircularCloseActionAndLeftReturnsWithoutChangingMode()throws Exception{
        TextView podcast=(TextView)fixture.field(panel,"podcastTab"),video=(TextView)fixture.field(panel,"videoTab");View close=dialog.getWindow().getDecorView().findViewWithTag("queueClose");
        assertNotNull("The close action has a stable navigation target",close);assertEquals(close.getWidth(),close.getHeight());assertTrue(close.getHeight()>=52);assertEquals("關閉播放清單",close.getContentDescription());
        assertTrue(podcast.requestFocusFromTouch());assertSame(video,podcast.focusSearch(View.FOCUS_RIGHT));assertTrue(video.requestFocusFromTouch());assertSame(close,video.focusSearch(View.FOCUS_RIGHT));assertTrue(close.requestFocusFromTouch());assertSame(video,close.focusSearch(View.FOCUS_LEFT));
        assertFalse("Moving across the tabs to Close does not switch content",(Boolean)fixture.field(panel,"videoMode"));assertTrue(podcast.isSelected());assertTrue(close.performClick());assertFalse(dialog.isShowing());
    }
    private double surfaceLightness(View view){
        android.view.ViewGroup group=(android.view.ViewGroup)view.getParent();
        Bitmap bitmap=Bitmap.createBitmap(group.getWidth(),group.getHeight(),Bitmap.Config.ARGB_8888);Canvas canvas=new Canvas(bitmap);canvas.drawColor(0xFF182126);
        drawSurface(canvas,group);
        for(int i=0;i<group.getChildCount();i++){View child=group.getChildAt(i);int save=canvas.save();canvas.translate(child.getLeft(),child.getTop());drawSurface(canvas,child);canvas.restoreToCount(save);}
        double light=Color.luminance(bitmap.getPixel(view.getLeft()+view.getWidth()/2,view.getTop()+8));bitmap.recycle();return light;
    }
    private void drawSurface(Canvas canvas,View view){android.graphics.drawable.Drawable background=view.getBackground();if(background==null)return;Rect bounds=new Rect(background.getBounds());background.setBounds(0,0,view.getWidth(),view.getHeight());background.draw(canvas);background.setBounds(bounds);}
    private NowPlayingIndicator findIndicator(FrameLayout row){for(int i=0;i<row.getChildCount();i++)if(row.getChildAt(i) instanceof NowPlayingIndicator)return (NowPlayingIndicator)row.getChildAt(i);return null;}
    private void assertReadableTextPair(TextView title,TextView publisher,View row){
        assertNotNull(title.getLayout());
        assertTrue("Every rendered CJK line fits inside its TextView",title.getLayout().getLineBottom(title.getLayout().getLineCount()-1)<=title.getHeight());
        assertTrue("Publisher starts after the title",title.getBottom()<publisher.getTop());
        assertTrue("Publisher fits inside the row",publisher.getBottom()<=row.getHeight());
    }

}
