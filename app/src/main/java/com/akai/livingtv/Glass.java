package com.akai.livingtv;

import android.content.res.Resources;
import android.graphics.*;
import android.graphics.drawable.*;
import android.os.Handler;
import android.os.Looper;
import android.os.Process;
import android.view.View;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/** A small, one-shot CPU blur. Never samples another app or keeps a render loop. */
final class Glass {
    static final float PANEL_RADIUS=54f;
    private static final Handler MAIN=new Handler(Looper.getMainLooper());
    // At most one active blur and one waiting capture. Idle panels retain no worker thread.
    private static final ThreadPoolExecutor WORKER=new ThreadPoolExecutor(1,1,10,TimeUnit.SECONDS,
            new ArrayBlockingQueue<>(1),task->{Thread thread=new Thread(()->{Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND);task.run();},"meadow-glass");thread.setDaemon(true);return thread;});
    static { WORKER.allowCoreThreadTimeOut(true); }

    static GradientDrawable surface(int fill,int stroke,float radius) {
        int opacity=Color.alpha(fill);
        GradientDrawable d=opacity>0&&opacity<240?new Surface(radius):new GradientDrawable();
        if(opacity>0&&opacity<240){
            // A resting translucent control gets a small light-to-clear variation.
            // Opaque focus fills and transparent foreground outlines stay literal.
            int top=(Math.min(255,opacity+10)<<24)|(fill&0x00FFFFFF);
            int bottom=(Math.max(0,opacity-8)<<24)|(fill&0x00FFFFFF);
            d.setColors(new int[]{top,bottom});d.setOrientation(GradientDrawable.Orientation.TL_BR);
        }else d.setColor(fill);
        d.setCornerRadius(radius);d.setStroke(1,stroke);return d;
    }
    /** A static pill rim, used while a captured backdrop is unavailable or unnecessary. */
    private static final class Surface extends GradientDrawable {
        private final float radius;private final Paint light=new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF line=new RectF();
        Surface(float radius){this.radius=radius;light.setStyle(Paint.Style.STROKE);light.setStrokeWidth(1);}
        @Override protected void onBoundsChange(Rect bounds){super.onBoundsChange(bounds);line.set(bounds);line.inset(1.5f,1.5f);light.setShader(new LinearGradient(bounds.left,bounds.top,bounds.right,bounds.bottom,new int[]{0x70F4FCFF,0x08E6F4F8,0x34DDEDF2},new float[]{0,.55f,1},Shader.TileMode.CLAMP));}
        @Override public void draw(Canvas canvas){super.draw(canvas);canvas.drawRoundRect(line,Math.max(0,radius-1.5f),Math.max(0,radius-1.5f),light);}
        @Override public void setAlpha(int alpha){super.setAlpha(alpha);light.setAlpha(alpha);}
    }
    static Drawable snapshot(View scene,Resources resources,float radius) {
        return snapshot(scene,resources,radius,new Rect(400,240,1520,900));
    }
    static Drawable snapshot(View scene,Resources resources,float radius,Rect region) {
        Bitmap bitmap=capture(scene,region);ClearImage clear=new ClearImage(bitmap);blur(bitmap,null);return new Frost(bitmap,clear,radius);
    }

    /**
     * Captures on the calling UI thread, then blurs off-thread. The callback is on the UI
     * thread. A newer request replaces any queued capture; cancel when a panel closes.
     * Show a static surface while waiting so opening animations need not wait for the blur.
     */
    static Request request(View scene,Resources resources,float radius,Consumer<Drawable> ready) {
        return request(scene,resources,radius,new Rect(400,240,1520,900),ready);
    }
    /** One full-scene capture shared by the dimmed backdrop and all panel materials. */
    static Request requestScene(View scene,Resources resources,Consumer<Scene> ready) {
        return requestScene(scene,resources,7,ready);
    }
    static Request requestScene(View scene,Resources resources,int blurRadius,Consumer<Scene> ready) {
        int width=Math.max(1,scene.getWidth()),height=Math.max(1,scene.getHeight());
        return request(scene,resources,0,new Rect(0,0,width,height),blurRadius,blurred->
                ready.accept(new Scene(((Frost)blurred).bitmap,((Frost)blurred).clear,width,height)));
    }
    static final class Scene {
        private final Bitmap bitmap;private final ClearImage clear;private final int width,height;
        private Scene(Bitmap bitmap,ClearImage clear,int width,int height){this.bitmap=bitmap;this.clear=clear;this.width=width;this.height=height;}
        Drawable backdrop(){return new Frost(bitmap,null,0,new RectF(0,0,bitmap.getWidth(),bitmap.getHeight()),1);}
        Drawable panel(Rect region,float radius){return material(region,radius,0);}
        Drawable control(Rect region,float radius){return material(region,radius,2);}
        private Drawable material(Rect region,float radius,int kind){
            if(region.width()<=0||region.height()<=0||region.left<0||region.top<0||region.right>width||region.bottom>height)throw new IllegalArgumentException("Panel must stay inside the captured scene");
            return new Frost(bitmap,clear,radius,new RectF(region.left*bitmap.getWidth()/(float)width,region.top*bitmap.getHeight()/(float)height,region.right*bitmap.getWidth()/(float)width,region.bottom*bitmap.getHeight()/(float)height),kind);
        }
    }
    static Request request(View scene,Resources resources,float radius,Rect region,Consumer<Drawable> ready) {
        return request(scene,resources,radius,region,7,ready);
    }
    private static Request request(View scene,Resources resources,float radius,Rect region,int blurRadius,Consumer<Drawable> ready) {
        if(Looper.myLooper()!=Looper.getMainLooper())throw new IllegalStateException("Glass capture must run on the UI thread");
        if(ready==null)throw new IllegalArgumentException("Glass callback is required");
        if(blurRadius<1||blurRadius>7)throw new IllegalArgumentException("Blur radius must stay within the small sample budget");
        Request request=new Request(capture(scene,region),radius,blurRadius,ready);
        // Only UI callers enqueue. Removing a queued capture before execute leaves room
        // even if the worker concurrently picks it up; running work owns its own bitmap.
        Runnable displaced=WORKER.getQueue().poll();
        if(displaced instanceof Request)((Request)displaced).cancel();
        WORKER.execute(request);
        return request;
    }
    private static Bitmap capture(View scene,Rect region) {
        if(region.width()<=0||region.height()<=0)throw new IllegalArgumentException("Glass region must have positive dimensions");
        float scale=Math.min(320f/region.width(),180f/region.height());
        int w=Math.max(1,Math.round(region.width()*scale)),h=Math.max(1,Math.round(region.height()*scale));
        Bitmap bitmap=Bitmap.createBitmap(w,h,Bitmap.Config.ARGB_8888);
        Canvas canvas=new Canvas(bitmap);canvas.scale(w/(float)region.width(),h/(float)region.height());canvas.translate(-region.left,-region.top);scene.draw(canvas);
        return bitmap;
    }
    private static boolean blur(Bitmap bitmap,Request request) {
        int w=bitmap.getWidth(),h=bitmap.getHeight();
        int[] pixels=new int[w*h],scratch=new int[w*h];bitmap.getPixels(pixels,0,w,0,0,w,h);
        for(int i=0;i<3;i++) {
            if(request!=null&&request.cancelled)return false;
            pass(pixels,scratch,w,h,true,request==null?7:request.blurRadius);
            if(request!=null&&request.cancelled)return false;
            pass(scratch,pixels,w,h,false,request==null?7:request.blurRadius);
        }
        if(request!=null&&request.cancelled)return false;
        bitmap.setPixels(pixels,0,w,0,0,w,h);return true;
    }
    private static void pass(int[] a,int[] b,int w,int h,boolean horizontal,int r) {
        int lines=horizontal?h:w,length=horizontal?w:h,window=r*2+1;
        for(int line=0;line<lines;line++) {
            int red=0,green=0,blue=0;
            for(int k=-r;k<=r;k++){int c=a[at(line,Math.max(0,Math.min(length-1,k)),w,horizontal)];red+=(c>>16)&255;green+=(c>>8)&255;blue+=c&255;}
            for(int x=0;x<length;x++) {
                b[at(line,x,w,horizontal)]=0xFF000000|((red/window)<<16)|((green/window)<<8)|(blue/window);
                int old=a[at(line,Math.max(0,x-r),w,horizontal)],next=a[at(line,Math.min(length-1,x+r+1),w,horizontal)];
                red+=((next>>16)&255)-((old>>16)&255);green+=((next>>8)&255)-((old>>8)&255);blue+=(next&255)-(old&255);
            }
        }
    }
    private static int at(int line,int x,int width,boolean horizontal){return horizontal?line*width+x:x*width+line;}

    /** One immutable, crisp low-resolution sample shared by every rim in a scene. */
    private static final class ClearImage {
        final int width,height;final int[] pixels;
        ClearImage(Bitmap source){width=source.getWidth();height=source.getHeight();pixels=new int[width*height];source.getPixels(pixels,0,width,0,0,width,height);}
        int sample(float x,float y){
            x=Math.max(0,Math.min(width-1,x));y=Math.max(0,Math.min(height-1,y));
            int x0=(int)x,y0=(int)y,x1=Math.min(width-1,x0+1),y1=Math.min(height-1,y0+1);float dx=x-x0,dy=y-y0;
            int a=pixels[y0*width+x0],b=pixels[y0*width+x1],c=pixels[y1*width+x0],d=pixels[y1*width+x1],result=0xFF000000;
            for(int shift=0;shift<=16;shift+=8){float top=((a>>shift)&255)*(1-dx)+((b>>shift)&255)*dx,bottom=((c>>shift)&255)*(1-dx)+((d>>shift)&255)*dx;result|=Math.round(top*(1-dy)+bottom*dy)<<shift;}
            return result;
        }
    }

    static final class Request implements Runnable {
        private Bitmap captured,completed;private ClearImage completedClear;
        private final float radius;
        private final int blurRadius;
        private Consumer<Drawable> callback;
        private volatile boolean cancelled;
        private final Runnable delivery=this::deliver;
        private Request(Bitmap captured,float radius,int blurRadius,Consumer<Drawable> callback){this.captured=captured;this.radius=radius;this.blurRadius=blurRadius;this.callback=callback;}
        void cancel() {
            synchronized(this) {
                cancelled=true;callback=null;
                if(captured!=null){captured.recycle();captured=null;}
                if(completed!=null){completed.recycle();completed=null;}
                completedClear=null;
                MAIN.removeCallbacks(delivery);
            }
            WORKER.remove(this);
        }
        @Override public void run() {
            Bitmap bitmap;
            synchronized(this){if(cancelled)return;bitmap=captured;captured=null;}
            try {
                ClearImage clear=new ClearImage(bitmap);
                if(!blur(bitmap,this))return;
                synchronized(this){if(cancelled)return;completed=bitmap;completedClear=clear;bitmap=null;MAIN.post(delivery);}
            } finally { if(bitmap!=null)bitmap.recycle(); }
        }
        private void deliver() {
            Bitmap bitmap;ClearImage clear;Consumer<Drawable> ready;
            synchronized(this){if(cancelled)return;bitmap=completed;completed=null;clear=completedClear;completedClear=null;ready=callback;callback=null;}
            if(bitmap!=null){if(ready!=null)ready.accept(new Frost(bitmap,clear,radius));else bitmap.recycle();}
        }
    }
    private static final class Frost extends Drawable {
        private static final int RIM_LAYERS=6;
        private static final float RIM_THICKNESS=3f,RIM_BEND=6f;
        private final Bitmap bitmap;private final ClearImage clear;private final float radius;
        private final Paint art=new Paint(Paint.ANTI_ALIAS_FLAG|Paint.FILTER_BITMAP_FLAG),tint=new Paint(Paint.ANTI_ALIAS_FLAG),edge=new Paint(Paint.ANTI_ALIAS_FLAG),inner=new Paint(Paint.ANTI_ALIAS_FLAG),sheen=new Paint(Paint.ANTI_ALIAS_FLAG),lens=new Paint(Paint.ANTI_ALIAS_FLAG|Paint.FILTER_BITMAP_FLAG);
        private final RectF sample;private final int material;
        private final RectF fillBounds=new RectF(),edgeBounds=new RectF(),innerBounds=new RectF();
        private final Matrix matrix=new Matrix();private int alpha=255;
        private Bitmap rimAtlas;private float[] rimVertices;private int rimSegments;private float rimThickness;
        Frost(Bitmap bitmap,ClearImage clear,float radius){this(bitmap,clear,radius,new RectF(0,0,bitmap.getWidth(),bitmap.getHeight()),0);}
        Frost(Bitmap bitmap,ClearImage clear,float radius,RectF sample,int material){
            this.bitmap=bitmap;this.clear=clear;this.radius=radius;this.sample=sample;this.material=material;
            art.setShader(new BitmapShader(bitmap,Shader.TileMode.CLAMP,Shader.TileMode.CLAMP));
            lens.setAlpha(160);
            edge.setStyle(Paint.Style.STROKE);edge.setStrokeWidth(1.2f);inner.setStyle(Paint.Style.STROKE);inner.setStrokeWidth(1);
        }
        @Override protected void onBoundsChange(Rect bounds){
            float sx=bounds.width()/sample.width(),sy=bounds.height()/sample.height();
            matrix.setScale(sx,sy);matrix.postTranslate(bounds.left-sample.left*sx,bounds.top-sample.top*sy);art.getShader().setLocalMatrix(matrix);
            fillBounds.set(bounds);edgeBounds.set(bounds);edgeBounds.inset(.7f,.7f);innerBounds.set(bounds);innerBounds.inset(2.4f,2.4f);
            buildRim(bounds);
            int top=material==1?0x18303A42:material==2?0x3818232C:0x80202934;
            int bottom=material==1?0x18303A42:material==2?0x66121A22:0x9C141D28;
            tint.setShader(new LinearGradient(0,bounds.top,0,Math.max(bounds.top+1,bounds.bottom),top,bottom,Shader.TileMode.CLAMP));
            edge.setShader(new LinearGradient(bounds.left,bounds.top,bounds.right,bounds.bottom,new int[]{0xC4F2FFFF,0x4FB7D7E5,0x0ADCE8E9,0x75FFD9B3},new float[]{0,.22f,.63f,1},Shader.TileMode.CLAMP));
            inner.setShader(new LinearGradient(bounds.right,bounds.top,bounds.left,bounds.bottom,new int[]{0x02FFFFFF,0x48E9F7FF,0x04FFFFFF,0x42FFE7CF},new float[]{0,.3f,.65f,1},Shader.TileMode.CLAMP));
            sheen.setShader(new RadialGradient(bounds.left+bounds.width()*.16f,bounds.top+bounds.height()*.08f,Math.max(1,Math.max(bounds.width(),bounds.height())*.75f),new int[]{material==2?0x1CFFFFFF:0x12FFFFFF,0x00FFFFFF},new float[]{0,1},Shader.TileMode.CLAMP));
        }
        /**
         * Unwrap the curved rim into a tiny perimeter-by-depth texture. Each depth
         * samples a different distance along the rounded rectangle's surface normal.
         * Mapping that atlas back onto the ring bends real scene features at corners;
         * unlike scaling one blurred shader, this is a non-linear image displacement.
         * Geometry and pixels are computed only when bounds change, never in draw().
         */
        private void buildRim(Rect bounds){
            rimAtlas=null;rimVertices=null;rimSegments=0;
            if(clear==null||material==1||bounds.width()==0||bounds.height()==0||radius<=0)return;
            float r=Math.min(radius,Math.min(bounds.width(),bounds.height())/2f);
            // Keep the visible lip fixed for capsules and large panels alike.
            // Distortion distance is independent of this quiet three-pixel band.
            rimThickness=RIM_THICKNESS;
            RimContour contour=new RimContour();float left=bounds.left,top=bounds.top,right=bounds.right,bottom=bounds.bottom;
            contour.point(left+r,top,0,-1);
            contour.line(left+r,top,right-r,top,0,-1);
            contour.arc(right-r,top+r,r,-90);
            contour.line(right,top+r,right,bottom-r,1,0);
            contour.arc(right-r,bottom-r,r,0);
            contour.line(right-r,bottom,left+r,bottom,0,1);
            contour.arc(left+r,bottom-r,r,90);
            contour.line(left,bottom-r,left,top+r,-1,0);
            contour.arc(left+r,top+r,r,180);
            rimSegments=contour.size-1;
            int width=rimSegments+1;int[] colors=new int[width*(RIM_LAYERS+1)];
            rimVertices=new float[colors.length*2];
            for(int row=0;row<=RIM_LAYERS;row++){
                float fraction=row/(float)RIM_LAYERS,depth=rimThickness*fraction;
                // No displacement at either boundary; strongest bending in the
                // middle of the curved lip. The normal rotates around each corner.
                float curve=(float)Math.sin(Math.PI*fraction),bend=curve*RIM_BEND;
                for(int column=0;column<width;column++){
                    int p=column*4,index=row*width+column;float nx=contour.points[p+2],ny=contour.points[p+3];
                    float x=contour.points[p]-nx*depth,y=contour.points[p+1]-ny*depth;
                    rimVertices[index*2]=x;rimVertices[index*2+1]=y;
                    int color=clear.sample(sample.left+(x-nx*bend-bounds.left)*sample.width()/bounds.width(),sample.top+(y-ny*bend-bounds.top)*sample.height()/bounds.height());
                    // A tapered lip lets the blurred body blend through both edges,
                    // avoiding a solid high-contrast strip that reads as chrome.
                    colors[index]=(Math.round(230*curve)<<24)|(color&0x00FFFFFF);
                }
            }
            // At most 177 x 7 pixels (< 5 KiB), even for a full-screen panel.
            // All surfaces continue sharing the original <=320 x 180 scene image.
            rimAtlas=Bitmap.createBitmap(colors,width,RIM_LAYERS+1,Bitmap.Config.ARGB_8888);
        }
        private static final class RimContour {
            final float[] points=new float[177*4];int size;
            void point(float x,float y,float nx,float ny){int p=size++*4;points[p]=x;points[p+1]=y;points[p+2]=nx;points[p+3]=ny;}
            void line(float x0,float y0,float x1,float y1,float nx,float ny){int steps=Math.max(1,Math.min(32,(int)Math.ceil(Math.hypot(x1-x0,y1-y0)/48)));for(int i=1;i<=steps;i++){float t=i/(float)steps;point(x0+(x1-x0)*t,y0+(y1-y0)*t,nx,ny);}}
            void arc(float cx,float cy,float radius,float start){for(int i=1;i<=12;i++){double angle=Math.toRadians(start+i*7.5f);float nx=(float)Math.cos(angle),ny=(float)Math.sin(angle);point(cx+radius*nx,cy+radius*ny,nx,ny);}}
        }
        @Override public void draw(Canvas canvas){
            if(alpha==0)return;
            // Idle drawing never allocates an offscreen layer. A caller explicitly
            // fading Drawable alpha needs one grouped layer so the rim fades too.
            int save=alpha<255?canvas.saveLayerAlpha(fillBounds,alpha):-1;
            canvas.drawRoundRect(fillBounds,radius,radius,art);canvas.drawRoundRect(fillBounds,radius,radius,tint);
            if(material!=1){canvas.drawRoundRect(fillBounds,radius,radius,sheen);if(rimAtlas!=null)canvas.drawBitmapMesh(rimAtlas,rimSegments,RIM_LAYERS,rimVertices,0,null,0,lens);canvas.drawRoundRect(edgeBounds,radius,radius,edge);canvas.drawRoundRect(innerBounds,Math.max(0,radius-2.4f),Math.max(0,radius-2.4f),inner);}
            if(save!=-1)canvas.restoreToCount(save);
        }
        @Override public void getOutline(Outline outline){outline.setRoundRect(getBounds(),radius);}
        @Override public void setAlpha(int alpha){this.alpha=Math.max(0,Math.min(255,alpha));invalidateSelf();}
        @Override public int getAlpha(){return alpha;}
        @Override public void setColorFilter(ColorFilter filter){art.setColorFilter(filter);tint.setColorFilter(filter);edge.setColorFilter(filter);inner.setColorFilter(filter);sheen.setColorFilter(filter);lens.setColorFilter(filter);invalidateSelf();}
        @Override public int getOpacity(){return PixelFormat.TRANSLUCENT;}
    }
}
