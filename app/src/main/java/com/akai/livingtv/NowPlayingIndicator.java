package com.akai.livingtv;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.view.View;

/** A playback-state equalizer, not an audio-amplitude meter. No work while hidden or paused. */
final class NowPlayingIndicator extends View {
    private static final long FRAME_MS=67; // At most 15 small-region updates per second.
    private static final float[][] LEVELS=new float[32][4];
    private static final float[] REST={.32f,.55f,.43f,.28f};
    static {for(int frame=0;frame<LEVELS.length;frame++)for(int bar=0;bar<4;bar++)LEVELS[frame][bar]=.25f+.75f*(float)Math.abs(Math.sin(frame*.29+bar*1.13));}
    private final Paint paint=new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF barBounds=new RectF();
    private boolean playing,animating,aggregatedVisible=true;
    private int phase;
    private final Runnable frame=new Runnable(){@Override public void run(){
        if(!animating)return;
        if(!canAnimate()){stopFrames();return;}
        phase=(phase+1)%LEVELS.length;invalidate();postDelayed(this,FRAME_MS);
    }};
    NowPlayingIndicator(Context context){super(context);paint.setColor(0xFFA5DDB2);setFocusable(false);setClickable(false);setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);}
    void setPlaybackState(String state){boolean next="playing".equals(state);playing=next;setContentDescription(next?"正在播放":"paused".equals(state)?"已暫停":"已停止");updateFrames();invalidate();}
    void setTint(int color){paint.setColor(color);invalidate();}
    private boolean canAnimate(){return playing&&isAttachedToWindow()&&aggregatedVisible&&getWindowVisibility()==VISIBLE&&isShown();}
    private void updateFrames(){
        if(frame==null)return;
        if(canAnimate()){if(!animating){animating=true;postDelayed(frame,FRAME_MS);}}
        else stopFrames();
    }
    private void stopFrames(){animating=false;removeCallbacks(frame);}
    @Override protected void onAttachedToWindow(){super.onAttachedToWindow();updateFrames();}
    @Override protected void onDetachedFromWindow(){stopFrames();super.onDetachedFromWindow();}
    @Override protected void onVisibilityChanged(View changedView,int visibility){super.onVisibilityChanged(changedView,visibility);updateFrames();}
    @Override public void onVisibilityAggregated(boolean visible){super.onVisibilityAggregated(visible);aggregatedVisible=visible;updateFrames();}
    @Override protected void onWindowVisibilityChanged(int visibility){super.onWindowVisibilityChanged(visibility);updateFrames();}
    @Override protected void onDraw(Canvas canvas){
        super.onDraw(canvas);float unit=getWidth()/7f,center=getHeight()/2f,maxHeight=getHeight()*.78f;
        for(int bar=0;bar<4;bar++){float level=playing?LEVELS[phase][bar]:REST[bar];float height=Math.max(3,maxHeight*level);barBounds.set(bar*unit*2,center-height/2,bar*unit*2+unit,center+height/2);canvas.drawRoundRect(barBounds,unit/2,unit/2,paint);}
    }
}
