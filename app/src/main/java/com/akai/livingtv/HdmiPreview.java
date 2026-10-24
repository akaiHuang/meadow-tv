package com.akai.livingtv;

import android.content.*;
import android.graphics.*;
import android.graphics.drawable.*;
import android.media.tv.*;
import android.os.*;
import android.util.TypedValue;
import android.view.*;
import android.widget.*;
import java.lang.ref.WeakReference;
import java.util.*;

/** Experimental launcher-local TV surface. Never captures or retransmits HDMI video. */
final class HdmiPreview extends FrameLayout {
    private static WeakReference<HdmiPreview> active=new WeakReference<>(null);
    private final Handler handler=new Handler(Looper.getMainLooper());
    private final TextView status,label,hint;
    private final FrameLayout placeholder,caption;
    private final ImageView stateIcon;
    private final android.content.SharedPreferences prefs;
    private TvView video;
    private TvInputInfo input;
    private TvInputManager manager;
    private boolean listening,running,available;
    private final Runnable muteCheck=new Runnable(){public void run(){if(!listening||video==null)return;try{video.setStreamVolume(0f);}catch(Exception ignored){}MediaQueue q=MediaQueue.get(getContext());if(q.current()!=null&&q.current().provider.equals("podcast")&&(q.state.equals("playing")||q.state.equals("preparing")))handler.postDelayed(this,1000);}};
    private final Runnable timeout=()->{if(running&&!available)fail("選擇輸入來源","HDMI · 暫無預覽");};
    private final TvInputManager.TvInputCallback changes=new TvInputManager.TvInputCallback(){@Override public void onInputStateChanged(String id,int state){if(input==null||input.getId().equals(id)){if(state==TvInputManager.INPUT_STATE_DISCONNECTED){release();fallback("選擇輸入來源","HDMI · 尚未連線");}else if(listening)connect();}}};
    HdmiPreview(Context c){
        super(c);prefs=c.getSharedPreferences("living",0);setSoundEffectsEnabled(false);setId(View.generateViewId());setFocusable(true);setClickable(true);setDefaultFocusHighlightEnabled(false);setDescendantFocusability(FOCUS_BLOCK_DESCENDANTS);setClipToOutline(true);
        GradientDrawable panel=Glass.surface(0x58303A42,0x6FC0D4DE,32);panel.setColors(new int[]{0x5E52616A,0x8F17232B});panel.setOrientation(GradientDrawable.Orientation.TL_BR);setBackground(panel);
        placeholder=new FrameLayout(c);placeholder.setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);add(placeholder,10,10,488,274);
        ImageView source=new ImageView(c);source.setImageResource(R.drawable.ic_input);source.setColorFilter(0xBFE8F1F4);addTo(placeholder,source,212,57,64,64);
        status=text("第四台",29,0xFFF0F5F7);status.setGravity(Gravity.CENTER);addTo(placeholder,status,32,136,424,44);
        hint=text("選擇輸入來源",20,0xFFBBCBD2);hint.setGravity(Gravity.CENTER);hint.setSingleLine(true);hint.setEllipsize(android.text.TextUtils.TruncateAt.END);addTo(placeholder,hint,32,183,424,34);
        caption=new FrameLayout(c);caption.setBackground(Glass.surface(0x80303C46,0x508DA6B4,22));add(caption,10,238,488,74);
        stateIcon=new ImageView(c);stateIcon.setImageResource(R.drawable.ic_input);stateIcon.setColorFilter(0xFFE3EDF2);addTo(caption,stateIcon,16,24,26,26);
        label=text("HDMI · 尚未連線",21,0xFFE7F0F4);label.setSingleLine(true);label.setEllipsize(android.text.TextUtils.TruncateAt.END);addTo(caption,label,58,0,352,74);
        ImageView expand=new ImageView(c);expand.setImageDrawable(new FullscreenGlyph());expand.setPadding(13,13,13,13);expand.setBackground(Glass.surface(0x204B5E69,0x4F9DB3BF,28));expand.setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);addTo(caption,expand,422,9,56,56);
        StateListDrawable border=new StateListDrawable();GradientDrawable edge=Glass.surface(Color.TRANSPARENT,0xFFF1F4F5,32);edge.setStroke(3,0xFFF1F4F5);border.addState(new int[]{android.R.attr.state_focused},edge);border.addState(new int[]{},new ColorDrawable(Color.TRANSPARENT));setForeground(border);
        setContentDescription("第四台 HDMI 小窗；按確認切換全螢幕");setOnClickListener(v->openFullscreen());
    }
    private TextView text(String value,int size,int color){TextView t=new TextView(getContext());t.setText(value);t.setTextSize(TypedValue.COMPLEX_UNIT_PX,size);t.setTextColor(color);t.setTypeface(Typeface.create("sans-serif-medium",Typeface.NORMAL));t.setGravity(Gravity.CENTER_VERTICAL);t.setIncludeFontPadding(false);return t;}
    private void add(View child,int x,int y,int w,int h){addTo(this,child,x,y,w,h);}
    private void addTo(FrameLayout parent,View child,int x,int y,int w,int h){LayoutParams p=new LayoutParams(w,h);p.leftMargin=x;p.topMargin=y;parent.addView(child,p);}
    private void fallback(String message,String state){placeholder.setVisibility(VISIBLE);status.setText("第四台");hint.setText(message);label.setText(state);stateIcon.setImageResource(R.drawable.ic_input);}
    private void waiting(String message){placeholder.setVisibility(VISIBLE);status.setText("第四台");hint.setText(message);label.setText("HDMI · 等待訊號");stateIcon.setImageResource(R.drawable.ic_input);}
    void start(){
        if(listening)return;manager=(TvInputManager)getContext().getSystemService(Context.TV_INPUT_SERVICE);if(manager==null){fallback("選擇輸入來源","HDMI · 尚未連線");return;}
        listening=true;try{manager.registerCallback(changes,handler);}catch(Exception ignored){}connect();
    }
    private void connect(){try{connectAttempt();}catch(Exception e){fail("選擇輸入來源","HDMI · 暫無預覽");}}
    private void connectAttempt(){
        release();input=chooseInput();
        if(input==null){fallback("選擇輸入來源","HDMI · 尚未連線");return;}
        String name=input.loadLabel(getContext()).toString();label.setText(name+" · 等待訊號");
        if(manager.getInputState(input.getId())==TvInputManager.INPUT_STATE_DISCONNECTED){fallback("選擇輸入來源","HDMI · 尚未連線");return;}
        prefs.edit().putString("previewInput",input.getId()).apply();
        video=new TvView(getContext());video.setFocusable(false);video.setDescendantFocusability(FOCUS_BLOCK_DESCENDANTS);add(video,10,10,488,274);video.setStreamVolume(0f);placeholder.bringToFront();caption.bringToFront();
        video.setCallback(new TvView.TvInputCallback(){
            @Override public void onVideoAvailable(String id){if(!running||input==null||!id.equals(input.getId()))return;available=true;handler.removeCallbacks(timeout);placeholder.setVisibility(INVISIBLE);label.setText("第四台 · "+name+" · 靜音");stateIcon.setImageResource(R.drawable.ic_volume_off);if(video!=null)video.setStreamVolume(0f);}
            @Override public void onVideoUnavailable(String id,int reason){if(running){available=false;waiting("等待 HDMI 訊號…");}}
            @Override public void onConnectionFailed(String id){if(running)fail("可直接切換第四台","HDMI · 暫無預覽");}
            @Override public void onDisconnected(String id){if(running)fail("選擇輸入來源","HDMI · 連線中斷");}
            @Override public void onContentBlocked(String id,TvContentRating rating){if(running)fail("開啟第四台確認內容","HDMI · 等待確認");}
        });
        running=true;active=new WeakReference<>(this);waiting("正在連接 HDMI…");
        try{video.tune(input.getId(),TvContract.buildChannelUriForPassthroughInput(input.getId()));video.setStreamVolume(0f);handler.postDelayed(muteCheck,200);handler.postDelayed(timeout,8000);}catch(Exception e){fail("可直接切換第四台","HDMI · 暫無預覽");}
    }
    private TvInputInfo chooseInput(){
        List<TvInputInfo> list=InputController.inputs(getContext());String saved=prefs.getString("previewInput","");
        for(TvInputInfo i:list)if(i.getType()==TvInputInfo.TYPE_HDMI&&i.getId().equals(saved))return i;
        for(TvInputInfo i:list)if(i.getType()==TvInputInfo.TYPE_HDMI&&manager.getInputState(i.getId())==TvInputManager.INPUT_STATE_CONNECTED)return i;
        for(TvInputInfo i:list)if(i.getType()==TvInputInfo.TYPE_HDMI&&manager.getInputState(i.getId())==TvInputManager.INPUT_STATE_CONNECTED_STANDBY)return i;
        return null;
    }
    private void fail(String message,String state){release();fallback(message,state);}
    private void release(){
        running=false;available=false;handler.removeCallbacks(timeout);handler.removeCallbacks(muteCheck);TvView old=video;video=null;
        if(old!=null){old.setCallback(null);try{old.reset();}catch(Exception ignored){}removeView(old);}
        if(active.get()==this)active.clear();placeholder.setVisibility(VISIBLE);
    }
    void stop(){listening=false;if(manager!=null)try{manager.unregisterCallback(changes);}catch(Exception ignored){}release();fallback("選擇輸入來源","HDMI · 待機");}
    void openFullscreen(){TvInputInfo target=input;stop();if(target!=null&&InputController.open(getContext(),target))return;if(!InputController.openConnectedHdmi(getContext())){Intent i=new Intent(getContext(),MainActivity.class).putExtra("action","inputs").putExtra("fromShortcut",true).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK|Intent.FLAG_ACTIVITY_CLEAR_TOP);getContext().startActivity(i);}}
    static void releaseActive(){HdmiPreview preview=active.get();if(preview!=null)preview.stop();}
    static void muteActive(){HdmiPreview preview=active.get();if(preview!=null){preview.handler.removeCallbacks(preview.muteCheck);preview.handler.post(preview.muteCheck);}}
    boolean isRunning(){return running;}
    /** A decorative affordance; the existing single preview focus target performs the action. */
    private static final class FullscreenGlyph extends Drawable {
        private final Paint paint=new Paint(Paint.ANTI_ALIAS_FLAG);
        FullscreenGlyph(){paint.setColor(0xFFD7E0E3);paint.setStyle(Paint.Style.STROKE);paint.setStrokeWidth(1.8f);paint.setStrokeCap(Paint.Cap.ROUND);}
        @Override public void draw(Canvas canvas){Rect b=getBounds();int save=canvas.save();canvas.translate(b.left,b.top);canvas.scale(b.width()/24f,b.height()/24f);canvas.drawLine(3,9,3,3,paint);canvas.drawLine(3,3,9,3,paint);canvas.drawLine(15,3,21,3,paint);canvas.drawLine(21,3,21,9,paint);canvas.drawLine(21,15,21,21,paint);canvas.drawLine(21,21,15,21,paint);canvas.drawLine(9,21,3,21,paint);canvas.drawLine(3,21,3,15,paint);canvas.restoreToCount(save);}
        @Override public void setAlpha(int alpha){paint.setAlpha(alpha);invalidateSelf();}@Override public void setColorFilter(ColorFilter filter){paint.setColorFilter(filter);invalidateSelf();}@Override public int getOpacity(){return PixelFormat.TRANSLUCENT;}
    }
}
