package com.akai.livingtv;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Path;
import android.view.View;

/** Static, balanced 64px weather artwork. No bitmaps, timers or animation. */
final class WeatherGlyph extends View {
    private static final int UNKNOWN=0,CLEAR=1,PARTLY=2,CLOUD=3,FOG=4,RAIN=5,SNOW=6,THUNDER=7;
    private static final float[] RAYS=new float[32];
    static {
        for(int n=0;n<8;n++){
            double angle=n*Math.PI/4;int at=n*4;
            RAYS[at]=(float)Math.cos(angle)*19;RAYS[at+1]=(float)Math.sin(angle)*19;
            RAYS[at+2]=(float)Math.cos(angle)*24;RAYS[at+3]=(float)Math.sin(angle)*24;
        }
    }
    private final Paint paint=new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path cloud=new Path(),moon=new Path(),bolt=new Path();
    private final Path mostlyClearCloud=new Path(),partlyCloud=new Path();
    private int code=-1;private boolean day=true,dark,filled;

    WeatherGlyph(Context context){
        super(context);setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
        paint.setStrokeCap(Paint.Cap.ROUND);paint.setStrokeJoin(Paint.Join.ROUND);
        cloud.moveTo(17,44);cloud.cubicTo(10,44,7,40,7,35);cloud.cubicTo(7,30,11,26,16,26);
        cloud.cubicTo(17,17,26,12,33,16);cloud.cubicTo(38,16,42,20,43,25);
        cloud.cubicTo(50,24,56,29,56,35);cloud.cubicTo(56,41,51,44,45,44);cloud.close();
        Matrix cloudPlacement=new Matrix();cloudPlacement.setScale(.68f,.68f);cloudPlacement.postTranslate(20,20);cloud.transform(cloudPlacement,mostlyClearCloud);
        cloudPlacement.setScale(.82f,.82f);cloudPlacement.postTranslate(9,11);cloud.transform(cloudPlacement,partlyCloud);
        moon.moveTo(43,15);moon.cubicTo(29,9,15,20,16,34);moon.cubicTo(17,47,33,53,45,43);
        moon.cubicTo(31,45,21,32,26,20);moon.cubicTo(30,15,36,13,43,15);moon.close();
        bolt.moveTo(35,40);bolt.lineTo(25,51);bolt.lineTo(33,51);bolt.lineTo(29,60);
        bolt.lineTo(43,46);bolt.lineTo(35,46);bolt.lineTo(40,40);bolt.close();
    }
    void focusTint(boolean on){if(dark!=on){dark=on;invalidate();}}
    void update(int weatherCode,boolean isDay){if(code!=weatherCode||day!=isDay){code=weatherCode;day=isDay;invalidate();}}
    void setFilled(boolean on){if(filled!=on){filled=on;invalidate();}}

    @Override protected void onDraw(Canvas canvas){
        super.onDraw(canvas);float side=Math.min(getWidth(),getHeight());if(side<=0)return;
        int save=canvas.save();canvas.translate((getWidth()-side)/2f,(getHeight()-side)/2f);canvas.scale(side/64f,side/64f);
        switch(kind(code)){
            case CLEAR:
                if(day)sun(canvas,32,31,1);else {drawMoon(canvas);if(filled){paint.setStyle(Paint.Style.FILL);paint.setColor(dark?0xFF345044:0xFFDDEAF2);canvas.drawCircle(49,18,1.6f,paint);}}
                break;
            case PARTLY:
                int skySave=canvas.save();if(!filled)canvas.clipOutPath(code==1?mostlyClearCloud:partlyCloud);
                if(day)sun(canvas,23,23,.73f);else {int moonSave=canvas.save();canvas.translate(1,0);canvas.scale(.7f,.7f);drawMoon(canvas);canvas.restoreToCount(moonSave);}
                canvas.restoreToCount(skySave);
                int cloudSave=canvas.save();canvas.translate(code==1?20:9,code==1?20:11);canvas.scale(code==1?.68f:.82f,code==1?.68f:.82f);drawCloud(canvas);canvas.restoreToCount(cloudSave);
                break;
            case CLOUD:
                canvas.translate(0,3);drawCloud(canvas);break;
            case FOG:
                int fogSave=canvas.save();canvas.translate(2,-2);canvas.scale(.94f,.94f);drawCloud(canvas);canvas.restoreToCount(fogSave);
                stroke(dark?0xFF42615F:0xFFB8CED8,filled?3.2f:3);canvas.drawLine(14,49,49,49,paint);canvas.drawLine(20,56,54,56,paint);break;
            case RAIN:
                drawCloud(canvas);rain(canvas,18,46);rain(canvas,31,46);rain(canvas,44,46);break;
            case SNOW:
                drawCloud(canvas);snow(canvas,21,53);snow(canvas,43,53);break;
            case THUNDER:
                drawCloud(canvas);rain(canvas,17,47);paint.setStyle(Paint.Style.FILL);paint.setColor(dark?0xFF926311:0xFFF2D185);canvas.drawPath(bolt,paint);break;
            default:
                stroke(dark?0xFF29443B:0xFFCFDDE5,3.2f);canvas.drawLine(23,32,41,32,paint);
        }
        canvas.restoreToCount(save);
    }
    private static int kind(int code){
        if(code==0)return CLEAR;if(code==1||code==2)return PARTLY;if(code==3)return CLOUD;
        if(code==45||code==48)return FOG;
        if(code==51||code==53||code==55||code==56||code==57||code==61||code==63||code==65||code==66||code==67||code==80||code==81||code==82)return RAIN;
        if(code==71||code==73||code==75||code==77||code==85||code==86)return SNOW;
        if(code==95||code==96||code==97||code==99)return THUNDER;return UNKNOWN;
    }
    private void sun(Canvas canvas,float x,float y,float scale){
        int save=canvas.save();canvas.translate(x,y);canvas.scale(scale,scale);
        int color=dark?0xFF936B22:0xFFF0D49A;
        if(filled){paint.setStyle(Paint.Style.FILL);paint.setColor(color);canvas.drawCircle(0,0,12,paint);stroke(dark?0xFF936B22:0xFFF7E6BC,1.6f);canvas.drawCircle(0,0,12,paint);}else {stroke(color,3);canvas.drawCircle(0,0,11.5f,paint);}
        stroke(color,filled?2.6f:3);canvas.drawLines(RAYS,paint);canvas.restoreToCount(save);
    }
    private void drawMoon(Canvas canvas){
        int color=dark?0xFF2C453B:0xFFDCE8F2;
        if(filled){paint.setStyle(Paint.Style.FILL);paint.setColor(color);canvas.drawPath(moon,paint);}else {stroke(color,3);canvas.drawPath(moon,paint);}
    }
    private void drawCloud(Canvas canvas){
        int color=dark?0xFF263F37:0xFFE0EAF1;
        if(filled){paint.setStyle(Paint.Style.FILL);paint.setColor(color);canvas.drawPath(cloud,paint);stroke(dark?0xFF263F37:0xB3F7FCFF,1.2f);canvas.drawPath(cloud,paint);}else {stroke(color,3);canvas.drawPath(cloud,paint);}
    }
    private void rain(Canvas canvas,float x,float y){stroke(dark?0xFF315D70:0xFFABCAD9,filled?3.2f:3);canvas.drawLine(x+2,y+1,x-1,y+9,paint);}
    private void snow(Canvas canvas,float x,float y){stroke(dark?0xFF365D67:0xFFC6DFEA,2.3f);canvas.drawLine(x,y-4,x,y+4,paint);canvas.drawLine(x-3.5f,y-2,x+3.5f,y+2,paint);canvas.drawLine(x-3.5f,y+2,x+3.5f,y-2,paint);}
    private void stroke(int color,float width){paint.setStyle(Paint.Style.STROKE);paint.setColor(color);paint.setStrokeWidth(width);}
}
