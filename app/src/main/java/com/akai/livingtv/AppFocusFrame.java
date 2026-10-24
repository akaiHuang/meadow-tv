package com.akai.livingtv;

import android.graphics.*;
import android.graphics.drawable.Drawable;

/** Hollow focus light outside the artwork, never a thick inset border over a logo. */
final class AppFocusFrame extends Drawable {
    private final RectF frame=new RectF();private final Rect dirty=new Rect();
    private final Paint[] strokes=new Paint[4];private final int[] colors={0x10CFE5FF,0x25E8F3FF,0x50F1F8FF,0xF5FFFFFF};
    AppFocusFrame(){float[] widths={16,10,5,2.5f};for(int i=0;i<strokes.length;i++){Paint p=new Paint(Paint.ANTI_ALIAS_FLAG);p.setStyle(Paint.Style.STROKE);p.setStrokeWidth(widths[i]);p.setColor(colors[i]);strokes[i]=p;}}
    @Override protected void onBoundsChange(Rect bounds){frame.set(bounds);frame.inset(-5.5f,-5.5f);dirty.set(bounds);dirty.inset(-15,-15);}
    @Override public void draw(Canvas canvas){for(Paint p:strokes)canvas.drawRoundRect(frame,31,31,p);}
    @Override public Rect getDirtyBounds(){return dirty;}
    @Override public void setAlpha(int alpha){for(int i=0;i<strokes.length;i++)strokes[i].setAlpha(Color.alpha(colors[i])*alpha/255);invalidateSelf();}
    @Override public void setColorFilter(ColorFilter filter){for(Paint p:strokes)p.setColorFilter(filter);invalidateSelf();}
    @Override public int getOpacity(){return PixelFormat.TRANSLUCENT;}
}
