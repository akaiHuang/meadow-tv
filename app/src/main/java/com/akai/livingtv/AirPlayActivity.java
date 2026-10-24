package com.akai.livingtv;

import android.app.Activity;
import android.content.*;
import android.graphics.*;
import android.graphics.drawable.*;
import android.net.nsd.*;
import android.os.*;
import android.util.TypedValue;
import android.view.*;
import android.widget.*;
import java.net.*;
import java.util.*;

/** Neutral casting guide. The separate receiver owns decoding and playback. */
public final class AirPlayActivity extends Activity {
    private final Handler handler=new Handler(Looper.getMainLooper());
    private RibbonView ribbon;
    private UiSounds sounds;
    private TextView status;
    private NsdManager nsd;
    private NsdManager.DiscoveryListener discovery;
    private boolean visible,searching,found;
    private int discoveryGeneration;
    private final Set<String> localAddresses=new HashSet<>();
    private final Runnable finishSearch=()->{stopDiscovery();if(!found&&visible)status.setText("接收狀態尚未確認");};

    @Override public void onCreate(Bundle state){
        super.onCreate(state);
        getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_FULLSCREEN|View.SYSTEM_UI_FLAG_HIDE_NAVIGATION|View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY|View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN|View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION|View.SYSTEM_UI_FLAG_LAYOUT_STABLE);
        sounds=new UiSounds(this);
        FrameLayout stage=new FrameLayout(this);
        FrameLayout root=new FrameLayout(this){
            @Override protected void onMeasure(int w,int h){int width=MeasureSpec.getSize(w),height=MeasureSpec.getSize(h);setMeasuredDimension(width,height);stage.measure(MeasureSpec.makeMeasureSpec(1920,MeasureSpec.EXACTLY),MeasureSpec.makeMeasureSpec(1080,MeasureSpec.EXACTLY));}
            @Override protected void onLayout(boolean changed,int l,int t,int r,int b){stage.layout(0,0,1920,1080);stage.setPivotX(0);stage.setPivotY(0);stage.setScaleX((r-l)/1920f);stage.setScaleY((b-t)/1080f);}
        };
        root.setBackgroundColor(0xFF03070C);root.addView(stage,new FrameLayout.LayoutParams(1920,1080));setContentView(root);
        ribbon=new RibbonView(this);place(stage,ribbon,0,0,1920,1080);
        status=text("投放指南",25,0xFFADBFD6,"sans-serif");status.setGravity(Gravity.RIGHT|Gravity.CENTER_VERTICAL);place(stage,status,1320,66,500,48);
        View symbol=new View(this){private final Paint paint=new Paint(Paint.ANTI_ALIAS_FLAG);@Override protected void onDraw(Canvas c){paint.setColor(0xFFD5E4FA);paint.setStyle(Paint.Style.STROKE);paint.setStrokeWidth(7);c.drawRoundRect(8,8,133,78,6,6,paint);paint.setStyle(Paint.Style.FILL);paint.setColor(0xFF03070C);c.drawRect(35,59,110,89,paint);paint.setColor(0xFFD5E4FA);Path p=new Path();p.moveTo(71,50);p.lineTo(29,105);p.lineTo(113,105);p.close();c.drawPath(p,paint);}};
        place(stage,symbol,104,246,145,115);
        TextView title=text("把螢幕，\n帶到這裡。",79,0xFFF0F4FA,"sans-serif-light");title.setLineSpacing(10,1);place(stage,title,102,385,900,240);
        TextView guide=text("在 iPhone 或 Mac 開啟「螢幕鏡像輸出」",29,0xFFBAC8D8,"sans-serif");place(stage,guide,106,638,1070,54);
        TextView device=text("Xiaomi-AirPlay",30,0xFFDCE5F0,"sans-serif");device.setPadding(30,0,30,0);device.setBackground(Glass.surface(0x70151B23,0x3338495B,38));place(stage,device,106,710,390,76);
        TextView settings=button("重新偵測",this::startDiscovery);place(stage,settings,106,869,320,92);
        TextView back=button("返回主畫面",this::returnHome);place(stage,back,448,869,270,92);
        TextView note=text("手機與電視需連上相同 Wi-Fi",23,0xFF7F90A6,"sans-serif");place(stage,note,106,996,1050,40);
        settings.setNextFocusRightId(back.getId());settings.setNextFocusLeftId(settings.getId());back.setNextFocusLeftId(settings.getId());back.setNextFocusRightId(back.getId());settings.requestFocus();
        UiSounds.disableDefault(getWindow().getDecorView());
    }
    private void place(FrameLayout parent,View child,int x,int y,int w,int h){FrameLayout.LayoutParams p=new FrameLayout.LayoutParams(w,h);p.leftMargin=x;p.topMargin=y;parent.addView(child,p);}
    private TextView text(String value,int size,int color,String family){TextView t=new TextView(this);t.setText(value);t.setTextSize(TypedValue.COMPLEX_UNIT_PX,size);t.setTextColor(color);t.setTypeface(Typeface.create(family,Typeface.NORMAL));t.setGravity(Gravity.CENTER_VERTICAL);t.setIncludeFontPadding(false);return t;}
    private TextView button(String value,Runnable action){
        TextView v=text(value,30,0xFFE3ECF9,"sans-serif-medium");v.setId(View.generateViewId());v.setFocusable(true);v.setClickable(true);v.setGravity(Gravity.CENTER);v.setDefaultFocusHighlightEnabled(false);
        StateListDrawable d=new StateListDrawable();GradientDrawable focused=Glass.surface(0xD9293546,0xFFF0F6FF,46);focused.setStroke(3,0xFFF0F6FF);d.addState(new int[]{android.R.attr.state_focused},focused);d.addState(new int[]{},Glass.surface(0x70131C28,0x333D4D61,46));v.setBackground(d);v.setOnClickListener(x->action.run());return v;
    }
    private void returnHome(){startActivity(new Intent(this,MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP).putExtra("fromShortcut",true));super.finish();}
    @Override public void onBackPressed(){returnHome();}
    @Override protected void onResume(){super.onResume();visible=true;sounds.start();ribbon.resume();startDiscovery();}
    @Override protected void onPause(){visible=false;stopDiscovery();ribbon.pause();sounds.stop();super.onPause();}
    @Override protected void onDestroy(){handler.removeCallbacksAndMessages(null);sounds.close();super.onDestroy();}
    @Override public boolean dispatchKeyEvent(KeyEvent e){sounds.key(e,this::getCurrentFocus);return super.dispatchKeyEvent(e);}
    private void startDiscovery(){
        stopDiscovery();found=false;status.setText("正在尋找接收器…");localAddresses.clear();
        try{Enumeration<NetworkInterface> interfaces=NetworkInterface.getNetworkInterfaces();while(interfaces!=null&&interfaces.hasMoreElements()){Enumeration<InetAddress> addresses=interfaces.nextElement().getInetAddresses();while(addresses.hasMoreElements())localAddresses.add(addresses.nextElement().getHostAddress());}}catch(Exception ignored){}
        nsd=(NsdManager)getSystemService(NSD_SERVICE);if(nsd==null){status.setText("接收狀態尚未確認");return;}
        final int generation=++discoveryGeneration;
        discovery=new NsdManager.DiscoveryListener(){
            public void onDiscoveryStarted(String type){}
            public void onDiscoveryStopped(String type){}
            public void onStartDiscoveryFailed(String type,int code){handler.post(()->{if(generation==discoveryGeneration)finishSearch.run();});}
            public void onStopDiscoveryFailed(String type,int code){}
            public void onServiceLost(NsdServiceInfo service){}
            public void onServiceFound(NsdServiceInfo service){
                if(!service.getServiceName().startsWith("Xiaomi-AirPlay"))return;
                try{nsd.resolveService(service,new NsdManager.ResolveListener(){
                    public void onResolveFailed(NsdServiceInfo info,int error){}
                    public void onServiceResolved(NsdServiceInfo info){handler.post(()->{if(visible&&searching&&generation==discoveryGeneration&&info.getHost()!=null&&localAddresses.contains(info.getHost().getHostAddress())){found=true;status.setText("●  接收器已偵測到");stopDiscovery();}});}
                });}catch(Exception ignored){}
            }
        };
        try{searching=true;nsd.discoverServices("_airplay._tcp.",NsdManager.PROTOCOL_DNS_SD,discovery);handler.postDelayed(finishSearch,8000);}catch(Exception e){searching=false;status.setText("接收狀態尚未確認");}
    }
    private void stopDiscovery(){++discoveryGeneration;handler.removeCallbacks(finishSearch);if(searching&&nsd!=null&&discovery!=null)try{nsd.stopServiceDiscovery(discovery);}catch(Exception ignored){}searching=false;}
}
