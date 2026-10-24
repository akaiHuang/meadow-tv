package com.akai.livingtv;

import android.app.AlertDialog;
import android.graphics.*;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.StateListDrawable;
import android.graphics.drawable.GradientDrawable;
import android.os.*;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.*;
import android.view.inputmethod.*;
import android.widget.*;
import java.util.*;

/** Native TV library, ordered queue and one visible transport. No hidden progress timer. */
final class QueueDialog {
    private static final int INK=0xFF142D20, WHITE=0xFFF1F5F2, MUTED=0xFFAFBAC0;
    private final MainActivity activity;
    private final MediaQueue queue;
    private final PodcastRepository podcasts;
    private final FeedRepository images;
    private final String initialQuery;
    private AlertDialog dialog,managementDialog;
    private LinearLayout libraryRows,queueRows;
    private FrameLayout segmented;
    private ScrollView libraryScroll;
    private TextView message,playing,transportTitle,elapsed,total,queueCount,podcastTab,videoTab,libraryHeading,manage,clear,voiceSearch,myShows,searchButton;
    private Transport toggle,stop,next;
    private ImageView transportCover;
    private ProgressBar progress;
    private EditText query;
    private boolean alive,videoMode;
    private int libraryGeneration;
    private String loadedCover="";
    private final Handler ui=new Handler(Looper.getMainLooper());
    private final Runnable refresh=this::renderQueue;
    private final Runnable tick=new Runnable(){public void run(){if(!alive)return;renderProgress();if(queue.state.equals("playing"))ui.postDelayed(this,1000);}};
    QueueDialog(MainActivity a){this(a,"");}
    QueueDialog(MainActivity a,String initial){initialQuery=initial;activity=a;queue=MediaQueue.get(a);podcasts=PodcastRepository.get(a);images=new FeedRepository(a);}
    void open(){
        alive=true;FrameLayout stage=new FrameLayout(activity);
        FrameLayout root=new FrameLayout(activity){
            @Override protected void onMeasure(int w,int h){setMeasuredDimension(MeasureSpec.getSize(w),MeasureSpec.getSize(h));stage.measure(MeasureSpec.makeMeasureSpec(1560,MeasureSpec.EXACTLY),MeasureSpec.makeMeasureSpec(860,MeasureSpec.EXACTLY));}
            @Override protected void onLayout(boolean c,int l,int t,int r,int b){stage.layout(0,0,1560,860);stage.setPivotX(0);stage.setPivotY(0);stage.setScaleX((r-l)/1560f);stage.setScaleY((b-t)/860f);}
        };root.addView(stage,new FrameLayout.LayoutParams(1560,860));
        TextView brand=text("MEADOW",23,0xFFD8E1DB);brand.setLetterSpacing(.28f);put(stage,brand,60,24,330,32);
        TextView heading=text("播放清單",46,WHITE);heading.setTypeface(Typeface.create("sans-serif",Typeface.BOLD));put(stage,heading,60,64,300,70);
        segmented=new FrameLayout(activity);segmented.setClipToOutline(true);put(stage,segmented,356,70,340,64);
        podcastTab=button("Podcast",()->mode(false));videoTab=button("影片",()->mode(true));
        podcastTab.setTextSize(TypedValue.COMPLEX_UNIT_PX,24);videoTab.setTextSize(TypedValue.COMPLEX_UNIT_PX,24);
        podcastTab.setOnFocusChangeListener((v,on)->paintTabs());videoTab.setOnFocusChangeListener((v,on)->paintTabs());
        podcastTab.setNextFocusLeftId(podcastTab.getId());podcastTab.setNextFocusRightId(videoTab.getId());videoTab.setNextFocusLeftId(podcastTab.getId());
        put(segmented,podcastTab,3,3,181,58);put(segmented,videoTab,185,3,152,58);
        ImageButton collapse=new ImageButton(activity);collapse.setId(View.generateViewId());collapse.setImageDrawable(new Glyph("close",WHITE));collapse.setScaleType(ImageView.ScaleType.FIT_CENTER);collapse.setPadding(17,17,17,17);collapse.setFocusable(true);collapse.setClickable(true);collapse.setDefaultFocusHighlightEnabled(false);collapse.setBackground(focus(0x203E4853));collapse.setContentDescription("關閉播放清單");collapse.setTag("queueClose");collapse.setOnClickListener(v->dialog.dismiss());collapse.setOnFocusChangeListener((v,on)->collapse.setColorFilter(on?INK:WHITE));put(stage,collapse,1438,40,62,62);
        videoTab.setNextFocusRightId(collapse.getId());
        podcastTab.setNextFocusUpId(collapse.getId());videoTab.setNextFocusUpId(collapse.getId());collapse.setNextFocusLeftId(videoTab.getId());collapse.setNextFocusRightId(collapse.getId());collapse.setNextFocusUpId(collapse.getId());
        libraryHeading=text("找點想聽的",31,WHITE);libraryHeading.setTypeface(Typeface.create("sans-serif",Typeface.BOLD));put(stage,libraryHeading,60,170,590,48);
        myShows=button("我的節目",()->openPodcastLibrary(""));myShows.setTextSize(TypedValue.COMPLEX_UNIT_PX,22);decorate(myShows,"library",WHITE);myShows.setTag("queuePodcastLibrary");put(stage,myShows,677,170,200,48);
        query=new EditText(activity);query.setId(View.generateViewId());query.setSingleLine(true);query.setTextColor(WHITE);query.setHintTextColor(0xFFB8C2CC);query.setTextSize(TypedValue.COMPLEX_UNIT_PX,24);query.setPadding(24,0,24,0);query.setHint("搜尋節目或主題");query.setImeOptions(EditorInfo.IME_ACTION_SEARCH);query.setBackground(Glass.surface(0x20343C47,0x666F7D8A,32));
        Glyph searchGlyph=new Glyph("search",0xFFBCCDC2);searchGlyph.setBounds(0,0,27,27);query.setCompoundDrawablesRelative(searchGlyph,null,null,null);query.setCompoundDrawablePadding(10);
        voiceSearch=button("語音",this::voiceSearch);voiceSearch.setTextSize(TypedValue.COMPLEX_UNIT_PX,23);decorate(voiceSearch,"mic",WHITE);voiceSearch.setTag("queuePodcastVoice");put(stage,voiceSearch,60,228,124,62);
        put(stage,query,198,228,547,62);searchButton=button("搜尋",this::search);searchButton.setTag("queuePodcastSearch");put(stage,searchButton,759,228,118,62);query.setOnEditorActionListener((v,id,e)->{if(id==EditorInfo.IME_ACTION_SEARCH){search();return true;}return false;});
        podcastTab.setNextFocusDownId(query.getId());videoTab.setNextFocusDownId(query.getId());collapse.setNextFocusDownId(query.getId());
        voiceSearch.setNextFocusLeftId(voiceSearch.getId());voiceSearch.setNextFocusRightId(query.getId());voiceSearch.setNextFocusUpId(podcastTab.getId());
        query.setNextFocusRightId(searchButton.getId());searchButton.setNextFocusLeftId(query.getId());searchButton.setNextFocusRightId(searchButton.getId());
        myShows.setNextFocusLeftId(podcastTab.getId());myShows.setNextFocusRightId(collapse.getId());myShows.setNextFocusUpId(collapse.getId());myShows.setNextFocusDownId(voiceSearch.getId());
        message=text("",18,0xFFABBCC9);message.setSingleLine(true);message.setVisibility(View.INVISIBLE);put(stage,message,64,301,813,28);
        libraryRows=column();libraryScroll=scroll(libraryRows);put(stage,libraryScroll,49,307,850,386);
        View divider=new View(activity);divider.setBackgroundColor(0x335E7666);put(stage,divider,925,170,1,508);
        TextView upNext=text("接下來",34,WHITE);upNext.setTypeface(Typeface.create("sans-serif",Typeface.BOLD));put(stage,upNext,980,170,175,50);queueCount=text("0 項",23,0xFF9DB4A8);put(stage,queueCount,1165,177,150,42);
        queueRows=column();put(stage,scroll(queueRows),963,237,553,283);
        manage=button("管理清單",this::manage);decorate(manage,"list",0xFFE3EFE7);manage.setTag("queueManage");put(stage,manage,980,548,290,66);
        clear=button("清空",this::confirmClear);clear.setTextSize(TypedValue.COMPLEX_UNIT_PX,23);decorate(clear,"trash",0xFFB6C5BC);clear.setTag("queueClear");put(stage,clear,1285,548,215,66);
        View line=new View(activity);line.setBackgroundColor(0x405E7666);line.setTag("queueTransportDivider");put(stage,line,45,700,1470,1);
        transportCover=cover();put(stage,transportCover,60,720,100,100);
        playing=text("尚未播放",22,0xFFB1C4BB);playing.setSingleLine(true);put(stage,playing,184,722,349,34);transportTitle=text("從左邊選一集開始",28,WHITE);transportTitle.setSingleLine(true);put(stage,transportTitle,184,763,349,45);
        elapsed=text("0:00",22,0xFFCEDBD1);put(stage,elapsed,553,760,70,40);progress=new ProgressBar(activity,null,android.R.attr.progressBarStyleHorizontal);progress.setProgressTintList(android.content.res.ColorStateList.valueOf(0xFFB5DCBB));progress.setProgressBackgroundTintList(android.content.res.ColorStateList.valueOf(0x405C7267));put(stage,progress,628,776,305,8);total=text("—",22,0xFFCEDBD1);put(stage,total,951,760,102,40);
        toggle=new Transport("播放","play",true,this::toggle);next=new Transport("下一項","next",false,()->MediaQueueService.command(activity,"NEXT"));stop=new Transport("停止","stop",false,()->MediaQueueService.stopPlayback(activity));
        put(stage,toggle,1102,714,120,112);put(stage,next,1242,714,120,112);put(stage,stop,1382,714,120,112);
        dialog=new AlertDialog.Builder(activity).create();dialog.setView(root,0,0,0,0);
        activity.showFrosted(dialog,()->{alive=false;libraryGeneration++;ui.removeCallbacks(tick);queue.unlisten(refresh);images.close();});
        Window w=dialog.getWindow();if(w!=null){w.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN);}
        queue.listen(refresh);renderQueue();paintTabs();layoutSearch();load(podcasts.items());
        if(!initialQuery.isEmpty()){query.setText(initialQuery);query.post(()->{if(alive)search();});}
        podcastTab.requestFocus();
    }
    private void mode(boolean video){videoMode=video;libraryGeneration++;query.setText("");query.setHint(video?"搜尋近期與精選影片":"搜尋節目或主題");libraryHeading.setText(video?"找點想看的":"找點想聽的");paintTabs();layoutSearch();if(video)loadVideos();else load(podcasts.items());}
    private void layoutSearch(){
        voiceSearch.setVisibility(videoMode?View.GONE:View.VISIBLE);voiceSearch.setFocusable(!videoMode);voiceSearch.setEnabled(!videoMode);
        myShows.setVisibility(videoMode?View.GONE:View.VISIBLE);myShows.setFocusable(!videoMode);myShows.setEnabled(!videoMode);
        FrameLayout.LayoutParams bounds=(FrameLayout.LayoutParams)query.getLayoutParams();bounds.leftMargin=videoMode?60:198;bounds.width=videoMode?685:547;query.setLayoutParams(bounds);
        query.setNextFocusLeftId(videoMode?query.getId():voiceSearch.getId());searchButton.setNextFocusUpId((videoMode?videoTab:myShows).getId());
    }
    private void paintTabs(){
        podcastTab.setSelected(!videoMode);videoTab.setSelected(videoMode);
        if(query!=null)query.setNextFocusUpId((videoMode?videoTab:podcastTab).getId());
        boolean preview=(!videoMode&&videoTab.hasFocus())||(videoMode&&podcastTab.hasFocus());
        GradientDrawable shell=new GradientDrawable();shell.setCornerRadius(32);
        shell.setColor(preview?0x80FFFFFF:0x1AFFFFFF);shell.setStroke(2,0x607F8C97);segmented.setBackground(shell);
        for(TextView tab:new TextView[]{podcastTab,videoTab}){
            boolean current=tab.isSelected(),focused=tab.hasFocus();String name=tab==podcastTab?"Podcast":"影片";
            tab.setText(current?"● "+name:name);tab.setTextColor(current||focused?INK:WHITE);
            tab.setContentDescription(name+(current?"，目前顯示":"，按確認切換"));
            if(current){GradientDrawable surface=new GradientDrawable();surface.setCornerRadius(29);surface.setColor(0xFFF4F7F6);tab.setBackground(surface);}
            else tab.setBackground(null);
        }
    }
    private void search(){if(videoMode)loadVideos();else openPodcastLibrary(query.getText().toString().trim());((InputMethodManager)activity.getSystemService(android.content.Context.INPUT_METHOD_SERVICE)).hideSoftInputFromWindow(query.getWindowToken(),0);}
    private void voiceSearch(){dialog.dismiss();activity.showPodcastVoiceSearch();}
    private void openPodcastLibrary(String term){
        new PodcastLibraryDialog(activity,term,()->{if(alive&&!videoMode){load(podcasts.items());myShows.requestFocus();}}).open();
    }
    private void loadVideos(){List<MediaItem> items=new ArrayList<>();String term=query.getText().toString().toLowerCase(Locale.ROOT).trim();for(MediaItem item:queue.availableVideos())if(item.title.toLowerCase(Locale.ROOT).contains(term))items.add(item);items.sort(Comparator.comparing((MediaItem i)->i.title));load(items);}
    private void load(List<MediaItem> items){
        if(!alive)return;int generation=++libraryGeneration;libraryRows.removeAllViews();notice(items.isEmpty()?(videoMode?"暫時沒有內容，試試搜尋其他名稱。":"搜尋或用 Meadow 加入節目，收藏後就能隨時收聽。") : "");
        List<View> entries=new ArrayList<>(),additions=new ArrayList<>();
        for(MediaItem item:items){
            LinearLayout row=new LinearLayout(activity);row.setGravity(Gravity.CENTER_VERTICAL);row.setBackground(selectedRow());
            ReadingRow primary=new ReadingRow(activity);primary.setId(View.generateViewId());primary.setFocusable(true);primary.setClickable(true);primary.setDefaultFocusHighlightEnabled(false);primary.setContentDescription(shortTitle(item.title)+"，按確認播放");
            ImageView image=cover();image.setTag("rowArt");put(primary,image,16,12,100,100);images.thumbnail(item.image,b->{if(alive&&generation==libraryGeneration&&b!=null)image.setImageBitmap(b);});
            TextView title=text(displayTitle(item.title),30,WHITE);title.setTag("libraryTitle");title.setMaxLines(2);title.setLineSpacing(0,1);title.setTypeface(Typeface.create("sans-serif",Typeface.BOLD));put(primary,title,142,0,590,-2);
            TextView publisher=text(item.show,22,MUTED);publisher.setTag("libraryPublisher");publisher.setSingleLine(true);put(primary,publisher,142,0,590,-2);primary.textPair(title,publisher);
            ImageView play=icon("play",INK);play.setBackground(Glass.surface(0x809CDFB8,0x00FFFFFF,28));play.setPadding(14,14,12,14);play.setVisibility(View.INVISIBLE);play.setTag("rowArt");put(primary,play,44,40,44,44);
            TextView add=button("＋",()->{int at=queue.add(item);notice(at>=0?"已加入："+shortTitle(item.title):"清單已滿，先移除一項再加入。");});add.setContentDescription("加入待播："+shortTitle(item.title));add.setTextSize(TypedValue.COMPLEX_UNIT_PX,35);add.setBackground(focus(0x304A545D));
            Runnable focusRow=()->{boolean on=primary.hasFocus()||add.hasFocus();row.setSelected(on);title.setTextColor(on?INK:WHITE);publisher.setTextColor(on?0xFF3D6050:MUTED);play.setVisibility(primary.hasFocus()?View.VISIBLE:View.INVISIBLE);add.setTextColor(on?0xFF355847:0xFFE3EFE7);};
            primary.setOnFocusChangeListener((v,on)->focusRow.run());
            add.setOnFocusChangeListener((v,on)->focusRow.run());
            primary.setOnClickListener(v->play(item));row.addView(primary,new LinearLayout.LayoutParams(744,-2));
            LinearLayout.LayoutParams plus=new LinearLayout.LayoutParams(62,62);plus.leftMargin=16;plus.rightMargin=6;row.addView(add,plus);primary.setNextFocusRightId(add.getId());add.setNextFocusLeftId(primary.getId());
            LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(-1,-2);lp.bottomMargin=6;libraryRows.addView(row,lp);
            entries.add(primary);additions.add(add);
        }
        View first=entries.isEmpty()?(videoMode?searchButton:myShows):entries.get(0);voiceSearch.setNextFocusDownId(query.getId());query.setNextFocusDownId(first.getId());searchButton.setNextFocusDownId(first.getId());
        for(int i=0;i<entries.size();i++){
            View entry=entries.get(i),add=additions.get(i);entry.setNextFocusUpId(i==0?searchButton.getId():entries.get(i-1).getId());add.setNextFocusUpId(i==0?searchButton.getId():additions.get(i-1).getId());
            entry.setNextFocusDownId(i+1<entries.size()?entries.get(i+1).getId():manage.getId());add.setNextFocusDownId(i+1<additions.size()?additions.get(i+1).getId():manage.getId());
        }
    }
    static String displayTitle(String title){String full=shortTitle(title);int question=full.indexOf("？");return question>=7&&question<30?full.substring(0,question+1):full;}
    static String shortTitle(String title){String value=title.replaceFirst("[｜|].*$","").trim();return value.isEmpty()?title:value;}
    private void play(MediaItem item){int at=queue.add(item);if(at<0){notice("清單已滿，先移除一項再加入。");return;}queue.select(at);MediaQueueService.command(activity,"PLAY");dialog.dismiss();if(item.provider.equals("podcast"))activity.showPlayer();}
    private void toggle(){if(queue.current()==null&&!queue.items().isEmpty())queue.select(0);MediaQueueService.command(activity,"TOGGLE");}
    private void renderQueue(){
        if(!alive)return;View focused=dialog.getCurrentFocus();String restore=focused!=null&&focused.getTag() instanceof String?(String)focused.getTag():"";
        View transportFocus=toggle.hasFocus()?toggle:next.hasFocus()?next:stop.hasFocus()?stop:focused==toggle||focused==next||focused==stop?focused:null;
        queueRows.removeAllViews();MediaItem active=queue.current();List<MediaItem> items=queue.items();queueCount.setText(items.size()+" 項");
        String state=queue.state.equals("playing")?"正在播放":queue.state.equals("preparing")?"正在準備":queue.state.equals("paused")?"已暫停":queue.state.equals("external")?"請在影音 App 控制播放":"已停止";
        playing.setText(queue.error.isEmpty()?active==null?"尚未播放":state+(active.show.isEmpty()?"":" · "+active.show):queue.error);playing.setTextColor(queue.error.isEmpty()?0xFFA8C2B7:0xFFE6B197);transportTitle.setText(active==null?"從左邊選一集開始":displayTitle(active.title));
        if(active!=null&&!loadedCover.equals(active.id)){loadedCover=active.id;transportCover.setImageDrawable(null);images.thumbnail(active.image,b->{if(alive&&queue.current()!=null&&queue.current().id.equals(active.id))transportCover.setImageBitmap(b);});}else if(active==null){loadedCover="";transportCover.setImageDrawable(null);}
        toggle.content(queue.state.equals("playing")?"暫停":"播放",queue.state.equals("playing")?"pause":"play");boolean audio=active!=null?active.provider.equals("podcast"):!items.isEmpty()&&items.get(0).provider.equals("podcast");toggle.setEnabled(audio);next.setEnabled(queue.index+1<items.size());stop.content(active!=null&&!audio?"結束待播":"停止","stop");stop.setEnabled(active!=null);
        wireTransportFocus(transportFocus);
        if(items.isEmpty()){TextView empty=text("清單還是空的。\n從左邊加入想看的、想聽的。",25,0xFFA7BDB1);empty.setLineSpacing(7,1);queueRows.addView(empty,new LinearLayout.LayoutParams(-1,150));}
        for(MediaItem item:items){
            int n=items.indexOf(item);ReadingRow row=new ReadingRow(activity);row.setId(View.generateViewId());row.setTag(item.id);row.setFocusable(true);row.setClickable(true);row.setDefaultFocusHighlightEnabled(false);row.setBackground(focus(0x00394B40));row.setContentDescription((n+1)+"，"+shortTitle(item.title)+"，按確認管理");row.setOnClickListener(v->options(item));
            NowPlayingIndicator indicator=n==queue.index&&item.provider.equals("podcast")?new NowPlayingIndicator(activity):null;TextView number=indicator==null?text(String.valueOf(n+1),22,0xFFA2B3AC):null;View marker=indicator==null?number:indicator;marker.setTag("rowArt");if(indicator!=null){indicator.setPlaybackState(queue.state);}put(row,marker,5,27,28,45);ImageView image=cover();image.setTag("rowArt");put(row,image,49,14,96,96);images.thumbnail(item.image,b->{if(alive&&b!=null)image.setImageBitmap(b);});
            TextView title=text(displayTitle(item.title),29,WHITE);title.setTag("queueTitle");title.setMaxLines(2);title.setLineSpacing(0,1);title.setTypeface(Typeface.create("sans-serif",Typeface.BOLD));put(row,title,170,0,308,-2);TextView publisher=text(item.show.isEmpty()?item.provider:item.show,22,MUTED);publisher.setTag("queuePublisher");publisher.setSingleLine(true);put(row,publisher,170,0,308,-2);row.textPair(title,publisher);
            ImageView more=icon("menu",0xFFA5B8AD);more.setTag("rowArt");put(row,more,497,39,28,28);row.setOnFocusChangeListener((v,on)->{title.setTextColor(on?INK:WHITE);publisher.setTextColor(on?0xFF3D6050:MUTED);if(number!=null)number.setTextColor(on?INK:0xFFA2B3AC);if(indicator!=null)indicator.setTint(on?INK:0xFFA5DDB2);more.setImageDrawable(new Glyph("menu",on?INK:MUTED));});
            LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(-1,-2);lp.bottomMargin=8;queueRows.addView(row,lp);if(item.id.equals(restore))row.requestFocus();
        }
        ui.removeCallbacks(tick);renderProgress();if(queue.state.equals("playing"))ui.postDelayed(tick,1000);
    }
    private void wireTransportFocus(View previous){
        View[] ordered={toggle,next,stop};List<View> enabled=new ArrayList<>();
        for(int i=0;i<ordered.length;i++){
            View control=ordered[i];control.setNextFocusLeftId(control.getId());control.setNextFocusRightId(control.getId());control.setNextFocusDownId(control.getId());control.setNextFocusUpId((i==0?manage:clear).getId());
            if(control.isEnabled())enabled.add(control);
        }
        for(int i=0;i<enabled.size();i++){
            View control=enabled.get(i);control.setNextFocusLeftId(enabled.get(Math.max(0,i-1)).getId());control.setNextFocusRightId(enabled.get(Math.min(enabled.size()-1,i+1)).getId());
        }
        manage.setNextFocusDownId(enabled.isEmpty()?query.getId():enabled.get(0).getId());clear.setNextFocusDownId(enabled.isEmpty()?query.getId():enabled.get(enabled.size()-1).getId());
        if(previous!=null&&!previous.isEnabled()){
            int old=Arrays.asList(ordered).indexOf(previous),distance=Integer.MAX_VALUE;View replacement=query;
            for(View candidate:enabled){int away=Math.abs(Arrays.asList(ordered).indexOf(candidate)-old);if(away<distance){distance=away;replacement=candidate;}}
            if(!replacement.requestFocus())query.requestFocus();
        }
    }
    private void notice(String value){
        message.setText(value);boolean visible=!value.isEmpty();message.setVisibility(visible?View.VISIBLE:View.INVISIBLE);
        if(libraryScroll!=null){FrameLayout.LayoutParams lp=(FrameLayout.LayoutParams)libraryScroll.getLayoutParams();lp.topMargin=visible?337:307;lp.height=visible?356:386;libraryScroll.setLayoutParams(lp);}
    }
    private void renderProgress(){long[] p=MediaQueueService.progress();progress.setMax((int)Math.max(1,p[1]));progress.setProgress((int)p[0]);elapsed.setText(PlayerDialog.time(p[0]));total.setText(p[1]>0?PlayerDialog.time(p[1]):"—");}
    private void manage(){
        List<MediaItem> items=queue.items();if(items.isEmpty()){notice("清單還是空的，先加入一項。");return;}
        GlassMenuDialog menu=new GlassMenuDialog(activity,"管理清單","選一項調整順序、播放或移除；關閉後返回播放清單。");
        for(int i=0;i<items.size();i++){
            MediaItem item=items.get(i);String detail=item.show.isEmpty()?item.provider:item.show;
            menu.addOption("queue-item:"+item.id,(i+1)+"  "+shortTitle(item.title),detail,0,false,()->options(item));
        }
        AlertDialog[] opened=new AlertDialog[1];menu.onDismiss(()->{if(managementDialog==opened[0])managementDialog=null;});
        opened[0]=menu.open();managementDialog=opened[0];
    }
    private void confirmClear(){
        if(queue.items().isEmpty())return;
        GlassMenuDialog menu=new GlassMenuDialog(activity,"清空播放清單？","會停止目前的 Podcast，並移除所有待播項目。");
        menu.addOption("queue-keep","保留","繼續保留目前的播放清單。",R.drawable.ic_back,true,()->{});
        menu.addOption("queue-clear","清空","停止播放，並移除清單中的所有項目。",0,true,()->queue.clear());
        menu.open();
    }
    private void options(MediaItem item){
        String id=item.id;AlertDialog parent=managementDialog!=null&&managementDialog.isShowing()?managementDialog:null;
        GlassMenuDialog menu=new GlassMenuDialog(activity,shortTitle(item.title),item.show.isEmpty()?"調整這一項在播放清單中的安排。":item.show);
        String[] keys={"queue-play","queue-up","queue-down","queue-remove"},labels={"播放這一項","往前移動","往後移動","移除"};
        String[] details={"立即播放這一項。","在待播順序中往前一項。","在待播順序中往後一項。","只移除這一項，保留其他待播內容。"};
        for(int n=0;n<labels.length;n++){
            final int action=n;
            menu.addOption(keys[n],labels[n],details[n],n==0?R.drawable.ic_play:0,true,()->{
                List<MediaItem> now=queue.items();int at=-1;for(int k=0;k<now.size();k++)if(now.get(k).id.equals(id)){at=k;break;}
                if(at<0){notice("這一項已從清單移除。");return;}
                MediaItem target=now.get(at);
                if(action==0){queue.select(at);MediaQueueService.command(activity,"PLAY");if(parent!=null)parent.dismiss();dialog.dismiss();if(target.provider.equals("podcast"))activity.showPlayer();return;}
                if(action==1)queue.move(at,at-1);if(action==2)queue.move(at,at+1);if(action==3)queue.remove(at);
                if(parent!=null&&parent.isShowing()){parent.dismiss();manage();}
            });
        }
        menu.open();
    }
    private LinearLayout column(){LinearLayout l=new LinearLayout(activity);l.setOrientation(LinearLayout.VERTICAL);return l;}
    private ScrollView scroll(LinearLayout content){ScrollView s=new ScrollView(activity);s.setId(View.generateViewId());s.setVerticalScrollBarEnabled(false);s.setClipToPadding(true);s.setPadding(7,3,8,8);s.addView(content,new ScrollView.LayoutParams(-1,-2));return s;}
    private ImageView cover(){ImageView image=new ImageView(activity);image.setScaleType(ImageView.ScaleType.FIT_CENTER);image.setBackground(Glass.surface(0x30343D48,0x00FFFFFF,9));image.setClipToOutline(true);return image;}
    private ImageView icon(String kind,int color){ImageView image=new ImageView(activity);image.setImageDrawable(new Glyph(kind,color));image.setScaleType(ImageView.ScaleType.FIT_CENTER);return image;}
    private TextView text(String value,int size,int color){TextView t=new TextView(activity);t.setText(value);t.setTextSize(TypedValue.COMPLEX_UNIT_PX,size);t.setTextColor(color);t.setIncludeFontPadding(false);t.setTypeface(Typeface.create("sans-serif",Typeface.NORMAL));t.setGravity(Gravity.CENTER_VERTICAL);t.setEllipsize(TextUtils.TruncateAt.END);return t;}
    private StateListDrawable selectedRow(){StateListDrawable d=new StateListDrawable();d.addState(new int[]{android.R.attr.state_selected},Glass.surface(0xFFE7F1E4,0xB3FFFFFF,28));d.addState(new int[]{},Glass.surface(0x002C3B33,0x00FFFFFF,28));return d;}
    private StateListDrawable focus(int fill){StateListDrawable d=new StateListDrawable();d.addState(new int[]{android.R.attr.state_focused},Glass.surface(0xFFE7F1E4,0xB3FFFFFF,34));d.addState(new int[]{android.R.attr.state_selected},Glass.surface(0xFFD1EBD6,0x557DAB8E,34));d.addState(new int[]{},Glass.surface(fill,(fill>>>24)==0?0:0x406F7E8A,34));return d;}
    private TextView button(String value,Runnable click){TextView t=text(value,25,0xFFE3EFE7);t.setId(View.generateViewId());t.setGravity(Gravity.CENTER);t.setFocusable(true);t.setClickable(true);t.setDefaultFocusHighlightEnabled(false);t.setBackground(focus(0x203E4853));t.setOnFocusChangeListener((v,on)->{int tint=on||t.isSelected()?INK:0xFFE3EFE7;t.setTextColor(tint);for(Drawable glyph:t.getCompoundDrawablesRelative())if(glyph!=null)glyph.setTint(tint);if(t.getText() instanceof android.text.Spanned){android.text.Spanned text=(android.text.Spanned)t.getText();for(CenteredGlyphSpan span:text.getSpans(0,text.length(),CenteredGlyphSpan.class))span.getDrawable().setTint(tint);}});t.setOnClickListener(v->click.run());return t;}
    private void decorate(TextView label,String kind,int color){
        String caption=label.getText().toString();Glyph glyph=new Glyph(kind,color);glyph.setBounds(0,0,25,25);
        android.text.SpannableString group=new android.text.SpannableString("\uFFFC"+caption);group.setSpan(new CenteredGlyphSpan(glyph,10),0,1,android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        label.setCompoundDrawablesRelative(null,null,null,null);label.setCompoundDrawablePadding(0);label.setPadding(18,0,18,0);label.setText(group);label.setContentDescription(caption);
    }
    /** The icon and caption share one centered text advance, with a fixed optical gap. */
    private static final class CenteredGlyphSpan extends android.text.style.ImageSpan {
        private final int gap;
        CenteredGlyphSpan(Drawable glyph,int gap){super(glyph);this.gap=gap;}
        @Override public int getSize(Paint paint,CharSequence text,int start,int end,Paint.FontMetricsInt metrics){
            if(metrics!=null){Paint.FontMetricsInt font=paint.getFontMetricsInt();metrics.ascent=font.ascent;metrics.descent=font.descent;metrics.top=font.top;metrics.bottom=font.bottom;}
            return getDrawable().getBounds().width()+gap;
        }
        @Override public void draw(Canvas canvas,CharSequence text,int start,int end,float x,int top,int baseline,int bottom,Paint paint){
            Drawable glyph=getDrawable();Paint.FontMetricsInt font=paint.getFontMetricsInt();float y=baseline+(font.ascent+font.descent)/2f-glyph.getBounds().height()/2f;
            int save=canvas.save();canvas.translate(x,y);glyph.draw(canvas);canvas.restoreToCount(save);
        }
    }
    private void put(FrameLayout p,View v,int x,int y,int width,int height){FrameLayout.LayoutParams lp=new FrameLayout.LayoutParams(width,height);lp.leftMargin=x;lp.topMargin=y;p.addView(v,lp);}

    /** Measure CJK fallback line metrics naturally, then place the publisher below them. */
    private static final class ReadingRow extends FrameLayout {
        private TextView title,publisher;
        ReadingRow(android.content.Context context){super(context);setMinimumHeight(124);}
        void textPair(TextView heading,TextView source){title=heading;publisher=source;}
        @Override protected void onMeasure(int widthSpec,int heightSpec){
            super.onMeasure(widthSpec,MeasureSpec.makeMeasureSpec(0,MeasureSpec.UNSPECIFIED));
            int textHeight=title==null?0:title.getMeasuredHeight()+6+publisher.getMeasuredHeight()+24;
            setMeasuredDimension(getMeasuredWidth(),resolveSize(Math.max(124,textHeight),heightSpec));
        }
        @Override protected void onLayout(boolean changed,int left,int top,int right,int bottom){
            super.onLayout(changed,left,top,right,bottom);
            if(title==null||publisher==null)return;
            int textHeight=title.getMeasuredHeight()+6+publisher.getMeasuredHeight();
            int y=Math.max(2,(getHeight()-textHeight)/2);
            title.layout(title.getLeft(),y,title.getRight(),y+title.getMeasuredHeight());
            int sourceY=y+title.getMeasuredHeight()+6;
            publisher.layout(publisher.getLeft(),sourceY,publisher.getRight(),sourceY+publisher.getMeasuredHeight());
            for(int i=0;i<getChildCount();i++){View child=getChildAt(i);if("rowArt".equals(child.getTag())){int cy=(getHeight()-child.getMeasuredHeight())/2;child.layout(child.getLeft(),cy,child.getRight(),cy+child.getMeasuredHeight());}}
        }
    }

    private final class Transport extends FrameLayout {
        private final TextView caption;private final ImageView glyph;private final boolean primary;private String kind;
        Transport(String title,String icon,boolean emphasized,Runnable action){super(activity);primary=emphasized;setId(View.generateViewId());setFocusable(true);setClickable(true);setDefaultFocusHighlightEnabled(false);
            glyph=new ImageView(activity);glyph.setPadding(22,22,22,22);put(this,glyph,21,0,78,78);caption=text(title,20,0xFFE2EBE5);caption.setGravity(Gravity.CENTER);put(this,caption,0,83,120,28);content(title,icon);setOnClickListener(v->action.run());setOnFocusChangeListener((v,on)->{paint();animate().scaleX(on?1.045f:1f).scaleY(on?1.045f:1f).setDuration(100).start();});
        }
        void content(String title,String icon){caption.setText(title);setContentDescription(title);kind=icon;paint();}
        @Override public void setEnabled(boolean enabled){super.setEnabled(enabled);setFocusable(enabled);setAlpha(enabled?1:.38f);if(glyph!=null)paint();}
        private void paint(){boolean focused=hasFocus();GradientDrawable surface=Glass.surface(focused?0xFFF4F8F6:primary?0xFFB9D5C4:0x303F4954,focused?0xFFFFFFFF:0x557B8B99,100);if(focused)surface.setStroke(4,0xFFFFFFFF);glyph.setBackground(surface);glyph.setImageDrawable(new Glyph(kind,focused||primary?INK:0xFFF0F5F0));caption.setTextColor(focused?0xFFFFFFFF:0xFFCEDBD1);}
    }

    /** Tiny static Canvas glyphs; no font-symbol fallback or rendering loop. */
    private static final class Glyph extends Drawable {
        private final String kind;private final Paint paint=new Paint(Paint.ANTI_ALIAS_FLAG);
        Glyph(String type,int color){kind=type;paint.setColor(color);paint.setStrokeCap(Paint.Cap.ROUND);paint.setStrokeJoin(Paint.Join.ROUND);}
        @Override public void draw(Canvas c){Rect b=getBounds();int save=c.save();c.translate(b.left,b.top);c.scale(b.width()/24f,b.height()/24f);paint.setStrokeWidth(1.8f);paint.setStyle(Paint.Style.FILL);
            switch(kind){
                case "play":case "next":Path p=new Path();p.moveTo(7,4);p.lineTo(kind.equals("next")?17:19,12);p.lineTo(7,20);p.close();c.drawPath(p,paint);if(kind.equals("next"))c.drawRoundRect(18,4,21,20,1,1,paint);break;
                case "pause":c.drawRoundRect(5,4,9,20,1.2f,1.2f,paint);c.drawRoundRect(15,4,19,20,1.2f,1.2f,paint);break;
                case "stop":c.drawRoundRect(5,5,19,19,2,2,paint);break;
                case "search":paint.setStyle(Paint.Style.STROKE);c.drawCircle(10,10,6.5f,paint);c.drawLine(15,15,21,21,paint);break;
                case "mic":paint.setStyle(Paint.Style.STROKE);c.drawRoundRect(9,2,15,15,3,3,paint);c.drawArc(5,7,19,19,0,180,false,paint);c.drawLine(12,19,12,22,paint);c.drawLine(8,22,16,22,paint);break;
                case "library":paint.setStyle(Paint.Style.STROKE);c.drawRoundRect(4,5,20,21,2,2,paint);c.drawLine(7,2,17,2,paint);c.drawLine(9,10,16,10,paint);c.drawLine(9,15,14,15,paint);break;
                case "close":paint.setStyle(Paint.Style.STROKE);c.drawLine(6,6,18,18,paint);c.drawLine(18,6,6,18,paint);break;
                case "list":paint.setStyle(Paint.Style.STROKE);for(int y=6;y<=18;y+=6){c.drawLine(9,y,21,y,paint);c.drawPoint(3,y,paint);}break;
                case "trash":paint.setStyle(Paint.Style.STROKE);c.drawLine(4,6,20,6,paint);c.drawLine(9,3,15,3,paint);c.drawLine(6,6,7,21,paint);c.drawLine(18,6,17,21,paint);c.drawLine(7,21,17,21,paint);c.drawLine(10,10,10,17,paint);c.drawLine(14,10,14,17,paint);break;
                default:paint.setStyle(Paint.Style.STROKE);c.drawLine(5,9,19,9,paint);c.drawLine(5,15,19,15,paint);
            }c.restoreToCount(save);
        }
        @Override public void setTint(int color){paint.setColor(color);paint.setColorFilter(null);invalidateSelf();}
        @Override public void setAlpha(int alpha){paint.setAlpha(alpha);invalidateSelf();}
        @Override public void setColorFilter(ColorFilter colorFilter){paint.setColorFilter(colorFilter);invalidateSelf();}
        @Override public int getOpacity(){return PixelFormat.TRANSLUCENT;}
    }
}
