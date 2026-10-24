package com.akai.livingtv;

import android.graphics.*;
import android.graphics.drawable.Drawable;
import android.os.Looper;
import android.view.View;
import java.lang.reflect.Field;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.*;
import static org.junit.Assert.*;
import static org.robolectric.Shadows.shadowOf;

@RunWith(RobolectricTestRunner.class) @Config(sdk=28)
@GraphicsMode(GraphicsMode.Mode.NATIVE) @LooperMode(LooperMode.Mode.PAUSED)
public class GlassTest {
    @Test public void backgroundBlurPreservesLegacyPixelsAndKeepsCaptureAndDeliveryOnUi()throws Exception{
        int w=320,h=180;int[] source=new int[w*h];
        for(int y=0;y<h;y++)for(int x=0;x<w;x++)source[y*w+x]=Color.rgb((x*13+y*7)%256,(x*3+y*17)%256,(x*23+y*11)%256);
        Bitmap original=Bitmap.createBitmap(source,w,h,Bitmap.Config.ARGB_8888);
        AtomicInteger captures=new AtomicInteger();View scene=new View(RuntimeEnvironment.getApplication()){
            @Override protected void onDraw(Canvas canvas){assertSame(Looper.getMainLooper(),Looper.myLooper());captures.incrementAndGet();canvas.drawBitmap(original,0,0,null);}
        };scene.layout(0,0,w,h);
        int[] expected=source;
        for(int i=0;i<3;i++){expected=legacyPass(expected,w,h,true);expected=legacyPass(expected,w,h,false);}
        Drawable synchronous=Glass.snapshot(scene,scene.getResources(),24,new Rect(0,0,w,h));
        assertArrayEquals("scratch reuse must retain the previous six-pass image",expected,pixels(synchronous));
        AtomicReference<Drawable> result=new AtomicReference<>();
        Glass.request(scene,scene.getResources(),24,new Rect(0,0,w,h),drawable->{assertSame(Looper.getMainLooper(),Looper.myLooper());result.set(drawable);});
        assertEquals("capture is complete before request returns",2,captures.get());
        assertNull("blur delivery must not block the current UI task",result.get());await(result);
        assertArrayEquals(expected,pixels(result.get()));
    }
    @Test public void cancelledPanelDoesNotReceiveLateBlurWhileNextPanelStillCompletes()throws Exception{
        View scene=new View(RuntimeEnvironment.getApplication());scene.setBackgroundColor(Color.BLUE);scene.layout(0,0,320,180);
        AtomicInteger stale=new AtomicInteger();Glass.Request cancelled=Glass.request(scene,scene.getResources(),24,new Rect(0,0,320,180),drawable->stale.incrementAndGet());cancelled.cancel();cancelled.cancel();
        AtomicReference<Drawable> next=new AtomicReference<>();Glass.request(scene,scene.getResources(),24,new Rect(0,0,320,180),next::set);await(next);
        assertEquals("closing a panel must suppress its worker result",0,stale.get());assertNotNull(next.get());
    }
    @Test public void sharedSceneBlursOnceAndCropsEachPanelFromItsOwnLocation()throws Exception{
        AtomicInteger captures=new AtomicInteger();View view=new View(RuntimeEnvironment.getApplication()){@Override protected void onDraw(Canvas canvas){captures.incrementAndGet();Paint p=new Paint();p.setColor(Color.RED);canvas.drawRect(0,0,160,180,p);p.setColor(Color.BLUE);canvas.drawRect(160,0,320,180,p);}};view.layout(0,0,320,180);
        AtomicReference<Glass.Scene> value=new AtomicReference<>();Glass.requestScene(view,view.getResources(),value::set);long until=System.currentTimeMillis()+5000;while(value.get()==null&&System.currentTimeMillis()<until){Thread.sleep(10);shadowOf(Looper.getMainLooper()).idle();}assertNotNull(value.get());
        Bitmap left=Bitmap.createBitmap(120,180,Bitmap.Config.ARGB_8888),right=Bitmap.createBitmap(120,180,Bitmap.Config.ARGB_8888);Drawable a=value.get().panel(new Rect(0,0,120,180),20),b=value.get().control(new Rect(200,0,320,180),40);a.setBounds(0,0,120,180);b.setBounds(0,0,120,180);a.draw(new Canvas(left));b.draw(new Canvas(right));
        assertEquals("Backdrop and multiple materials share one scene capture",1,captures.get());assertTrue(Color.red(left.getPixel(60,90))>Color.blue(left.getPixel(60,90)));assertTrue(Color.blue(right.getPixel(60,90))>Color.red(right.getPixel(60,90)));
    }
    @Test public void fadingGlassFadesTheTintAndSpecularRimAsOneMaterial()throws Exception{
        View view=new View(RuntimeEnvironment.getApplication());view.setBackgroundColor(Color.rgb(140,170,190));view.layout(0,0,320,180);
        Drawable glass=Glass.snapshot(view,view.getResources(),28,new Rect(0,0,320,180));glass.setBounds(0,0,320,180);
        Bitmap full=Bitmap.createBitmap(320,180,Bitmap.Config.ARGB_8888);glass.draw(new Canvas(full));assertEquals(255,Color.alpha(full.getPixel(160,90)));
        glass.setAlpha(128);Bitmap half=Bitmap.createBitmap(320,180,Bitmap.Config.ARGB_8888);glass.draw(new Canvas(half));assertEquals("The layered material should fade as one surface",128,Color.alpha(half.getPixel(160,90)));
        glass.setAlpha(0);Bitmap hidden=Bitmap.createBitmap(320,180,Bitmap.Config.ARGB_8888);glass.draw(new Canvas(hidden));int[] pixels=new int[320*180];hidden.getPixels(pixels,0,320,0,0,320,180);for(int pixel:pixels)assertEquals("Hidden glass must not leave a bright border",0,Color.alpha(pixel));
    }
    @Test public void transparentSurfaceDoesNotAcquireAnUnrequestedBodyFill(){
        Drawable outline=Glass.surface(Color.TRANSPARENT,Color.WHITE,24);outline.setBounds(0,0,120,80);Bitmap result=Bitmap.createBitmap(120,80,Bitmap.Config.ARGB_8888);outline.draw(new Canvas(result));assertEquals("Focus foregrounds must keep the artwork visible",0,Color.alpha(result.getPixel(60,40)));
    }
    @Test public void crispRimBendsSceneFeaturesNonlinearlyAlongRotatingCornerNormals()throws Exception{
        int[] colors=new int[320*180];for(int y=0;y<180;y++)for(int x=0;x<320;x++)colors[y*320+x]=Color.rgb(Math.round(x*255/319f),y,20);
        Bitmap source=Bitmap.createBitmap(colors,320,180,Bitmap.Config.ARGB_8888);View view=new View(RuntimeEnvironment.getApplication()){@Override protected void onDraw(Canvas canvas){canvas.drawBitmap(source,0,0,null);}};view.layout(0,0,320,180);
        Drawable glass=Glass.snapshot(view,view.getResources(),48,new Rect(0,0,320,180));glass.setBounds(0,0,320,180);
        Bitmap rim=(Bitmap)field(glass,"rimAtlas");float[] vertices=(float[])field(glass,"rimVertices");int columns=rim.getWidth(),top=-1,corner=-1;float closest=Float.MAX_VALUE;
        for(int c=0;c<columns;c++){float x=vertices[c*2],y=vertices[c*2+1];if(Math.abs(y)<.01f&&Math.abs(x-160)<closest){closest=Math.abs(x-160);top=c;}if(x>296&&y>8&&y<25)corner=c;}
        assertTrue(top>=0);assertTrue(corner>=0);
        int middle=rim.getHeight()/2,last=rim.getHeight()-1;
        assertEquals("The outer lip blends into the body rather than leaving an opaque strip",0,Color.alpha(rim.getPixel(top,0)));assertEquals(0,Color.alpha(rim.getPixel(top,last)));
        float innerY=vertices[(last*columns+top)*2+1];
        assertTrue("The middle of the fine lip still samples farther into the source gradient than the undisplaced inner edge",Color.green(rim.getPixel(top,middle))>innerY+2);
        float cornerX=vertices[(middle*columns+corner)*2],cornerY=vertices[(middle*columns+corner)*2+1];int refracted=rim.getPixel(corner,middle);
        assertTrue("Top-right curvature bends the source horizontally",Color.red(refracted)<cornerX*255/319f-2);
        assertTrue("The same curved corner also bends the source vertically",Color.green(refracted)>cornerY+1);
        Bitmap rendered=Bitmap.createBitmap(320,180,Bitmap.Config.ARGB_8888);glass.draw(new Canvas(rendered));
        Field atlas=glass.getClass().getDeclaredField("rimAtlas");atlas.setAccessible(true);atlas.set(glass,null);Bitmap flat=Bitmap.createBitmap(320,180,Bitmap.Config.ARGB_8888);glass.draw(new Canvas(flat));
        int depth=Math.round((float)field(glass,"rimThickness")/2f);
        assertNotEquals("The warped texture must change the actual Canvas pixels, rather than merely compute an unused atlas",flat.getPixel(160,depth),rendered.getPixel(160,depth));
    }
    @Test public void sceneSharesOneBoundedCrispSampleAndUsesTinyRimAtlases()throws Exception{
        View view=new View(RuntimeEnvironment.getApplication());view.setBackgroundColor(Color.CYAN);view.layout(0,0,1920,1080);
        AtomicReference<Glass.Scene> result=new AtomicReference<>();Glass.requestScene(view,view.getResources(),result::set);long until=System.currentTimeMillis()+5000;while(result.get()==null&&System.currentTimeMillis()<until){Thread.sleep(10);shadowOf(Looper.getMainLooper()).idle();}assertNotNull(result.get());
        Drawable panel=result.get().panel(new Rect(180,140,1580,890),54),pill=result.get().control(new Rect(1500,100,1800,180),40);panel.setBounds(0,0,1400,750);pill.setBounds(0,0,300,80);
        assertSame("Multiple surfaces share one original sample",field(panel,"clear"),field(pill,"clear"));assertSame(field(panel,"bitmap"),field(pill,"bitmap"));
        Object clear=field(panel,"clear");assertTrue(((int[])field(clear,"pixels")).length<=320*180);
        for(Drawable surface:new Drawable[]{panel,pill}){Bitmap atlas=(Bitmap)field(surface,"rimAtlas");assertTrue("The perimeter texture stays below 5 KiB instead of allocating a full panel image",atlas.getByteCount()<=177*7*4);assertTrue(((float[])field(surface,"rimVertices")).length<=177*7*2);}
        View tall=new View(RuntimeEnvironment.getApplication());tall.setBackgroundColor(Color.RED);tall.layout(0,0,1200,4000);Drawable portrait=Glass.snapshot(tall,tall.getResources(),40,new Rect(0,0,1200,4000));
        assertTrue("Tall dialogs must respect the same capture memory budget",((Bitmap)field(portrait,"bitmap")).getByteCount()<=320*180*4);
    }
    @Test public void rimStaysThreePixelsForSmallAndLargeControlsAndPanelsAfterResize()throws Exception{
        View view=new View(RuntimeEnvironment.getApplication());view.setBackgroundColor(Color.CYAN);view.layout(0,0,1920,1080);
        AtomicReference<Glass.Scene> result=new AtomicReference<>();Glass.requestScene(view,view.getResources(),result::set);long until=System.currentTimeMillis()+5000;while(result.get()==null&&System.currentTimeMillis()<until){Thread.sleep(10);shadowOf(Looper.getMainLooper()).idle();}assertNotNull(result.get());
        Drawable small=result.get().control(new Rect(30,30,134,134),52),large=result.get().control(new Rect(200,40,800,640),180),panel=result.get().panel(new Rect(288,162,1632,918),54);
        Drawable[] surfaces={small,large,panel};int[][] sizes={{104,104},{600,600},{1344,756}};
        for(int i=0;i<surfaces.length;i++){
            Drawable surface=surfaces[i];surface.setBounds(0,0,sizes[i][0],sizes[i][1]);assertFixedRim(surface,0);
            surface.setBounds(10,20,sizes[i][0]/2+10,sizes[i][1]/2+20);assertFixedRim(surface,20);
        }
    }
    private static void assertFixedRim(Drawable surface,int top)throws Exception{
        assertEquals("Shape dimensions must not scale the glass edge",3f,(float)field(surface,"rimThickness"),.001f);
        Bitmap atlas=(Bitmap)field(surface,"rimAtlas");float[] vertices=(float[])field(surface,"rimVertices");int lastRow=(atlas.getHeight()-1)*atlas.getWidth();
        assertEquals(top,vertices[1],.001f);assertEquals("The actual mesh must occupy only a three-pixel band",top+3,vertices[lastRow*2+1],.001f);
    }
    private static Object field(Object value,String name)throws Exception{Field field=value.getClass().getDeclaredField(name);field.setAccessible(true);return field.get(value);}
    private static void await(AtomicReference<Drawable> value)throws Exception{
        long until=System.currentTimeMillis()+5000;while(value.get()==null&&System.currentTimeMillis()<until){Thread.sleep(10);shadowOf(Looper.getMainLooper()).idle();}assertNotNull("worker delivers within the test deadline",value.get());
    }
    private static int[] pixels(Drawable drawable)throws Exception{
        Field field=drawable.getClass().getDeclaredField("bitmap");field.setAccessible(true);Bitmap bitmap=(Bitmap)field.get(drawable);int[] result=new int[bitmap.getWidth()*bitmap.getHeight()];bitmap.getPixels(result,0,bitmap.getWidth(),0,0,bitmap.getWidth(),bitmap.getHeight());return result;
    }
    // Independent reference for the old allocation-heavy six-pass implementation.
    private static int[] legacyPass(int[] input,int width,int height,boolean horizontal){
        int[] output=new int[input.length];int lines=horizontal?height:width,length=horizontal?width:height;
        for(int line=0;line<lines;line++)for(int x=0;x<length;x++){
            int r=0,g=0,b=0;for(int delta=-7;delta<=7;delta++){int sample=Math.max(0,Math.min(length-1,x+delta));int c=input[horizontal?line*width+sample:sample*width+line];r+=(c>>>16)&255;g+=(c>>>8)&255;b+=c&255;}
            output[horizontal?line*width+x:x*width+line]=0xff000000|((r/15)<<16)|((g/15)<<8)|(b/15);
        }
        return output;
    }
}
