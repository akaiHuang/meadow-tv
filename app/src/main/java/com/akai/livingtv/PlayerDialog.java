package com.akai.livingtv;

import android.app.AlertDialog;
import android.content.res.ColorStateList;
import android.graphics.*;
import android.graphics.drawable.*;
import android.os.*;
import android.util.TypedValue;
import android.view.*;
import android.widget.*;
import java.util.*;

/** Native now-playing UI. A half-second progress tick exists only while this dialog is visible. */
final class PlayerDialog {
    private static final int WHITE=0xFFF1F5F2,MUTED=0xFFB6C3C3,INK=0xFF173326;
    private final MainActivity activity;private final MediaQueue queue;private final FeedRepository art;
    private AlertDialog dialog;private boolean alive,dragging;private String imageId="",nextImageId="";
    private final Handler ui=new Handler(Looper.getMainLooper());
    private TextView title,subtitle,state,elapsed,duration,nextTitle,library;private ImageButton close;
    private View lastContentFocus,lastTransportFocus;
    private ImageView cover,nextCover;private SeekBar seek;private NowPlayingIndicator indicator;
    private Transport back,toggle,forward,stop,next;
    private final Runnable changed=this::render;
    private final Runnable tick=new Runnable(){public void run(){if(!alive)return;progress();ui.postDelayed(this,500);}};
    PlayerDialog(MainActivity a){activity=a;queue=MediaQueue.get(a);art=new FeedRepository(a);}
    void open(){
        alive=true;FrameLayout stage=new FrameLayout(activity);stage.setTag("playerStage");
        FrameLayout root=new FrameLayout(activity){
            @Override protected void onMeasure(int w,int h){setMeasuredDimension(MeasureSpec.getSize(w),MeasureSpec.getSize(h));stage.measure(MeasureSpec.makeMeasureSpec(1560,MeasureSpec.EXACTLY),MeasureSpec.makeMeasureSpec(860,MeasureSpec.EXACTLY));}
            @Override protected void onLayout(boolean c,int l,int t,int r,int b){stage.layout(0,0,1560,860);stage.setPivotX(0);stage.setPivotY(0);stage.setScaleX((r-l)/1560f);stage.setScaleY((b-t)/860f);}
        };root.addView(stage,new FrameLayout.LayoutParams(1560,860));
        TextView brand=text("MEADOW",24,0xFFE5EEEA);brand.setLetterSpacing(.28f);put(stage,brand,80,48,330,44);
        library=button("播放清單",()->{dialog.dismiss();activity.showQueue();});library.setTag("playerLibrary");put(stage,library,1183,40,215,60);
        close=new ImageButton(activity);close.setId(View.generateViewId());close.setImageDrawable(new TransportGlyph("close",WHITE));close.setScaleType(ImageView.ScaleType.FIT_CENTER);close.setPadding(17,17,17,17);close.setFocusable(true);close.setClickable(true);close.setDefaultFocusHighlightEnabled(false);close.setContentDescription("關閉播放器");close.setTag("playerClose");StateListDrawable closeSurface=new StateListDrawable();closeSurface.addState(new int[]{android.R.attr.state_focused},Glass.surface(0xFFF1F6F3,0xDDFFFFFF,31));closeSurface.addState(new int[]{},Glass.surface(0x12FFFFFF,0x50FFFFFF,31));close.setBackground(closeSurface);close.setOnFocusChangeListener((v,on)->close.setColorFilter(on?INK:WHITE));close.setOnClickListener(v->dialog.dismiss());put(stage,close,1418,39,62,62);

        cover=new ImageView(activity);cover.setScaleType(ImageView.ScaleType.FIT_CENTER);cover.setBackground(Glass.surface(0x18394349,0x3BABBEC5,28));cover.setClipToOutline(true);put(stage,cover,80,174,390,390);
        indicator=new NowPlayingIndicator(activity);indicator.setTint(0xFF8FCF98);put(stage,indicator,544,172,32,34);
        state=text("準備播放…",25,0xFF8FCF98);put(stage,state,592,166,888,46);
        title=text("",46,WHITE);title.setTypeface(Typeface.create("sans-serif",Typeface.BOLD));title.setGravity(Gravity.TOP);title.setMaxLines(2);title.setLineSpacing(6,1);title.setEllipsize(android.text.TextUtils.TruncateAt.END);put(stage,title,544,231,936,152);
        subtitle=text("",26,MUTED);subtitle.setSingleLine(true);subtitle.setEllipsize(android.text.TextUtils.TruncateAt.END);put(stage,subtitle,544,397,936,44);
        seek=new SeekBar(activity);seek.setId(View.generateViewId());seek.setKeyProgressIncrement(15000);seek.setThumbTintList(ColorStateList.valueOf(0xFFE8F3EB));seek.setProgressTintList(ColorStateList.valueOf(0xFFB9D8C5));seek.setProgressBackgroundTintList(ColorStateList.valueOf(0x405F7277));put(stage,seek,534,463,956,48);
        elapsed=text("0:00",22,0xFFD8E3DE);duration=text("—",22,0xFFD8E3DE);duration.setGravity(Gravity.RIGHT|Gravity.CENTER_VERTICAL);put(stage,elapsed,544,516,260,36);put(stage,duration,1220,516,260,36);
        seek.setOnFocusChangeListener((v,on)->{if(on)rememberContent(v);});
        seek.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener(){public void onStartTrackingTouch(SeekBar s){dragging=true;}public void onStopTrackingTouch(SeekBar s){dragging=false;MediaQueueService.seek(s.getProgress());}public void onProgressChanged(SeekBar s,int value,boolean user){if(user){elapsed.setText(time(value));if(!dragging)MediaQueueService.seek(value);}}});

        back=new Transport("後退 15 秒","back15",false,()->MediaQueueService.seek(MediaQueueService.progress()[0]-15000));
        toggle=new Transport("播放","play",true,()->MediaQueueService.command(activity,"TOGGLE"));
        forward=new Transport("前進 30 秒","forward30",false,()->MediaQueueService.seek(MediaQueueService.progress()[0]+30000));
        stop=new Transport("停止","stop",false,()->MediaQueueService.stopPlayback(activity));
        next=new Transport("下一項","next",false,()->MediaQueueService.command(activity,"NEXT"));
        Transport[] actions={back,toggle,forward,stop,next};
        for(int i=0;i<actions.length;i++)put(stage,actions[i],544+i*188,566,168,138);

        View line=new View(activity);line.setBackgroundColor(0x266A8388);put(stage,line,80,732,1400,1);
        put(stage,text("接下來",25,0xFFC4D2CF),80,756,144,48);
        nextCover=new ImageView(activity);nextCover.setScaleType(ImageView.ScaleType.FIT_CENTER);nextCover.setClipToOutline(true);nextCover.setBackground(Glass.surface(0x12313B43,0,12));put(stage,nextCover,248,748,72,72);
        nextTitle=button("從播放清單加入更多節目",()->{dialog.dismiss();activity.showQueue();});nextTitle.setTextSize(TypedValue.COMPLEX_UNIT_PX,24);nextTitle.setGravity(Gravity.LEFT|Gravity.CENTER_VERTICAL);nextTitle.setPadding(24,0,24,0);nextTitle.setMaxLines(2);nextTitle.setEllipsize(android.text.TextUtils.TruncateAt.END);put(stage,nextTitle,340,744,1140,80);
        nextTitle.setOnFocusChangeListener((v,on)->{nextTitle.setTextColor(on?INK:WHITE);if(on)rememberContent(v);});
        updateNavigation(null);

        dialog=new AlertDialog.Builder(activity).create();dialog.setView(root,0,0,0,0);
        activity.showFrosted(dialog,()->{alive=false;ui.removeCallbacks(tick);queue.unlisten(changed);indicator.setPlaybackState("stopped");art.close();});
        // The activity owns the shared 70% content-panel size.
        queue.listen(changed);render();ui.post(tick);(available(toggle)?toggle:library).requestFocus();
    }
    private void render(){
        if(!alive)return;View previousFocus=dialog==null?null:dialog.getCurrentFocus();MediaItem item=queue.current();indicator.setPlaybackState(item==null?"stopped":queue.state);
        if(item==null){dragging=false;title.setText("目前沒有播放內容");subtitle.setText("到播放清單選擇一集開始。");state.setText("尚未播放");state.setTextColor(0xFF8FCF98);imageId="";nextImageId="";cover.setImageDrawable(null);nextCover.setImageDrawable(null);nextCover.setVisibility(View.GONE);nextTitle.setText("從播放清單加入更多節目");FrameLayout.LayoutParams bounds=(FrameLayout.LayoutParams)nextTitle.getLayoutParams();bounds.leftMargin=248;bounds.width=1232;nextTitle.setLayoutParams(bounds);toggle.content("播放","play");for(Transport action:new Transport[]{back,toggle,forward,stop,next})action.setEnabled(false);progress();updateNavigation(previousFocus);return;}
        title.setText(item.title.replaceFirst("[｜|].*$",""));state.setTextColor(queue.state.equals("error")?0xFFE0AD94:0xFF8FCF98);subtitle.setText(item.show);
        state.setText(queue.state.equals("playing")?"正在播放":queue.state.equals("paused")?"已暫停":queue.state.equals("preparing")?"正在準備音源…":queue.error.isEmpty()?"已停止":queue.error);
        toggle.content(queue.state.equals("playing")?"暫停":"播放",queue.state.equals("playing")?"pause":"play");
        boolean audio=item.provider.equals("podcast");for(Transport action:new Transport[]{back,toggle,forward})action.setEnabled(audio);stop.setEnabled(true);
        if(!imageId.equals(item.id)){imageId=item.id;cover.setImageDrawable(null);art.image(item.image,b->{if(alive&&queue.current()!=null&&queue.current().id.equals(item.id))cover.setImageBitmap(b);});}
        List<MediaItem> items=queue.items();MediaItem following=queue.index+1<items.size()?items.get(queue.index+1):null;next.setEnabled(following!=null);
        nextTitle.setText(following==null?"播放清單已到底 · 選擇更多節目":following.title);nextCover.setVisibility(following==null?View.GONE:View.VISIBLE);
        FrameLayout.LayoutParams titleBounds=(FrameLayout.LayoutParams)nextTitle.getLayoutParams();titleBounds.leftMargin=following==null?248:340;titleBounds.width=1480-titleBounds.leftMargin;nextTitle.setLayoutParams(titleBounds);
        String nextId=following==null?"":following.id;if(!nextImageId.equals(nextId)){nextImageId=nextId;nextCover.setImageDrawable(null);if(following!=null)art.image(following.image,b->{if(alive&&nextImageId.equals(following.id))nextCover.setImageBitmap(b);});}
        progress();updateNavigation(previousFocus);
    }
    private void progress(){long[] p=MediaQueueService.progress();renderProgress(p[0],p[1]);}
    private void renderProgress(long position,long length){
        View previousFocus=dialog==null?null:dialog.getCurrentFocus();boolean wasAvailable=available(seek);MediaItem item=queue.current();if(item==null){position=0;length=0;}
        if(!dragging){seek.setMax((int)length);seek.setProgress((int)position);elapsed.setText(time(position));}duration.setText(length>0?time(length):"—");
        boolean enabled=item!=null&&item.provider.equals("podcast")&&length>0&&(queue.state.equals("playing")||queue.state.equals("paused"));seek.setEnabled(enabled);seek.setFocusable(enabled);
        if(wasAvailable!=enabled)updateNavigation(previousFocus);
    }
    private boolean available(View view){return view!=null&&view.isEnabled()&&view.isFocusable()&&view.getVisibility()==View.VISIBLE;}
    private void rememberContent(View view){lastContentFocus=view;if(view instanceof Transport)lastTransportFocus=view;updateNavigation(null);}
    private View preferredTransport(){if(available(lastTransportFocus))return lastTransportFocus;if(available(toggle))return toggle;for(Transport action:new Transport[]{back,forward,stop,next})if(available(action))return action;return null;}
    private View preferredContent(){if(queue.current()==null)return nextTitle;if(available(lastContentFocus))return lastContentFocus;if(available(seek))return seek;View action=preferredTransport();return action==null?nextTitle:action;}
    /** A disabled seek or transport must never become the only route out of the footer. */
    private void updateNavigation(View previousFocus){
        if(library==null||nextTitle==null)return;List<Transport> enabled=new ArrayList<>();for(Transport action:new Transport[]{back,toggle,forward,stop,next})if(available(action))enabled.add(action);
        View upper=available(seek)?seek:library;
        for(int i=0;i<enabled.size();i++){Transport action=enabled.get(i);action.setNextFocusLeftId(enabled.get(Math.max(0,i-1)).getId());action.setNextFocusRightId(enabled.get(Math.min(enabled.size()-1,i+1)).getId());action.setNextFocusUpId(upper.getId());action.setNextFocusDownId(nextTitle.getId());}
        View preferred=preferredTransport(),body=preferredContent();seek.setNextFocusUpId(library.getId());seek.setNextFocusDownId((preferred==null?nextTitle:preferred).getId());seek.setNextFocusLeftId(seek.getId());seek.setNextFocusRightId(seek.getId());
        library.setNextFocusLeftId(library.getId());library.setNextFocusRightId(close.getId());library.setNextFocusUpId(library.getId());library.setNextFocusDownId(body.getId());close.setNextFocusLeftId(library.getId());close.setNextFocusRightId(close.getId());close.setNextFocusUpId(close.getId());close.setNextFocusDownId(body.getId());
        nextTitle.setNextFocusLeftId(nextTitle.getId());nextTitle.setNextFocusRightId(nextTitle.getId());nextTitle.setNextFocusUpId((preferred!=null?preferred:available(seek)?seek:library).getId());nextTitle.setNextFocusDownId(nextTitle.getId());
        if(dialog!=null&&dialog.isShowing()){
            View focused=dialog.getCurrentFocus();
            if(previousFocus!=null&&!available(previousFocus)){View target=queue.current()==null?library:preferredContent();if(available(target))target.requestFocus();}
            else if(!available(focused))library.requestFocus();
        }
    }
    static String time(long ms){long s=Math.max(0,ms/1000);return s>=3600?String.format(Locale.ROOT,"%d:%02d:%02d",s/3600,(s/60)%60,s%60):String.format(Locale.ROOT,"%d:%02d",s/60,s%60);}
    private TextView text(String value,int size,int color){TextView t=new TextView(activity);t.setText(value);t.setTextColor(color);t.setTextSize(TypedValue.COMPLEX_UNIT_PX,size);t.setTypeface(Typeface.create("sans-serif",Typeface.NORMAL));t.setIncludeFontPadding(false);t.setGravity(Gravity.CENTER_VERTICAL);return t;}
    private TextView button(String value,Runnable action){TextView t=text(value,26,WHITE);t.setId(View.generateViewId());t.setGravity(Gravity.CENTER);t.setFocusable(true);t.setClickable(true);t.setDefaultFocusHighlightEnabled(false);StateListDrawable d=new StateListDrawable();d.addState(new int[]{android.R.attr.state_focused},Glass.surface(0xFFF1F6F3,0xDDFFFFFF,1000));d.addState(new int[]{},Glass.surface(0x12FFFFFF,0x50FFFFFF,1000));t.setBackground(d);t.setOnFocusChangeListener((v,on)->t.setTextColor(on?INK:WHITE));t.setOnClickListener(v->action.run());return t;}
    private void put(FrameLayout p,View v,int x,int y,int w,int h){FrameLayout.LayoutParams lp=new FrameLayout.LayoutParams(w,h);lp.leftMargin=x;lp.topMargin=y;p.addView(v,lp);}

    /** Circle and label share one remote focus target; glyphs never depend on a font. */
    private final class Transport extends FrameLayout {
        private final ImageView glyph;private final TextView caption;private final boolean primary;private String kind;
        Transport(String label,String icon,boolean emphasized,Runnable action){super(activity);primary=emphasized;setId(View.generateViewId());setFocusable(true);setClickable(true);setDefaultFocusHighlightEnabled(false);setClipChildren(false);
            glyph=new ImageView(activity);int size=primary?92:78;glyph.setPadding(23,23,23,23);if(!primary)glyph.setPadding(20,20,20,20);put(this,glyph,(168-size)/2,primary?0:7,size,size);
            caption=text(label,22,0xFFD0DDD6);caption.setGravity(Gravity.CENTER);put(this,caption,0,101,168,36);content(label,icon);setOnClickListener(v->action.run());setOnFocusChangeListener((v,on)->{paint();if(on)rememberContent(v);animate().scaleX(on?1.035f:1).scaleY(on?1.035f:1).setDuration(100).start();});
        }
        void content(String label,String icon){caption.setText(label);setContentDescription(label);kind=icon;paint();}
        @Override public void setEnabled(boolean enabled){super.setEnabled(enabled);setFocusable(enabled);setAlpha(enabled?1:.35f);if(glyph!=null)paint();}
        private void paint(){boolean focused=hasFocus();GradientDrawable material=Glass.surface(focused?0xFFF2F7F4:primary?0xFFBDD9C8:0x18FFFFFF,focused?0xFFFFFFFF:0x50FFFFFF,100);if(focused)material.setStroke(3,0xFFFFFFFF);glyph.setBackground(material);glyph.setImageDrawable(new TransportGlyph(kind,focused||primary?INK:WHITE));caption.setTextColor(focused?0xFFFFFFFF:0xFFCEDBD2);}
    }
    private static final class TransportGlyph extends Drawable {
        private final String kind;private final Paint paint=new Paint(Paint.ANTI_ALIAS_FLAG);private final Path triangle=new Path();private final RectF orbit=new RectF(3,3,21,21);
        TransportGlyph(String kind,int color){this.kind=kind;paint.setColor(color);paint.setStrokeCap(Paint.Cap.ROUND);paint.setStrokeJoin(Paint.Join.ROUND);triangle.moveTo(7,4);triangle.lineTo(kind.equals("next")?17:20,12);triangle.lineTo(7,20);triangle.close();}
        @Override public void draw(Canvas canvas){Rect b=getBounds();int saved=canvas.save();canvas.translate(b.left,b.top);canvas.scale(b.width()/24f,b.height()/24f);paint.setStyle(Paint.Style.FILL);
            switch(kind){
                case "play":case "next":canvas.drawPath(triangle,paint);if(kind.equals("next"))canvas.drawRoundRect(18,4,21,20,1,1,paint);break;
                case "pause":canvas.drawRoundRect(5,4,9,20,1,1,paint);canvas.drawRoundRect(15,4,19,20,1,1,paint);break;
                case "stop":canvas.drawRoundRect(5,5,19,19,2,2,paint);break;
                case "close":paint.setStyle(Paint.Style.STROKE);paint.setStrokeWidth(1.8f);canvas.drawLine(6,6,18,18,paint);canvas.drawLine(18,6,6,18,paint);break;
                default:boolean backward=kind.equals("back15");paint.setStyle(Paint.Style.STROKE);paint.setStrokeWidth(1.6f);canvas.drawArc(orbit,backward?-40:-140,280,false,paint);if(backward){canvas.drawLine(3,4,3,9,paint);canvas.drawLine(3,9,8,9,paint);}else{canvas.drawLine(21,4,21,9,paint);canvas.drawLine(21,9,16,9,paint);}paint.setStyle(Paint.Style.FILL);paint.setTextAlign(Paint.Align.CENTER);paint.setTextSize(8);paint.setTypeface(Typeface.create("sans-serif-medium",Typeface.NORMAL));canvas.drawText(backward?"15":"30",12,15.5f,paint);
            }canvas.restoreToCount(saved);
        }
        @Override public void setAlpha(int alpha){paint.setAlpha(alpha);invalidateSelf();}@Override public void setColorFilter(ColorFilter filter){paint.setColorFilter(filter);invalidateSelf();}@Override public int getOpacity(){return PixelFormat.TRANSLUCENT;}
    }
}
