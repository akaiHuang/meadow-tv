package com.akai.livingtv;

import android.Manifest;
import android.app.AlertDialog;
import android.content.*;
import android.content.pm.PackageManager;
import android.graphics.*;
import android.graphics.drawable.*;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.TypedValue;
import android.view.*;
import android.view.inputmethod.*;
import android.widget.*;
import org.json.*;
import java.util.*;

/** TV-first conversation, explicit connection states, original glaze and no Google voice output. */
final class AssistantPanel {
    private final MainActivity activity;
    private final JSONArray history=new JSONArray();
    private final List<TextView> suggestions=new ArrayList<>();
    private AssistantClient client;
    private AlertDialog dialog;
    private TextView reply,status,orbLabel,mic,send,connection,said,cancel,liveBadge,userLabel,replyLabel,welcomeTitle,welcomeHint,privacy;
    private ScrollView replyScroll;
    private ProgressBar loading;
    private final Handler timer=new Handler(Looper.getMainLooper());
    private final Runnable idle=()->disconnect("三分鐘沒有交談，已自動離線。");
    private EditText input;
    private GlazeView glaze;
    private VoicePeer voice;
    private boolean captureVoice;private String diagnosticQuery="";
    private long lastAudibleTouch;
    private String lastUser="",lastAnswer="";
    private String receiptText="";private long seenTaskFinishedAt;
    private boolean busy,listening,awaitingTranscript,textReady,connected,connecting,speaking,voiceUnavailable;
    private long connectionGeneration;
    private String searchDomain="";private TvToolSession task;
    AssistantPanel(MainActivity a){activity=a;}
    void open(){openPanel("");}
    void openForSearch(String domain){openPanel("podcast".equals(domain)?"podcast":"");}
    private void openPanel(String domain){
        if(dialog!=null&&dialog.isShowing()){if(searchDomain.equals(domain))return;hidePanel();}searchDomain=domain;
        if(client==null){client=new AssistantClient(activity);busy=false;textReady=false;connected=false;connecting=false;}listening=false;awaitingTranscript=false;suggestions.clear();
        captureVoice=activity.getSharedPreferences("living",0).getBoolean("voiceDiagnostic",false);diagnosticQuery=captureVoice?"你好，我今天有點累，陪我聊一句。":activity.getSharedPreferences("living",0).getBoolean("assistantDiagnostic",false)?"現在電視音量是多少？請先讀取實際音量。":"";activity.getSharedPreferences("living",0).edit().remove("voiceDiagnostic").remove("assistantDiagnostic").apply();
        FrameLayout stage=new FrameLayout(activity);stage.setPadding(0,0,0,0);
        FrameLayout root=new FrameLayout(activity){
            @Override protected void onMeasure(int w,int h){int width=MeasureSpec.getSize(w),height=MeasureSpec.getSize(h);setMeasuredDimension(width,height);stage.measure(MeasureSpec.makeMeasureSpec(1560,MeasureSpec.EXACTLY),MeasureSpec.makeMeasureSpec(860,MeasureSpec.EXACTLY));}
            @Override protected void onLayout(boolean changed,int l,int t,int r,int b){stage.layout(0,0,1560,860);stage.setPivotX(0);stage.setPivotY(0);stage.setScaleX((r-l)/1560f);stage.setScaleY((b-t)/860f);}
        };root.addView(stage,new FrameLayout.LayoutParams(1560,860));
        TextView wordmark=text("MEADOW",26,0xFFF1F5F2);wordmark.setTag("assistantWordmark");wordmark.setLetterSpacing(.30f);add(stage,wordmark,80,48,330,64);
        connection=text("",21,0xFF9DAFBE);connection.setVisibility(View.GONE);add(stage,connection,55,83,370,40);
        ImageButton close=closeButton();close.setTag("assistantClose");add(stage,close,1416,48,64,64);
        cancel=button("中斷連線",()->disconnect("已停止連線。",true));add(stage,cancel,1110,48,210,64);cancel.setVisibility(View.GONE);liveBadge=text("● 離線",23,0xFFE97B7B);liveBadge.setGravity(Gravity.CENTER);liveBadge.setBackground(Glass.surface(0x0AFFFFFF,0x38F0F6F3,32));add(stage,liveBadge,1140,48,180,64);
        View divider=new View(activity);divider.setBackgroundColor(0x14FFFFFF);add(stage,divider,550,151,1,609);
        ImageView still=new ImageView(activity);still.setImageResource(R.drawable.meadow_glaze);still.setScaleType(ImageView.ScaleType.FIT_CENTER);add(stage,still,117,145,360,360);
        glaze=new GlazeView(activity);add(stage,glaze,117,145,360,360);
        orbLabel=text("準備好，再開始聊",30,0xFFF1F4F2);orbLabel.setGravity(Gravity.CENTER);add(stage,orbLabel,88,531,420,54);
        privacy=text("按紅燈連線，再開始聊",20,0xFFA8B3B2);privacy.setGravity(Gravity.CENTER);add(stage,privacy,88,727,420,34);
        mic=button("● 離線",this::micPressed);mic.setTypeface(Typeface.create("sans-serif-medium",Typeface.NORMAL));add(stage,mic,88,625,420,90);mic.setOnFocusChangeListener((v,on)->paintMic());
        loading=new ProgressBar(activity);loading.setIndeterminateTintList(android.content.res.ColorStateList.valueOf(0xFF80AD94));add(stage,loading,117,655,30,30);loading.setVisibility(View.GONE);
        userLabel=text("你",23,0xFFAAB7B6);add(stage,userLabel,600,160,880,34);
        said=text("",44,0xFF8FCF98);said.setTypeface(Typeface.create("sans-serif",Typeface.BOLD));said.setGravity(Gravity.TOP);said.setMaxLines(2);said.setEllipsize(android.text.TextUtils.TruncateAt.END);said.setLineSpacing(5,1);add(stage,said,600,210,880,144);
        replyLabel=text("Meadow",23,0xFFAAB7B6);add(stage,replyLabel,600,356,880,34);
        reply=text("",40,0xFFF2F5F3);reply.setGravity(Gravity.TOP);reply.setLineSpacing(8,1);
        replyScroll=new ScrollView(activity);replyScroll.setVerticalScrollBarEnabled(false);replyScroll.setId(View.generateViewId());replyScroll.setFocusable(true);replyScroll.addView(reply,new ScrollView.LayoutParams(-1,-2));add(stage,replyScroll,600,402,880,128);
        welcomeTitle=text(searchDomain.equals("podcast")?"找點想聽的":"今晚，想看什麼？",49,0xFFF2F5F3);welcomeTitle.setTypeface(Typeface.create("sans-serif",Typeface.BOLD));add(stage,welcomeTitle,600,226,880,91);
        welcomeHint=text("連線後，按下語音鍵開始聊。\n也可以先打開播放清單。",28,0xFFBAC5C5);welcomeHint.setLineSpacing(8,1);add(stage,welcomeHint,602,342,878,116);
        showConversation(false);
        String[] labels={searchDomain.equals("podcast")?"推薦 Podcast":"推薦一部好片","播放清單"};String[] prompts={searchDomain.equals("podcast")?"幫我搜尋幾個適合現在聽的台灣 Podcast，提供可選的節目。":"推薦一部今晚適合看的節目，簡短說明理由。",""};
        for(int i=0;i<labels.length;i++){final String prompt=prompts[i];TextView chip=button(labels[i],()->{if(prompt.isEmpty())activity.showQueue();else ask(prompt);});chip.setTextSize(TypedValue.COMPLEX_UNIT_PX,27);add(stage,chip,600+i*452,547,428,76);suggestions.add(chip);}
        status=text("",20,0xFFADBABA);status.setSingleLine(true);status.setEllipsize(android.text.TextUtils.TruncateAt.END);add(stage,status,602,750,878,32);
        input=new EditText(activity);input.setId(View.generateViewId());input.setTextSize(TypedValue.COMPLEX_UNIT_PX,26);input.setTextColor(0xFFF0F4F2);input.setHintTextColor(0xFFAAB6B7);input.setHint("也可以打字問我…");input.setSingleLine(true);input.setPadding(28,0,28,0);input.setImeOptions(EditorInfo.IME_ACTION_SEND);input.setBackground(inputSurface());KeyboardGlyph keyboard=new KeyboardGlyph();keyboard.setBounds(0,0,28,28);input.setCompoundDrawablesRelative(keyboard,null,null,null);input.setCompoundDrawablePadding(16);input.setCompoundDrawableTintList(new android.content.res.ColorStateList(new int[][]{new int[]{-android.R.attr.state_enabled},new int[]{}},new int[]{0xFF8C999D,0xFFB9C9C7}));add(stage,input,600,650,726,76);
        send=button("送出",()->ask(input.getText().toString()));add(stage,send,1346,650,134,76);
        TextView voiceNote=text("閒置 3 分鐘後自動離線",18,0xFF96A4A7);add(stage,voiceNote,602,794,878,27);
        mic.setNextFocusRightId(suggestions.get(0).getId());mic.setNextFocusUpId(close.getId());mic.setNextFocusDownId(mic.getId());
        suggestions.get(0).setNextFocusLeftId(mic.getId());suggestions.get(0).setNextFocusRightId(suggestions.get(1).getId());suggestions.get(1).setNextFocusLeftId(suggestions.get(0).getId());
        for(TextView chip:suggestions){chip.setNextFocusUpId(welcomeTitle.getVisibility()==View.VISIBLE?close.getId():replyScroll.getId());chip.setNextFocusDownId(input.getId());}
        replyScroll.setNextFocusLeftId(mic.getId());replyScroll.setNextFocusUpId(close.getId());replyScroll.setNextFocusDownId(suggestions.get(0).getId());
        input.setNextFocusLeftId(mic.getId());input.setNextFocusRightId(send.getId());input.setNextFocusUpId(suggestions.get(0).getId());send.setNextFocusLeftId(input.getId());send.setNextFocusUpId(suggestions.get(1).getId());
        close.setNextFocusDownId(mic.getId());cancel.setNextFocusDownId(suggestions.get(0).getId());
        input.setOnEditorActionListener((v,id,event)->{if(id==EditorInfo.IME_ACTION_SEND){ask(input.getText().toString());return true;}return false;});
        dialog=new AlertDialog.Builder(activity).create();dialog.setView(root,0,0,0,0);
        activity.showFrosted(dialog,()->{stopInput();if(glaze!=null){glaze.close();glaze=null;}dialog=null;activity.assistantConnectionChanged(online());});
        Window window=dialog.getWindow();if(window!=null){window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN|WindowManager.LayoutParams.SOFT_INPUT_ADJUST_NOTHING);}
        restoreTaskReceipt();if(!receiptText.isEmpty())showTaskReceipt();else{if(!lastUser.isEmpty())showUser(lastUser);if(!lastAnswer.isEmpty()){showConversation(true);reply.setText(lastAnswer);}}renderConnection();mic.requestFocus();if(!online()&&!connecting)checkConnection();else if(connected)runDiagnostic();
    }
    private TextView text(String value,int size,int color){TextView t=new TextView(activity);t.setText(value);t.setTextSize(TypedValue.COMPLEX_UNIT_PX,size);t.setTextColor(color);t.setTypeface(Typeface.create("sans-serif",Typeface.NORMAL));t.setGravity(Gravity.CENTER_VERTICAL);t.setIncludeFontPadding(false);return t;}
    private void add(FrameLayout p,View v,int x,int y,int w,int h){FrameLayout.LayoutParams lp=new FrameLayout.LayoutParams(w,h);lp.leftMargin=x;lp.topMargin=y;p.addView(v,lp);}
    private TextView button(String value,Runnable action){
        TextView t=text(value,27,0xFFF0F3F2);t.setId(View.generateViewId());t.setFocusable(true);t.setClickable(true);t.setGravity(Gravity.CENTER);t.setDefaultFocusHighlightEnabled(false);
        StateListDrawable d=new StateListDrawable();d.addState(new int[]{-android.R.attr.state_enabled},Glass.surface(0x06FFFFFF,0x24FFFFFF,1000));d.addState(new int[]{android.R.attr.state_focused},Glass.surface(0xFFF2F6F3,0xDDFFFFFF,1000));d.addState(new int[]{},Glass.surface(0x12FFFFFF,0x50FFFFFF,1000));t.setBackground(d);
        t.setTextColor(new android.content.res.ColorStateList(new int[][]{new int[]{-android.R.attr.state_enabled},new int[]{android.R.attr.state_focused},new int[]{}},new int[]{0xFF8C999C,0xFF173325,0xFFF0F3F2}));
        t.setOnClickListener(v->action.run());return t;
    }
    private ImageButton closeButton(){
        ImageButton button=new ImageButton(activity);button.setId(View.generateViewId());button.setImageDrawable(new CloseGlyph());button.setScaleType(ImageView.ScaleType.FIT_CENTER);button.setPadding(17,17,17,17);button.setFocusable(true);button.setClickable(true);button.setDefaultFocusHighlightEnabled(false);button.setContentDescription("關閉 Meadow");
        StateListDrawable surface=new StateListDrawable();surface.addState(new int[]{android.R.attr.state_focused},Glass.surface(0xFFF2F6F3,0xDDFFFFFF,32));surface.addState(new int[]{},Glass.surface(0x12FFFFFF,0x50FFFFFF,32));button.setBackground(surface);
        button.setColorFilter(0xFFF0F3F2);button.setOnFocusChangeListener((v,on)->button.setColorFilter(on?0xFF182126:0xFFF0F3F2));button.setOnClickListener(v->hidePanel());return button;
    }
    private static final class CloseGlyph extends Drawable {
        private final Paint paint=new Paint(Paint.ANTI_ALIAS_FLAG);
        CloseGlyph(){paint.setColor(0xFFF0F3F2);paint.setStrokeWidth(2);paint.setStrokeCap(Paint.Cap.ROUND);}
        @Override public void draw(Canvas canvas){Rect bounds=getBounds();int save=canvas.save();canvas.translate(bounds.left,bounds.top);canvas.scale(bounds.width()/24f,bounds.height()/24f);canvas.drawLine(6,6,18,18,paint);canvas.drawLine(18,6,6,18,paint);canvas.restoreToCount(save);}
        @Override public void setAlpha(int alpha){paint.setAlpha(alpha);invalidateSelf();}@Override public void setColorFilter(ColorFilter filter){paint.setColorFilter(filter);invalidateSelf();}@Override public int getOpacity(){return PixelFormat.TRANSLUCENT;}
    }
    private StateListDrawable inputSurface(){StateListDrawable states=new StateListDrawable();states.addState(new int[]{-android.R.attr.state_enabled},Glass.surface(0x06FFFFFF,0x26FFFFFF,38));states.addState(new int[]{android.R.attr.state_focused},Glass.surface(0x18FFFFFF,0xE6FFFFFF,38));states.addState(new int[]{},Glass.surface(0x0EFFFFFF,0x46FFFFFF,38));return states;}
    private static final class KeyboardGlyph extends Drawable {
        private final Paint paint=new Paint(Paint.ANTI_ALIAS_FLAG);private final RectF frame=new RectF(2,5,26,23);
        KeyboardGlyph(){paint.setColor(0xFFB9C9C7);paint.setStrokeWidth(1.5f);paint.setStrokeCap(Paint.Cap.ROUND);}
        @Override public void draw(Canvas canvas){Rect bounds=getBounds();int save=canvas.save();canvas.translate(bounds.left,bounds.top);canvas.scale(bounds.width()/28f,bounds.height()/28f);paint.setStyle(Paint.Style.STROKE);canvas.drawRoundRect(frame,3,3,paint);for(int row=0;row<2;row++)for(int column=0;column<5;column++){float x=6+column*4;canvas.drawPoint(x,10+row*4,paint);}canvas.drawLine(9,19,19,19,paint);canvas.restoreToCount(save);}
        @Override public void setAlpha(int alpha){paint.setAlpha(alpha);invalidateSelf();}@Override public void setColorFilter(ColorFilter filter){paint.setColorFilter(filter);invalidateSelf();}@Override public int getOpacity(){return PixelFormat.TRANSLUCENT;}
    }
    private static final class CenteredIconSpan extends android.text.style.ImageSpan {
        CenteredIconSpan(Drawable icon){super(icon);}
        @Override public int getSize(Paint paint,CharSequence text,int start,int end,Paint.FontMetricsInt metrics){
            if(metrics!=null){Paint.FontMetricsInt font=paint.getFontMetricsInt();metrics.ascent=font.ascent;metrics.descent=font.descent;metrics.top=font.top;metrics.bottom=font.bottom;}return getDrawable().getBounds().width();
        }
        @Override public void draw(Canvas canvas,CharSequence text,int start,int end,float x,int top,int baseline,int bottom,Paint paint){
            Drawable icon=getDrawable();Paint.FontMetricsInt font=paint.getFontMetricsInt();float y=baseline+(font.ascent+font.descent)/2f-icon.getBounds().height()/2f;canvas.save();canvas.translate(x,y);icon.draw(canvas);canvas.restore();
        }
    }
    private void setAvailable(boolean on){send.setEnabled(on);input.setEnabled(on);for(int index=0;index<suggestions.size();index++){TextView action=suggestions.get(index);action.setEnabled(index==1||on);action.setAlpha(1f);}}
    private void touch(){timer.removeCallbacks(idle);if(online())timer.postDelayed(idle,180000);}
    void hidePanel(){stopInput();if(dialog!=null)dialog.dismiss();}
    boolean online(){return textReady||connected;}
    private void renderConnection(){
        activity.assistantConnectionChanged(online());if(mic==null)return;cancel.setVisibility(online()||connecting?View.VISIBLE:View.GONE);setAvailable(textReady&&!busy);
        FrameLayout.LayoutParams badgeBounds=(FrameLayout.LayoutParams)liveBadge.getLayoutParams();badgeBounds.leftMargin=online()||connecting?910:1140;liveBadge.setLayoutParams(badgeBounds);
        if(welcomeHint!=null&&welcomeTitle.getVisibility()==View.VISIBLE)welcomeHint.setText(connected?(searchDomain.equals("podcast")?"按下語音鍵，說出節目、主題或主持人。\nMeadow 會搜尋可播放的單集。":"按下語音鍵，說說想看的內容。\n也可以直接打字問我。") : textReady?(connecting?"文字已連線，可以先打字問我。\n正在準備語音，現在還不會收音。":"可以直接打字，或使用下方推薦。\n按左側按鈕可重試語音連線。") : connecting?"正在連接電腦，完成後就能開始聊。":"按左側紅燈連線，就能開始聊。\n也可以先打開播放清單。");
        privacy.setText(connected?"按下才會開啟麥克風":textReady?(connecting?"文字可用，正在準備語音":voiceUnavailable?"語音暫不可用，可先打字":"文字可用，按下可連接語音"):connecting?"連線完成後就能開始":"按紅燈連線，再開始聊");
        if(suggestions.size()>1)mic.setNextFocusRightId(suggestions.get(textReady&&!busy?0:1).getId());
        liveBadge.setText(connected?"● 語音已就緒":textReady?"● 文字已連線":connecting?"● 連線中…":"● 離線");liveBadge.setTextColor(online()?0xFF8FCF98:connecting?0xFFEAC887:0xFFE97B7B);
        paintMic();
    }
    /** Focus paints only this action; it must not toggle availability elsewhere. */
    private void paintMic(){
        if(mic==null||loading==null)return;
        boolean waiting=connecting&&!busy;String waitCaption=textReady?"語音連線中":"連線中";
        loading.setVisibility(waiting?View.VISIBLE:View.GONE);loading.setIndeterminateTintList(android.content.res.ColorStateList.valueOf(mic.hasFocus()?0xFF37674A:0xFFB4D4BD));
        mic.setPadding(waiting?44:0,0,0,0);
        if(waiting){FrameLayout.LayoutParams spinner=(FrameLayout.LayoutParams)loading.getLayoutParams();spinner.leftMargin=88+Math.round((420-mic.getPaint().measureText(waitCaption)-44)/2f);loading.setLayoutParams(spinner);}
        mic.setCompoundDrawables(null,null,null,null);
        if(busy){mic.setTextColor(mic.hasFocus()?0xFF173325:0xFFE5EEE6);mic.setText("取消任務");connection.setText("文字已連線，Meadow 正在處理任務。");}
        else if(waiting){mic.setTextColor(mic.hasFocus()?0xFF173325:0xFFE5EEE6);mic.setText(waitCaption);connection.setText(textReady?"文字已連線，正在準備語音。":"正在連接 Codex…");}
        else if(connected){mic.setTextColor(mic.hasFocus()?0xFF173325:0xFFE5EEE6);String caption=busy?"取消任務":awaitingTranscript?"取消轉錄":listening?"請開始說話":"按此開始講話";android.text.SpannableString micText=new android.text.SpannableString("\uFFFC  "+caption);android.graphics.drawable.Drawable icon=activity.getDrawable(R.drawable.ic_mic);if(icon!=null){icon.setBounds(0,0,31,31);icon.setTint(mic.hasFocus()?0xFF315B43:0xFFA8D0B7);micText.setSpan(new CenteredIconSpan(icon),0,1,android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);}mic.setText(micText);connection.setText("已連線，可以開始說話。");}
        else if(textReady){mic.setText("● 重試語音");mic.setTextColor(mic.hasFocus()?0xFF6E302E:0xFFE79C96);connection.setText("文字已連線；語音暫不可用，按一下可重試。");}
        else {android.text.SpannableString off=new android.text.SpannableString("● 離線");off.setSpan(new android.text.style.ForegroundColorSpan(0xFFE97B7B),0,1,android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);mic.setText(off);mic.setTextColor(mic.hasFocus()?0xFF6E302E:0xFFE79C96);connection.setText("● 離線 · 按紅燈即可重新連線");}
    }
    /** Empty guidance is not conversation content and is never added to history. */
    private void showConversation(boolean visible){
        int state=visible?View.VISIBLE:View.INVISIBLE;
        userLabel.setVisibility(state);said.setVisibility(state);replyLabel.setVisibility(state);replyScroll.setVisibility(state);replyScroll.setFocusable(visible);
        welcomeTitle.setVisibility(visible?View.GONE:View.VISIBLE);welcomeHint.setVisibility(visible?View.GONE:View.VISIBLE);
        if(visible)for(TextView chip:suggestions)chip.setNextFocusUpId(replyScroll.getId());
    }
    private void showUser(String text){
        if(!receiptText.isEmpty())reply.setText("");receiptText="";showConversation(true);userLabel.setText("你");replyLabel.setText("Meadow");said.setTextColor(0xFF8FCF98);said.setTextSize(TypedValue.COMPLEX_UNIT_PX,44);said.setText(text);
        FrameLayout.LayoutParams transcript=(FrameLayout.LayoutParams)said.getLayoutParams();said.measure(View.MeasureSpec.makeMeasureSpec(transcript.width,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(144,View.MeasureSpec.AT_MOST));
        transcript.height=Math.max(68,said.getMeasuredHeight());said.setLayoutParams(transcript);
        FrameLayout.LayoutParams label=(FrameLayout.LayoutParams)replyLabel.getLayoutParams();label.topMargin=transcript.topMargin+transcript.height+(transcript.height>90?20:64);replyLabel.setLayoutParams(label);
        FrameLayout.LayoutParams answer=(FrameLayout.LayoutParams)replyScroll.getLayoutParams();answer.topMargin=label.topMargin+label.height+12;answer.height=Math.max(80,527-answer.topMargin);replyScroll.setLayoutParams(answer);
    }
    /** Detached task results are receipts, never invented user dialogue or new model context. */
    private void restoreTaskReceipt(){
        if(busy)return;try{JSONObject saved=new JSONObject(activity.getSharedPreferences("living",0).getString("latestTaskResult","{}"));long finished=saved.optLong("finishedAt");String text=saved.optString("text").trim();if(finished>seenTaskFinishedAt&&!text.isEmpty()){seenTaskFinishedAt=finished;receiptText=text;}}catch(JSONException ignored){}
    }
    private void markTaskReceiptSeen(){try{JSONObject saved=new JSONObject(activity.getSharedPreferences("living",0).getString("latestTaskResult","{}"));if(!saved.optString("text").trim().isEmpty())seenTaskFinishedAt=Math.max(seenTaskFinishedAt,saved.optLong("finishedAt"));}catch(JSONException ignored){}}
    private void showTaskReceipt(){
        showConversation(true);userLabel.setVisibility(View.INVISIBLE);said.setVisibility(View.INVISIBLE);said.setText("");replyLabel.setText("上次任務");
        FrameLayout.LayoutParams label=(FrameLayout.LayoutParams)replyLabel.getLayoutParams();label.topMargin=183;replyLabel.setLayoutParams(label);
        FrameLayout.LayoutParams answer=(FrameLayout.LayoutParams)replyScroll.getLayoutParams();answer.topMargin=239;answer.height=288;replyScroll.setLayoutParams(answer);reply.setText(receiptText);
    }
    private void disconnect(String message){disconnect(message,false);}
    private void disconnect(String message,boolean cancelTask){
        if(task!=null){if(cancelTask)task.cancel();else task.detach();task=null;}
        timer.removeCallbacks(idle);busy=false;connecting=false;connected=false;speaking=false;stopVoice();
        if(client!=null)client.close();client=new AssistantClient(activity);renderConnection();status.setText(message);state("準備好，再開始聊",false,0);
    }
    private void checkConnection(){
        if(connecting||connected)return;if(!client.configured()){status.setText("尚未與 Mac 配對。");return;}
        connecting=true;voiceUnavailable=false;long generation=++connectionGeneration;renderConnection();state(textReady?"準備語音":"連線中",true,.06f);
        if(textReady){connectVoice(generation);return;}
        client.health(result->{if(!connecting||generation!=connectionGeneration)return;bridgeReady();connectVoice(generation);},error->{if(generation==connectionGeneration)disconnect(error);});
    }
    /** Called only after an authenticated, pinned bridge health response succeeds. */
    private void bridgeReady(){
        textReady=true;renderConnection();status.setText("文字已連線，可以先打字；語音正在準備。");state("可以先打字",false,0);touch();if(!captureVoice)runDiagnostic();
    }
    private void connectVoice(long generation){
        if(!connecting||generation!=connectionGeneration)return;
        voice=new VoicePeer(activity,new VoicePeer.Listener(){
                public void connected(){if(!connecting||generation!=connectionGeneration)return;connecting=false;connected=true;voiceUnavailable=false;renderConnection();if(!busy){status.setText("語音已就緒，按語音鍵才會收音。");AssistantPanel.this.state("等你開口",false,0);}touch();runDiagnostic();}
                public void state(String value){if(generation==connectionGeneration&&dialog!=null&&!busy)status.setText(value);}
                public void level(float level){if(speaking){if(glaze!=null)glaze.state(level>.025f,level);if(level>.025f){orbLabel.setText("Meadow 正在說");long now=android.os.SystemClock.uptimeMillis();if(now-lastAudibleTouch>1000){lastAudibleTouch=now;touch();}}}}
                public void inputLevel(float level){if(listening&&!awaitingTranscript){if(glaze!=null)glaze.level(level);if(level>.04f)touch();}}
                public void inputStopped(){if(listening){awaitingTranscript=true;renderConnection();AssistantPanel.this.state("正在聽懂",true,.05f);}}
                public void inputTranscript(String text,boolean complete){onCodexTranscript(text,complete);}
                public void inputFailed(String message){listening=false;awaitingTranscript=false;renderConnection();status.setText(message);AssistantPanel.this.state("再試一次？",false,0);touch();}
                public void ended(){speaking=false;MediaQueueService.duck(false);AssistantPanel.this.state("繼續聊聊",false,0);status.setText("按「按此開始講話」就能繼續。");touch();}
                public void failed(String message){if(generation==connectionGeneration)voiceFailed(message);}
            },captureVoice);voice.connect();
    }
    /** Voice failure leaves the paired text transport and any handed-off task intact. */
    private void voiceFailed(String message){
        ++connectionGeneration;connected=false;connecting=false;voiceUnavailable=true;listening=false;awaitingTranscript=false;speaking=false;MediaQueueService.duck(false);
        VoicePeer failed=voice;voice=null;if(failed!=null)failed.close();renderConnection();
        if(!busy){status.setText(textReady?"語音暫不可用；文字已連線，可以打字或按左側重試語音。":message);state(textReady?"可以先打字":"再試一次？",false,0);}touch();
    }
    private void runDiagnostic(){if(!diagnosticQuery.isEmpty()){String query=diagnosticQuery;diagnosticQuery="";captureVoice=false;ask(query);}}
    private void micPressed(){
        if(busy){if(task!=null){task.cancel();task=null;}busy=false;setBusy(false);state("已取消",false,0);status.setText("這次任務已取消。");touch();return;}
        if(connecting)return;if(!connected){checkConnection();return;}
        if(awaitingTranscript){stopInput();renderConnection();state("等你開口",false,0);status.setText("已取消這次轉錄。");touch();return;}
        if(listening){awaitingTranscript=true;renderConnection();if(voice!=null)voice.finishListening();state("正在聽懂",true,.05f);return;}startVoice();
    }
    private void state(String label,boolean animates,float energy){orbLabel.setText(label);if(glaze!=null)glaze.state(animates,energy);}
    private void setBusy(boolean on){renderConnection();if(on)mic.setText("取消任務");}
    static String requestForHarness(String text,String domain){return "podcast".equals(domain)?"目前是 Podcast 語音搜尋。請由 Meadow Harness 決定搜尋、推薦、加入或播放等操作，使用真實單集 ID，不要把搜尋當成已播放。使用者原話："+text:text;}
    void ask(String raw){
        String text=raw.trim();if(text.isEmpty()||busy)return;if(!textReady){status.setText("請先連接電腦，文字連線完成後就能交談。");return;}if(text.length()>1800){status.setText("可以說短一點嗎？一次先聊一件事就好。");return;}
        stopInput();if(voice!=null)voice.muteOutput();busy=true;touch();setBusy(true);state("正在想…",true,.10f);status.setText("Meadow 正在處理…");lastUser=text;lastAnswer="";showUser(text);reply.setText("");input.setText("");((InputMethodManager)activity.getSystemService(Context.INPUT_METHOD_SERVICE)).hideSoftInputFromWindow(input.getWindowToken(),0);
        JSONArray bounded=new JSONArray();for(int i=Math.max(0,history.length()-8);i<history.length();i++)try{JSONObject item=history.getJSONObject(i);String content=item.optString("content");if(content.length()>800)content=content.substring(0,800);bounded.put(new JSONObject().put("role",item.getString("role")).put("content",content));}catch(Exception ignored){}
        task=TvToolSession.start(activity,requestForHarness(text,searchDomain),bounded,activity.assistantCatalog(),s->{if(dialog!=null&&dialog.isShowing())status.setText(s);},answer->{
            busy=false;task=null;lastAnswer=answer.text;markTaskReceiptSeen();boolean visible=dialog!=null&&dialog.isShowing();
            if(visible){setBusy(false);reply.setText(answer.text);state(answer.ok?"繼續聊聊":"再試一次？",false,0);status.setText(answer.ok?(connected?"你可以接著問，或直接操作電視。":"文字回覆已完成；語音尚未就緒，可以繼續打字。"):answer.text);}
            if(answer.ok&&MainActivity.foreground&&connected)playVoice(answer.text);else touch();
            if(answer.ok)try{history.put(new JSONObject().put("role","user").put("content",text));history.put(new JSONObject().put("role","assistant").put("content",answer.text));}catch(Exception ignored){}
        });
    }
    private void onCodexTranscript(String text,boolean complete){
        if(!listening||text==null||text.trim().isEmpty())return;touch();showUser(text);
        if(complete){listening=false;awaitingTranscript=false;renderConnection();ask(text);}else status.setText("Codex 正在轉錄；說完可再按一次送出。");
    }
    private void playVoice(String answer){if(voice==null||!connected)return;speaking=true;MediaQueueService.duck(true);touch();voice.speak(answer);}
    private void startVoice(){
        if(voice==null||!connected){status.setText("請先完成 Codex 語音連線。");return;}
        if(activity.checkSelfPermission(Manifest.permission.RECORD_AUDIO)!=PackageManager.PERMISSION_GRANTED){activity.requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO},71);return;}
        stopInput();speaking=false;MediaQueueService.duck(false);touch();listening=true;awaitingTranscript=false;renderConnection();state("我在聽",true,.12f);status.setText("慢慢說，停頓約三秒會送出；再按一次也可以送出。");voice.startListening();
    }
    void permissionResult(int code,int[] results){if(code==71&&results.length>0&&results[0]==PackageManager.PERMISSION_GRANTED&&dialog!=null&&dialog.isShowing())startVoice();else if(code==71){status.setText("麥克風未開啟，仍可使用打字和建議。");state("等你開口",false,0);}}
    private void stopInput(){listening=false;awaitingTranscript=false;if(voice!=null)voice.cancelListening();}
    void stopVoice(){++connectionGeneration;timer.removeCallbacks(idle);MediaQueueService.duck(false);textReady=false;connected=false;connecting=false;speaking=false;voiceUnavailable=false;stopInput();if(voice!=null){voice.close();voice=null;}if(glaze!=null)glaze.state(false,0);activity.assistantConnectionChanged(false);}
    void close(){if(task!=null){task.detach();task=null;}stopVoice();if(glaze!=null){glaze.close();glaze=null;}if(client!=null){client.close();client=null;}if(dialog!=null)dialog.dismiss();}
}
