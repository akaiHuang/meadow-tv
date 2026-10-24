package com.akai.livingtv;

import android.accessibilityservice.AccessibilityService;
import android.app.*;
import android.content.*;
import android.content.pm.PackageManager;
import android.graphics.*;
import android.graphics.drawable.*;
import android.media.AudioManager;
import android.media.tv.*;
import android.net.*;
import android.os.*;
import android.provider.Settings;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.*;
import android.view.animation.DecelerateInterpolator;
import android.widget.*;
import java.text.SimpleDateFormat;
import java.util.*;

/** Native Android views: static artwork and event-driven updates, no rendering loop. */
public final class MainActivity extends Activity {
    static boolean foreground;
    private static final int WHITE=Color.rgb(241,246,242), MUTED=Color.rgb(173,190,180), ACCENT=Color.rgb(193,216,201);
    private static final String[] APPS={"com.google.android.youtube.tv","com.netflix.ninja","com.wbd.stream","com.apple.atve.androidtv.appletv"};
    private static final String[] NAMES={"YouTube","Netflix","HBO Max","Apple TV"};
    private final Handler handler=new Handler(Looper.getMainLooper());
    private final List<FeedRepository.Entry> entries=new ArrayList<>();
    private final List<View> focusables=new ArrayList<>();
    private final Set<AlertDialog> shownDialogs=new HashSet<>();
    private final Set<AlertDialog> transparentDialogs=new HashSet<>();
    private FrameLayout[] appTiles=new FrameLayout[4];
    private String[] apps=APPS.clone(),names=NAMES.clone();
    private HorizontalScrollView appScroll;
    private FrameLayout appStrip;
    private final List<FrameLayout> programTiles=new ArrayList<>();
    private final List<ImageView> programImages=new ArrayList<>();
    private final List<ProgramCard> programPool=new ArrayList<>();
    private long programGeneration;
    private String renderedProvider;
    private String renderedApp;
    private org.json.JSONArray ambientArt;
    private static final java.util.regex.Pattern SEASON_SUFFIX=java.util.regex.Pattern.compile(": (?:Limited Series|Season \\d+)$");
    private static final DecelerateInterpolator EASE_OUT=new DecelerateInterpolator();
    private Glass.Request controlGlass;
    private Glass.Request homeGlass;private Glass.Scene homeScene;
    private Glass.Request modalGlass;private Glass.Scene modalScene;private ImageView modalBackdrop;
    private final Map<AlertDialog,Rect> dialogRegions=new IdentityHashMap<>();
    private final Map<AlertDialog,Float> dialogCornerFractions=new IdentityHashMap<>();
    private final Set<ImageView> requestedProgramImages=Collections.newSetFromMap(new IdentityHashMap<>());
    private HorizontalScrollView programScroll;
    private LinearLayout programRow;
    private View controlBadge,assistantBadge;
    private boolean controlsOpen,changingFocus;
    private int artIndex,openDialogs;
    private ImageView incomingBackdrop;
    private android.animation.AnimatorSet backgroundFade;
    private AssistantPanel assistant;
    private UiSounds sounds;
    private HdmiPreview hdmiPreview;private MiniPlayerDock miniPlayer;
    private final Runnable resumePreview=this::tryResumePreview;
    private final Runnable rotateArt=new Runnable(){public void run(){if(started){View f=getCurrentFocus();if(appIndex==0&&!controlsOpen&&openDialogs==0)showAmbient();if(!controlsOpen&&openDialogs==0&&!visiblePrograms.isEmpty()&&!programTiles.contains(f)){artIndex=(artIndex+1)%visiblePrograms.size();selectProgram(artIndex);}handler.postDelayed(this,12000);}}};
    private FrameLayout stage,controls;
    private ImageView backdrop;
    private TextView weatherText;private WeatherGlyph weatherIcon;private View weatherBadge,youtubeBrand;private WeatherRepository weather;private int ambientIndex;
    private WeatherRepository.Reading currentWeather;
    private TextView clock,heroTitle,heroMeta,sectionTitle,sourceLabel,profileLabel,networkLabel,timerLabel;
    private View profile,power,wifi,audio,timer,input,settings,cast,muteControl,closeControl;
    private FeedRepository repository;
    private android.content.SharedPreferences prefs;
    private boolean started,lowMotion,hasStarted;
    private int appIndex;
    private long artworkGeneration;
    private Runnable pendingArt,pendingProvider;
    private String showingArtwork="";
    private final List<FeedRepository.Entry> visiblePrograms=new ArrayList<>();
    private ConnectivityManager connectivity;
    private ConnectivityManager.NetworkCallback networkCallback;
    private final BroadcastReceiver packageReceiver=new BroadcastReceiver(){@Override public void onReceive(Context c,Intent i){refreshApps();}};
    private final BroadcastReceiver minuteReceiver=new BroadcastReceiver(){@Override public void onReceive(Context c,Intent i){updateClock();}};

    @Override public void onCreate(Bundle state){
        super.onCreate(state);
        getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_FULLSCREEN|View.SYSTEM_UI_FLAG_HIDE_NAVIGATION|View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY|View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN|View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION|View.SYSTEM_UI_FLAG_LAYOUT_STABLE);
        prefs=getSharedPreferences("living",MODE_PRIVATE);
        lowMotion=prefs.getBoolean("reduceMotion",Build.VERSION.SDK_INT<=28)||((ActivityManager)getSystemService(ACTIVITY_SERVICE)).isLowRamDevice();
        sounds=new UiSounds(this);buildScreen();UiSounds.disableDefault(getWindow().getDecorView());
    }
    private void buildScreen(){
        stage=new FrameLayout(this);stage.setClipChildren(false);stage.setClipToPadding(false);
        ScaleRoot root=new ScaleRoot(this,stage);root.setBackgroundColor(Color.rgb(9,15,13));setContentView(root);
        backdrop=new ImageView(this);backdrop.setScaleType(ImageView.ScaleType.CENTER_CROP);backdrop.setAlpha(prefs.getBoolean("artwork",true)?1f:0f);place(backdrop,0,0,1920,1080);
        incomingBackdrop=new ImageView(this);incomingBackdrop.setScaleType(ImageView.ScaleType.CENTER_CROP);incomingBackdrop.setAlpha(0);place(incomingBackdrop,0,0,1920,1080);
        clock=text("",68,WHITE,"sans-serif-light");place(clock,80,55,225,92);
        FrameLayout sky=new FrameLayout(this);makeFocusable(sky);StateListDrawable skySurface=new StateListDrawable();skySurface.addState(new int[]{android.R.attr.state_focused},surface(0xFFF0F5EF,0x88FFFFFF,34));skySurface.addState(new int[]{},new ColorDrawable(Color.TRANSPARENT));sky.setBackground(skySurface);weatherIcon=new WeatherGlyph(this);add(sky,weatherIcon,10,12,44,44);weatherText=text("台北 · 天氣載入中",26,0xFFD9E2DC,"sans-serif");weatherText.setSingleLine(true);weatherText.setEllipsize(TextUtils.TruncateAt.END);add(sky,weatherText,68,0,236,68);sky.setOnClickListener(v->showWeather());sky.setOnFocusChangeListener((v,has)->{weatherText.setTextColor(has?0xFF172B24:0xFFD9E2DC);weatherIcon.focusTint(has);});weatherBadge=sky;place(sky,294,67,316,68);
        LinearLayout yt=new LinearLayout(this);yt.setGravity(Gravity.CENTER_VERTICAL);ImageView ytIcon=new ImageView(this);ytIcon.setImageResource(R.drawable.ic_youtube);yt.addView(ytIcon,new LinearLayout.LayoutParams(47,34));TextView ytName=text("YouTube",29,WHITE,"sans-serif-medium");LinearLayout.LayoutParams ytLp=new LinearLayout.LayoutParams(145,42);ytLp.leftMargin=12;yt.addView(ytName,ytLp);youtubeBrand=yt;place(yt,82,470,220,42);
        appScroll=new HorizontalScrollView(this);appScroll.setHorizontalScrollBarEnabled(false);appScroll.setFocusable(false);appScroll.setClipChildren(false);appScroll.setClipToPadding(false);appStrip=new FrameLayout(this);appStrip.setClipChildren(false);appScroll.addView(appStrip,new HorizontalScrollView.LayoutParams(4*288+28,220));place(appScroll,66,194,1784,220);
        loadApps();

        heroMeta=text("",25,ACCENT,"sans-serif");place(heroMeta,82,446,1200,40);
        heroTitle=text("今晚，想看什麼？",104,WHITE,"sans-serif");heroTitle.setTypeface(Typeface.create("sans-serif",Typeface.BOLD));heroTitle.setMaxLines(1);heroTitle.setEllipsize(TextUtils.TruncateAt.END);place(heroTitle,78,486,1190,150);
        sectionTitle=text("",27,0xFFD8E3DC,"sans-serif-medium");place(sectionTitle,82,644,1180,42);
        programScroll=new HorizontalScrollView(this);programScroll.setClipChildren(true);programScroll.setClipToPadding(true);programScroll.setHorizontalScrollBarEnabled(false);programScroll.setSmoothScrollingEnabled(true);programScroll.setFocusable(false);programScroll.setOnScrollChangeListener((v,x,y,oldX,oldY)->loadVisibleProgramImages());
        programRow=new LinearLayout(this);programRow.setOrientation(LinearLayout.HORIZONTAL);programRow.getViewTreeObserver().addOnGlobalLayoutListener(this::loadVisibleProgramImages);programRow.setPadding(14,14,30,14);programRow.setClipChildren(false);programScroll.addView(programRow,new HorizontalScrollView.LayoutParams(-2,-1));place(programScroll,66,700,1230,300);programScroll.setClipBounds(new Rect(0,0,1230,300));
        sourceLabel=text("",18,0xFFA4B3A9,"sans-serif");place(sourceLabel,84,1020,1650,30);
        assistantBadge=control(stage,"Meadow",R.drawable.ic_mic,1375,65,270,68,()->showAssistant());
        assistantConnectionChanged(false);
        controlBadge=control(stage,"控制中心",R.drawable.ic_settings,1670,65,190,68,()->toggleControls(true));
        controls=new FrameLayout(this);controls.setClipChildren(false);controls.setClipToPadding(false);
        place(controls,1375,148,485,570);controls.setVisibility(View.INVISIBLE);
        TextView title=text("控制中心",31,WHITE,"sans-serif-medium");add(controls,title,8,0,280,64);
        profile=control(controls,"家庭",R.drawable.ic_person,301,0,184,64,()->showAccounts());
        profileLabel=(TextView)profile.findViewWithTag("label");profileLabel.setTextSize(TypedValue.COMPLEX_UNIT_PX,25);
        profileLabel.setText(prefs.getString("profile","家庭"));
        power=module("電源",R.drawable.ic_power,0,90,220,220,true,false,()->showPower());
        wifi=module("網路",R.drawable.ic_wifi,242,90,243,102,false,false,()->showNetwork());
        networkLabel=(TextView)wifi.findViewWithTag("label");
        muteControl=module("靜音",R.drawable.ic_volume_off,242,208,243,102,false,false,this::toggleMute);
        audio=module("音訊",R.drawable.ic_audio,0,332,220,102,false,false,()->showAudio());
        timer=module("睡眠計時",R.drawable.ic_sleep,242,332,243,102,false,false,()->showTimer());
        timerLabel=(TextView)timer.findViewWithTag("label");
        input=module("輸入來源",R.drawable.ic_input,0,456,104,104,false,true,()->showInputs());
        settings=module("設定",R.drawable.ic_settings,127,456,104,104,false,true,()->showSettings());
        cast=module("投放到電視",R.drawable.ic_cast,254,456,104,104,false,true,()->showCast());
        closeControl=module("收起控制中心",R.drawable.ic_back,381,456,104,104,false,true,()->toggleControls(false));
        hdmiPreview=new HdmiPreview(this);place(hdmiPreview,1336,702,508,322);miniPlayer=new MiniPlayerDock(this);place(miniPlayer,1336,805,508,200);
        modalBackdrop=new ImageView(this);modalBackdrop.setScaleType(ImageView.ScaleType.FIT_XY);modalBackdrop.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);modalBackdrop.setVisibility(View.INVISIBLE);place(modalBackdrop,0,0,1920,1080);
        wireFocus();updateClock();
        appTiles[0].requestFocus();
    }
    private TextView text(String content,float size,int color,String family){
        TextView t=new TextView(this);t.setText(content);t.setTextColor(color);t.setTextSize(TypedValue.COMPLEX_UNIT_PX,size);t.setFontFeatureSettings("tnum");
        t.setTypeface(Typeface.create(family,Typeface.NORMAL));t.setGravity(Gravity.CENTER_VERTICAL);t.setIncludeFontPadding(false);return t;
    }
    private ImageView icon(int resource,int color){ImageView v=new ImageView(this);v.setImageResource(resource);v.setColorFilter(color);v.setScaleType(ImageView.ScaleType.FIT_CENTER);return v;}
    private void place(View v,int x,int y,int w,int h){add(stage,v,x,y,w,h);}
    private void add(FrameLayout parent,View v,int x,int y,int w,int h){FrameLayout.LayoutParams p=new FrameLayout.LayoutParams(w,h);p.leftMargin=x;p.topMargin=y;parent.addView(v,p);}
    private GradientDrawable surface(int fill,int stroke,float radius){GradientDrawable d=new GradientDrawable();d.setColor(fill);d.setCornerRadius(radius);d.setStroke(2,stroke);return d;}
    private StateListDrawable tileBackground(int fill){
        StateListDrawable list=new StateListDrawable();
        list.addState(new int[]{android.R.attr.state_focused},surface(0xFFDFE9F8,0xFFF4F8FF,25));
        list.addState(new int[]{},surface(fill,0xFF34404E,25));return list;
    }
    private void makeFocusable(View v){
        v.setId(View.generateViewId());v.setSoundEffectsEnabled(false);v.setFocusable(true);v.setClickable(true);v.setDefaultFocusHighlightEnabled(false);focusables.add(v);
    }
    private void focusAnimation(View v,boolean focused,float scale){
        v.animate().cancel();
        if(lowMotion){v.setScaleX(focused?scale:1f);v.setScaleY(focused?scale:1f);}
        else v.animate().scaleX(focused?scale:1f).scaleY(focused?scale:1f).setDuration(100).setInterpolator(EASE_OUT).start();
        // A live elevation shadow is costly on this GPU; Android 9 defaults to a static focus state.
        v.setTranslationZ(0);
    }
    private void loadApps(){
        List<AppCatalog.App> installed=AppCatalog.load(this);apps=new String[installed.size()];names=new String[installed.size()];appTiles=new FrameLayout[installed.size()];
        for(int i=0;i<installed.size();i++){apps[i]=installed.get(i).pkg;names[i]=installed.get(i).name;createApp(i);}
        appStrip.getLayoutParams().width=installed.size()*288+28;appStrip.requestLayout();
    }
    private void refreshApps(){
        List<AppCatalog.App> installed=AppCatalog.load(this);boolean same=installed.size()==apps.length;for(int i=0;same&&i<apps.length;i++)same=apps[i].equals(installed.get(i).pkg)&&names[i].equals(installed.get(i).name);if(same)return;
        String selected=apps[Math.min(appIndex,apps.length-1)];boolean hadFocus=Arrays.asList(appTiles).contains(getCurrentFocus());boolean lock=changingFocus;changingFocus=true;
        for(View tile:appTiles)focusables.remove(tile);appStrip.removeAllViews();loadApps();appIndex=0;for(int i=0;i<apps.length;i++)if(apps[i].equals(selected))appIndex=i;
        wireFocus();if(hadFocus&&!controlsOpen)appTiles[appIndex].requestFocus();if(controlsOpen)for(View tile:appTiles)tile.setFocusable(false);changingFocus=lock;if(repository!=null)updatePrograms(false);
    }
    private void scrollRail(HorizontalScrollView rail,int desired){int max=rail.getChildCount()==0?0:Math.max(0,rail.getChildAt(0).getWidth()-rail.getWidth());int target=Math.max(0,Math.min(max,desired));if(rail.getScrollX()!=target)rail.smoothScrollTo(target,0);}
    private void createApp(final int index){
        FrameLayout tile=new FrameLayout(this);makeFocusable(tile);tile.setClipChildren(false);tile.setClipToPadding(false);tile.setContentDescription(names[index]);FrameLayout face=new FrameLayout(this);face.setTag("appFace");face.setClipToOutline(true);face.setBackground(surface(0xFF131A24,0x334A5461,25));tile.addView(face,new FrameLayout.LayoutParams(-1,-1));
        ImageView banner=new ImageView(this);banner.setTag("appBanner");banner.setScaleType(ImageView.ScaleType.CENTER_CROP);
        try{
            Intent launch=getPackageManager().getLeanbackLaunchIntentForPackage(apps[index]);
            android.content.pm.ResolveInfo resolved=launch==null?null:getPackageManager().resolveActivity(launch,0);
            Drawable image=resolved==null?null:resolved.activityInfo.loadBanner(getPackageManager());
            if(image==null)image=getPackageManager().getApplicationBanner(apps[index]);
            if(image==null){image=getPackageManager().getApplicationIcon(apps[index]);banner.setScaleType(ImageView.ScaleType.FIT_CENTER);banner.setPadding(50,22,50,22);}
            banner.setImageDrawable(image);
        }catch(PackageManager.NameNotFoundException ignored){}
        if(apps[index].equals("meadow.podcasts")){banner.setImageResource(R.drawable.playlist_banner);banner.setScaleType(ImageView.ScaleType.CENTER_CROP);banner.setPadding(0,0,0,0);}
        face.addView(banner,new FrameLayout.LayoutParams(-1,-1));
        if(banner.getDrawable()==null){
            // Public builds contain no provider logo pack. An installed package
            // supplies its own banner/icon above; otherwise use a neutral label.
            banner.setVisibility(View.GONE);TextView brand=text(names[index],32,WHITE,"sans-serif-medium");brand.setTag("appFallbackLabel");brand.setGravity(Gravity.CENTER);brand.setPadding(24,16,24,16);brand.setMaxLines(2);brand.setEllipsize(TextUtils.TruncateAt.END);face.setBackground(surface(0xFF1D2529,0x44586772,25));face.addView(brand,new FrameLayout.LayoutParams(-1,-1));
        }
        StateListDrawable edge=new StateListDrawable();
        edge.addState(new int[]{android.R.attr.state_focused},new AppFocusFrame());
        edge.addState(new int[]{},new ColorDrawable(Color.TRANSPARENT));tile.setForeground(edge);
        TextView label=text(names[index],24,0xFFD9D9D9,"sans-serif-medium");label.setGravity(Gravity.CENTER);
        tile.setOnClickListener(v->launchApp(apps[index]));
        tile.setOnFocusChangeListener((v,has)->{label.setTextColor(has?Color.WHITE:0xFFD9D9D9);focusAnimation(v,has,1.018f);if(has&&!controlsOpen&&!changingFocus){int target=appScroll.getScrollX();int left=v.getLeft()-14,right=v.getRight()+14;if(left<target)target=left;else if(right>target+appScroll.getWidth())target=right-appScroll.getWidth();scrollRail(appScroll,target);if(appIndex!=index){appIndex=index;++artworkGeneration;if(pendingArt!=null){handler.removeCallbacks(pendingArt);pendingArt=null;}if(pendingProvider!=null)handler.removeCallbacks(pendingProvider);pendingProvider=()->{pendingProvider=null;updatePrograms(false);};handler.postDelayed(pendingProvider,80);}}});
        add(appStrip,tile,14+index*288,15,264,149);appTiles[index]=tile;
        add(appStrip,label,14+index*288,176,264,35);
    }
    private StateListDrawable controlMaterial(Drawable idle,float radius){
        StateListDrawable states=new StateListDrawable();states.addState(new int[]{android.R.attr.state_focused},surface(0xFFF1F7F3,0xEEFFFFFF,radius));states.addState(new int[]{},idle);return states;
    }
    private View module(String title,int resource,int x,int y,int w,int h,boolean large,boolean iconOnly,Runnable action){
        FrameLayout tile=new FrameLayout(this);makeFocusable(tile);tile.setContentDescription(title);float radius=large?66:h/2f;
        GradientDrawable idle=new GradientDrawable(GradientDrawable.Orientation.TL_BR,new int[]{0x91344249,0xAA17252E});idle.setCornerRadius(radius);idle.setStroke(2,0x7FBDCED1);tile.setBackground(controlMaterial(idle,radius));
        ImageView image=icon(resource,resource==R.drawable.ic_wifi?0xFF49D8F2:WHITE);int size=large?108:iconOnly?42:43;
        add(tile,image,large?(w-size)/2:iconOnly?(w-size)/2:25,large?45:(h-size)/2,size,size);
        TextView label=text(title,large?27:26,WHITE,"sans-serif-medium");label.setTag("label");label.setSingleLine(true);label.setEllipsize(TextUtils.TruncateAt.END);
        if(large){label.setGravity(Gravity.CENTER);add(tile,label,12,h-64,w-24,45);}else if(iconOnly){label.setVisibility(View.GONE);add(tile,label,0,0,1,1);}else add(tile,label,86,0,w-98,h);
        tile.setOnClickListener(v->action.run());tile.setOnFocusChangeListener((v,on)->{focusAnimation(v,on,1.025f);image.setColorFilter(on?0xFF19323A:resource==R.drawable.ic_wifi?0xFF49D8F2:WHITE);label.setTextColor(on?0xFF19323A:WHITE);});add(controls,tile,x,y,w,h);return tile;
    }
    private void toggleMute(){AudioManager manager=(AudioManager)getSystemService(AUDIO_SERVICE);manager.adjustStreamVolume(AudioManager.STREAM_MUSIC,AudioManager.ADJUST_TOGGLE_MUTE,0);((TextView)muteControl.findViewWithTag("label")).setText(manager.isStreamMute(AudioManager.STREAM_MUSIC)?"已靜音":"靜音");}
    private void paintControlScene(Glass.Scene scene){
        for(View tile:new View[]{profile,power,wifi,muteControl,audio,timer,input,settings,cast,closeControl}){FrameLayout.LayoutParams p=(FrameLayout.LayoutParams)tile.getLayoutParams();Rect region=new Rect(1375+p.leftMargin,148+p.topMargin,1375+p.leftMargin+p.width,148+p.topMargin+p.height);tile.setBackground(controlMaterial(scene.control(region,tile==power?66:p.height/2f),tile==power?66:p.height/2f));}
    }
    /** Rebind a bounded pool instead of allocating an entire view tree on each D-pad step. */
    private final class ProgramCard {
        final FrameLayout tile=new FrameLayout(MainActivity.this);
        final ImageView picture=new ImageView(MainActivity.this);
        FeedRepository.Entry entry;int index;
        ProgramCard(){
            makeFocusable(tile);tile.setBackground(surface(0x60212E27,0x66526A5C,21));tile.setClipToOutline(true);
            picture.setScaleType(ImageView.ScaleType.CENTER_CROP);tile.addView(picture,new FrameLayout.LayoutParams(-1,-1));
            StateListDrawable edge=new StateListDrawable();GradientDrawable line=surface(Color.TRANSPARENT,WHITE,21);line.setStroke(5,WHITE);
            edge.addState(new int[]{android.R.attr.state_focused},line);edge.addState(new int[]{android.R.attr.state_selected},line);edge.addState(new int[]{},new ColorDrawable(Color.TRANSPARENT));tile.setForeground(edge);
            tile.setOnClickListener(v->{if(entry!=null)showProgram(entry);});
            tile.setOnFocusChangeListener((v,has)->{focusAnimation(v,has,1.02f);if(has&&!controlsOpen&&!changingFocus){selectProgram(index);scrollRail(programScroll,Math.max(0,v.getLeft()-500));}});
            LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(178,267);lp.gravity=Gravity.CENTER_VERTICAL;lp.rightMargin=28;tile.setLayoutParams(lp);
        }
        void bind(int at,FeedRepository.Entry next){
            if(tile.getParent()==null)programRow.addView(tile);
            tile.animate().cancel();tile.setScaleX(1);tile.setScaleY(1);tile.setSelected(false);
            if(entry==null||!cardArtwork(entry).equals(cardArtwork(next))||picture.getDrawable()==null){picture.setImageDrawable(null);requestedProgramImages.remove(picture);}
            entry=next;index=at;tile.setContentDescription(next.title);tile.setFocusable(!controlsOpen);tile.setVisibility(View.VISIBLE);
            int width=next.provider.equals("spotify")?267:380,height=next.provider.equals("spotify")?267:214;
            LinearLayout.LayoutParams lp=(LinearLayout.LayoutParams)tile.getLayoutParams();if(lp.width!=width||lp.height!=height){lp.width=width;lp.height=height;tile.setLayoutParams(lp);}
        }
        void hide(){tile.animate().cancel();tile.setVisibility(View.GONE);tile.setFocusable(false);picture.setImageDrawable(null);requestedProgramImages.remove(picture);programRow.removeView(tile);entry=null;}
    }
    private static String cardArtwork(FeedRepository.Entry entry){return entry.provider.equals("spotify")||entry.wide||entry.backdrop.isEmpty()?entry.poster:entry.backdrop;}
    private void loadVisibleProgramImages(){
        if(!started||repository==null||programScroll.getWidth()==0||pendingArt!=null)return;
        int left=programScroll.getScrollX()-380,right=programScroll.getScrollX()+programScroll.getWidth()+380;
        for(int i=0;i<programTiles.size();i++){
            View tile=programTiles.get(i);ImageView image=programImages.get(i);
            if(image.getDrawable()!=null||tile.getWidth()==0||tile.getRight()<left||tile.getLeft()>right||!requestedProgramImages.add(image))continue;
            long generation=programGeneration;
            repository.thumbnail(cardArtwork(visiblePrograms.get(i)),b->{if(started&&generation==programGeneration&&requestedProgramImages.contains(image)&&image.isAttachedToWindow()){image.setImageBitmap(b);if(b==null)requestedProgramImages.remove(image);}});
        }
    }
    private void selectProgram(int index){
        if(index<0||index>=visiblePrograms.size())return;artIndex=index;
        for(int i=0;i<programTiles.size();i++)programTiles.get(i).setSelected(i==index);
        selectArt(visiblePrograms.get(index));
        if(!programTiles.contains(getCurrentFocus()))scrollRail(programScroll,Math.max(0,programTiles.get(index).getLeft()-400));
    }
    private StateListDrawable capsuleBackground(int fill){
        StateListDrawable list=new StateListDrawable();
        list.addState(new int[]{android.R.attr.state_focused},surface(0xFFF0F5EF,0x99FFFFFF,1000));
        list.addState(new int[]{},Glass.surface(fill,0x62CFDFE5,1000));return list;
    }
    private View control(FrameLayout parent,String title,int resource,int x,int y,int w,int h,Runnable action){
        FrameLayout v=new FrameLayout(this);makeFocusable(v);v.setBackground(capsuleBackground(parent==stage?0x80313D36:0x30495E51));v.setContentDescription(title);
        boolean row=w>300;int circle=row?54:42,left=row?14:10;
        View well=new View(this);well.setBackground(surface(0x246F8C78,Color.TRANSPARENT,circle/2f));add(v,well,left,(h-circle)/2,circle,circle);
        ImageView image=icon(resource,WHITE);int iconSize=row?29:26;add(v,image,left+(circle-iconSize)/2,(h-iconSize)/2,iconSize,iconSize);
        TextView label=text(title,row?28:25,WHITE,"sans-serif-medium");label.setTag("label");int labelX=left+circle+(row?20:10);add(v,label,labelX,0,w-labelX-(row?56:10),h);
        TextView chevron=text("›",30,0xFFB3C3B9,"sans-serif-light");if(row)add(v,chevron,w-42,0,25,h);
        v.setOnClickListener(view->action.run());
        v.setOnFocusChangeListener((view,has)->{focusAnimation(view,has,1.012f);int color=has?0xFF1C3025:WHITE;image.setColorFilter(color);label.setTextColor(color);chevron.setTextColor(has?0xFF456050:0xFFB3C3B9);well.setBackground(surface(has?0x184A6754:0x246F8C78,Color.TRANSPARENT,circle/2f));});
        add(parent,v,x,y,w,h);return v;
    }
    private void wireFocus(){
        for(int i=0;i<appTiles.length;i++){
            View tile=appTiles[i];tile.setNextFocusLeftId(appTiles[Math.max(0,i-1)].getId());tile.setNextFocusRightId(i==appTiles.length-1?hdmiPreview.getId():appTiles[i+1].getId());tile.setNextFocusUpId(i<2?weatherBadge.getId():assistantBadge.getId());tile.setNextFocusDownId(programTiles.isEmpty()?hdmiPreview.getId():programTiles.get(Math.min(i,programTiles.size()-1)).getId());
        }
        link(weatherBadge,weatherBadge,assistantBadge,weatherBadge,appTiles[0]);link(assistantBadge,weatherBadge,controlBadge,assistantBadge,appTiles[3]);link(controlBadge,assistantBadge,controlBadge,controlBadge,appTiles[3]);
        for(int i=0;i<programTiles.size();i++){View p=programTiles.get(i);link(p,programTiles.get(Math.max(0,i-1)),i==programTiles.size()-1?hdmiPreview:programTiles.get(i+1),appTiles[appIndex],hdmiPreview);}
        link(hdmiPreview,programTiles.isEmpty()?appTiles[3]:programTiles.get(programTiles.size()-1),hdmiPreview,controlBadge,miniPlayer!=null&&miniPlayer.showing()?miniPlayer.open:appTiles[Math.min(4,appTiles.length-1)]);if(miniPlayer!=null)miniPlayer.open.setNextFocusUpId(hdmiPreview.getId());
        link(profile,power,profile,profile,wifi);link(power,power,wifi,profile,audio);link(wifi,power,wifi,profile,muteControl);link(muteControl,power,muteControl,wifi,timer);
        link(audio,audio,timer,power,input);link(timer,audio,timer,muteControl,cast);
        link(input,input,settings,audio,input);link(settings,input,cast,audio,settings);link(cast,settings,closeControl,timer,cast);link(closeControl,cast,closeControl,timer,closeControl);
    }
    private void toggleControls(boolean open){
        if(controlsOpen==open)return;controlsOpen=open;changingFocus=true;controls.animate().cancel();if(controlGlass!=null){controlGlass.cancel();controlGlass=null;}
        // Keep whole app cards to the left of the expanded controls instead of
        // leaving part of the fifth banner visible behind the power module.
        appScroll.setClipBounds(open?new Rect(0,0,1166,220):null);
        if(open){
            if(miniPlayer!=null)miniPlayer.underControls(true);handler.removeCallbacks(resumePreview);hdmiPreview.stop();hdmiPreview.setFocusable(false);hdmiPreview.setVisibility(View.INVISIBLE);
            controls.setVisibility(View.INVISIBLE);
            controlGlass=Glass.requestScene(stage,getResources(),glass->{if(started&&controlsOpen)paintControlScene(glass);});
            controls.setVisibility(View.VISIBLE);controls.setAlpha(0);controls.setTranslationX(64);
            for(View v:appTiles)v.setFocusable(false);for(View v:programTiles)v.setFocusable(false);controlBadge.setFocusable(false);assistantBadge.setFocusable(false);weatherBadge.setFocusable(false);
            controls.animate().alpha(1).translationX(0).setDuration(220).setInterpolator(EASE_OUT).withLayer().withEndAction(()->{if(controlsOpen)profile.requestFocus();changingFocus=false;}).start();
        }else{
            if(miniPlayer!=null)miniPlayer.underControls(false);for(View v:appTiles)v.setFocusable(true);for(View v:programTiles)v.setFocusable(true);controlBadge.setFocusable(true);assistantBadge.setFocusable(true);weatherBadge.setFocusable(true);controlBadge.requestFocus();hdmiPreview.setFocusable(true);changingFocus=false;
            controls.animate().alpha(0).translationX(64).setDuration(180).withLayer().withEndAction(()->{if(!controlsOpen){controls.setVisibility(View.INVISIBLE);hdmiPreview.setVisibility(View.VISIBLE);refreshHomeGlass();syncPreview();}}).start();
        }
    }
    private void link(View v,View left,View right,View up,View down){v.setNextFocusLeftId(left.getId());v.setNextFocusRightId(right.getId());v.setNextFocusUpId(up.getId());v.setNextFocusDownId(down.getId());}
    private String selectedProvider(){if(apps[appIndex].equals("com.spotify.tv.android")||apps[appIndex].equals("meadow.podcasts"))return "spotify";return appIndex==1?"netflix":appIndex==2?"hbo":appIndex==3?"apple":"";}
    private void updatePrograms(boolean initial){
        boolean previousFocusLock=changingFocus;changingFocus=true;int oldCover=programTiles.indexOf(getCurrentFocus());int oldId=oldCover>=0&&oldCover<visiblePrograms.size()?visiblePrograms.get(oldCover).id:-1;
        String provider=selectedProvider();List<FeedRepository.Entry> next=new ArrayList<>();
        if(!provider.isEmpty())for(FeedRepository.Entry e:entries)if(e.provider.equals(provider))next.add(e);
        if(apps[appIndex].equals(renderedApp)&&provider.equals(renderedProvider)&&samePrograms(next,visiblePrograms)){
            changingFocus=previousFocusLock;loadVisibleProgramImages();
            if(!visiblePrograms.isEmpty())selectArt(visiblePrograms.get(Math.min(artIndex,visiblePrograms.size()-1)));else if(appIndex==0&&showingArtwork.isEmpty())showAmbient();
            return;
        }
        renderedApp=apps[appIndex];renderedProvider=provider;programGeneration++;visiblePrograms.clear();visiblePrograms.addAll(next);youtubeBrand.setVisibility(appIndex==0?View.VISIBLE:View.GONE);
        programTiles.clear();programImages.clear();requestedProgramImages.clear();artIndex=0;
        for(int i=0;i<visiblePrograms.size();i++){
            if(i==programPool.size())programPool.add(new ProgramCard());ProgramCard card=programPool.get(i);card.bind(i,visiblePrograms.get(i));programTiles.add(card.tile);programImages.add(card.picture);
            if(card.picture.getDrawable()!=null)requestedProgramImages.add(card.picture);
        }
        for(int i=visiblePrograms.size();i<programPool.size();i++)programPool.get(i).hide();
        if(oldCover<0)programScroll.scrollTo(0,0);sectionTitle.setText(visiblePrograms.isEmpty()?"":provider.equals("spotify")?"台灣 Podcast":names[appIndex]);wireFocus();
        if(!visiblePrograms.isEmpty())selectProgram(0);
        else {++artworkGeneration;if(backgroundFade!=null)backgroundFade.cancel();incomingBackdrop.setAlpha(0);incomingBackdrop.setImageDrawable(null);if(pendingArt!=null)handler.removeCallbacks(pendingArt);heroTitle.setText("今晚，想看什麼？");heroMeta.setText("");if(appIndex==0)showAmbient();else {backdrop.animate().alpha(0).setDuration(280).start();showingArtwork="";}}
        sourceLabel.setText(visiblePrograms.isEmpty()?(appIndex==0?"Google Ambient · 精選攝影":""):provider.equals("spotify")?"Podcast 封面與資料：發布者／Apple Podcasts · 點選瀏覽與播放單集":"資料：平台台灣公開目錄 · 實際上架與播放以 App 為準");
        if(oldCover>=0&&!controlsOpen&&!visiblePrograms.isEmpty()){
            int restore=Math.min(oldCover,visiblePrograms.size()-1);for(int i=0;i<visiblePrograms.size();i++)if(visiblePrograms.get(i).id==oldId){restore=i;break;}
            programTiles.get(restore).requestFocus();selectProgram(restore);
        }
        programRow.post(this::loadVisibleProgramImages);
        changingFocus=previousFocusLock;
    }
    private static boolean samePrograms(List<FeedRepository.Entry> a,List<FeedRepository.Entry> b){
        if(a.size()!=b.size())return false;
        for(int i=0;i<a.size();i++){FeedRepository.Entry x=a.get(i),y=b.get(i);if(x.id!=y.id||x.wide!=y.wide||x.verifiedAt!=y.verifiedAt||!Objects.equals(x.region,y.region)||!Objects.equals(x.locale,y.locale)||!Objects.equals(x.availability,y.availability)||!Objects.equals(x.collection,y.collection)||!Objects.equals(x.availabilityStarts,y.availabilityStarts)||!Objects.equals(x.availabilityEnds,y.availabilityEnds)||!Objects.equals(x.title,y.title)||!Objects.equals(x.provider,y.provider)||!Objects.equals(x.poster,y.poster)||!Objects.equals(x.backdrop,y.backdrop)||!Objects.equals(x.official,y.official)||!Objects.equals(x.summary,y.summary)||!Objects.equals(x.date,y.date)||!Objects.equals(x.source,y.source))return false;}
        return true;
    }
    private String providerName(String provider){if(provider.equals("spotify"))return "Spotify";return provider.equals("netflix")?"Netflix":provider.equals("hbo")?"HBO Max":"Apple TV";}
    private void selectArt(FeedRepository.Entry entry){
        heroTitle.setText(SEASON_SUFFIX.matcher(entry.title).replaceFirst(""));heroMeta.setText(entry.provider.equals("spotify")?"台灣 Podcast 推薦":"近期與精選節目");
        if(pendingArt!=null){handler.removeCallbacks(pendingArt);pendingArt=null;}
        if(entry.provider.equals("spotify")){if(!showingArtwork.startsWith("ambient:"))showAmbient();return;}
        final long generation=++artworkGeneration;
        pendingArt=()->{
            pendingArt=null;
            try{
                if(!started||!prefs.getBoolean("artwork",true)||repository==null)return;
                String url=entry.backdrop.isEmpty()?entry.poster:entry.backdrop;
                if(url.equals(showingArtwork))return;
                repository.background(url,b->{if(started&&generation==artworkGeneration&&b!=null){crossfade(b,url);}});
            }finally{loadVisibleProgramImages();}
        };
        handler.postDelayed(pendingArt,90);
    }
    private void crossfade(Bitmap bitmap,String url){
        if(backgroundFade!=null)backgroundFade.cancel();incomingBackdrop.animate().cancel();backdrop.animate().cancel();
        // Keep the old image opaque: one alpha blend, without a dip to black or two alpha layers.
        incomingBackdrop.setImageBitmap(bitmap);incomingBackdrop.setAlpha(0);
        backgroundFade=new android.animation.AnimatorSet();
        backgroundFade.play(android.animation.ObjectAnimator.ofFloat(incomingBackdrop,"alpha",0,1f));
        backgroundFade.setDuration(300);backgroundFade.addListener(new android.animation.AnimatorListenerAdapter(){
            private boolean cancelled;
            @Override public void onAnimationCancel(android.animation.Animator animation){cancelled=true;}
            @Override public void onAnimationEnd(android.animation.Animator animation){
                if(!cancelled){backdrop.setImageBitmap(bitmap);backdrop.setAlpha(prefs.getBoolean("artwork",true)?1f:0);showingArtwork=url;refreshHomeGlass();}
                incomingBackdrop.setAlpha(0);incomingBackdrop.setImageDrawable(null);
            }
        });backgroundFade.start();
    }
    private void paintWeather(WeatherRepository.Reading r){currentWeather=r;String temperature=r.code<0?r.text:r.text.split("°",2)[0]+"°";weatherText.setText(r.city+"  "+temperature);int width=Math.max(178,Math.min(360,Math.round(weatherText.getPaint().measureText(weatherText.getText().toString()))+88));weatherBadge.getLayoutParams().width=width;weatherText.getLayoutParams().width=width-88;weatherBadge.requestLayout();weatherIcon.update(r.code,r.day);weatherBadge.setContentDescription(r.city+" "+r.text+"，按一下更換城市。資料 Open-Meteo");paintHomeGlass();}
    /** One background-only capture after an artwork transition; never samples a TV surface. */
    private void refreshHomeGlass(){
        if(homeGlass!=null){homeGlass.cancel();homeGlass=null;}
        if(!started||controlsOpen||openDialogs>0||backdrop.getWidth()==0)return;
        homeGlass=Glass.requestScene(backdrop,getResources(),scene->{homeGlass=null;if(!started)return;homeScene=scene;paintHomeGlass();});
    }
    private void paintHomeGlass(){
        if(homeScene==null)return;
        for(View target:new View[]{weatherBadge,assistantBadge,controlBadge}){
            FrameLayout.LayoutParams p=(FrameLayout.LayoutParams)target.getLayoutParams();
            target.setBackground(controlMaterial(homeScene.control(new Rect(p.leftMargin,p.topMargin,p.leftMargin+p.width,p.topMargin+p.height),p.height/2f),p.height/2f));
        }
        if(hdmiPreview!=null){
            FrameLayout.LayoutParams p=(FrameLayout.LayoutParams)hdmiPreview.getLayoutParams();int y=Math.round(hdmiPreview.getY());
            hdmiPreview.setBackground(homeScene.control(new Rect(p.leftMargin,y,p.leftMargin+p.width,y+p.height),32));
        }
        if(miniPlayer!=null&&miniPlayer.showing()){
            FrameLayout.LayoutParams p=(FrameLayout.LayoutParams)miniPlayer.getLayoutParams();miniPlayer.setBackground(homeScene.control(new Rect(p.leftMargin,p.topMargin,p.leftMargin+p.width,p.topMargin+p.height),28));
        }
    }
    private void showWeather(){new WeatherDialog(this,currentWeather).open();}
    void loadWeatherForecast(int city,java.util.function.Consumer<WeatherRepository.Reading> done){
        if(city<0||city>=WeatherRepository.CITIES.length)return;
        if(weather==null)weather=new WeatherRepository(this);
        weather.load(city,reading->{if(prefs.getInt("weatherCity",0)==city)paintWeather(reading);done.accept(reading);});
    }
    void selectWeatherCity(int city,java.util.function.Consumer<WeatherRepository.Reading> done){
        if(city<0||city>=WeatherRepository.CITIES.length)return;
        prefs.edit().putInt("weatherCity",city).apply();if(weather!=null)weather.close();weather=new WeatherRepository(this);
        loadWeatherForecast(city,done);
    }
    private void showAmbient(){
        if(repository==null)return;
        try{
            if(ambientArt==null)try(java.io.InputStream in=getAssets().open("ambient-seed.json")){ambientArt=new org.json.JSONArray(new String(FeedRepository.readLimited(in,32000),java.nio.charset.StandardCharsets.UTF_8));}
            org.json.JSONObject art=ambientArt.getJSONObject((ambientIndex++)%ambientArt.length());String url=art.getString("url");long generation=++artworkGeneration;
            repository.background(url,b->{if(started&&(appIndex==0||selectedProvider().equals("spotify"))&&generation==artworkGeneration&&b!=null){crossfade(b,"ambient:"+url);if(appIndex==0)sourceLabel.setText("Google Ambient · "+art.optString("author"));}});
        }catch(Exception ignored){}
    }
    void showPlayer(){MediaItem item=MediaQueue.get(this).current();if(item==null||!item.provider.equals("podcast")){showQueue();return;}new PlayerDialog(this).open();}
    void playerDockChanged(boolean visible){if(hdmiPreview==null)return;if(!visible&&miniPlayer!=null&&miniPlayer.hasFocus())handler.post(()->hdmiPreview.requestFocus());hdmiPreview.animate().translationY(visible?-240:0).setDuration(220).setInterpolator(EASE_OUT).withEndAction(this::paintHomeGlass).start();wireFocus();}
    void showQueue(){new QueueDialog(this).open();}
    void showPodcast(String name){new QueueDialog(this,name).open();}
    void assistantConnectionChanged(boolean online){if(assistantBadge==null)return;TextView label=(TextView)assistantBadge.findViewWithTag("label");android.text.SpannableString value=new android.text.SpannableString("● Meadow "+(online?"線上":"離線"));value.setSpan(new android.text.style.ForegroundColorSpan(online?0xFF7CDBA5:0xFFE97B7B),0,1,android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);label.setText(value);label.setTextSize(TypedValue.COMPLEX_UNIT_PX,23);assistantBadge.setContentDescription("Meadow，"+(online?"線上":"離線"));}
    private void showAssistant(){if(assistant==null)assistant=new AssistantPanel(this);assistant.open();}
    boolean searchApp(String app,String query){
        if(query.isEmpty()||query.length()>180)return false;
        String pkg=app.equals("spotify")?"com.spotify.tv.android":app.equals("youtube")?APPS[0]:"";if(pkg.isEmpty())return false;
        Intent entry=getPackageManager().getLeanbackLaunchIntentForPackage(pkg);if(entry==null||entry.getComponent()==null)return false;
        Intent search=new Intent(Intent.ACTION_SEARCH).setComponent(entry.getComponent()).setPackage(pkg).putExtra(android.app.SearchManager.QUERY,query).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        return launch(search)||launch(new Intent(Intent.ACTION_VIEW,Uri.parse(app.equals("spotify")?"https://open.spotify.com/search/"+Uri.encode(query):"https://www.youtube.com/results?search_query="+Uri.encode(query))).setPackage(pkg));
    }
    void executeAssistantAction(org.json.JSONObject action,java.util.function.Consumer<org.json.JSONObject> done){
        TvCommandDispatcher.execute(getApplicationContext(),action,done);
    }
    void showPodcastVoiceSearch(){
        if(assistant==null)assistant=new AssistantPanel(this);
        assistant.openForSearch("podcast");
    }
    @Override protected void onStart(){
        super.onStart();miniPlayer.start();weather=new WeatherRepository(this);weather.load(this::paintWeather);sounds.start();started=true;if(hasStarted)refreshApps();hasStarted=true;updateClock();syncPreview();handler.removeCallbacks(rotateArt);handler.postDelayed(rotateArt,12000);
        String remoteAction=getIntent().getStringExtra("action");getIntent().removeExtra("action");if("inputs".equals(remoteAction))handler.post(this::showInputs);if("airplay".equals(remoteAction))handler.post(()->startActivity(new Intent(this,AirPlayActivity.class)));if("assistant".equals(remoteAction))handler.post(this::showAssistant);if("weather".equals(remoteAction))handler.post(this::showWeather);if("queue".equals(remoteAction))handler.post(this::showQueue);if("player".equals(remoteAction))handler.post(this::showPlayer);if("queue_video".equals(remoteAction))handler.post(()->openQueuedVideo(getIntent().getStringExtra("itemId")));
        registerReceiver(minuteReceiver,new IntentFilter(Intent.ACTION_TIME_TICK));IntentFilter packages=new IntentFilter();packages.addAction(Intent.ACTION_PACKAGE_ADDED);packages.addAction(Intent.ACTION_PACKAGE_REMOVED);packages.addAction(Intent.ACTION_PACKAGE_CHANGED);packages.addDataScheme("package");registerReceiver(packageReceiver,packages);
        connectivity=(ConnectivityManager)getSystemService(CONNECTIVITY_SERVICE);
        networkCallback=new ConnectivityManager.NetworkCallback(){@Override public void onAvailable(Network n){handler.post(()->updateNetwork());}@Override public void onLost(Network n){handler.post(()->updateNetwork());}@Override public void onCapabilitiesChanged(Network n,NetworkCapabilities c){handler.post(()->updateNetwork());}};
        try{connectivity.registerDefaultNetworkCallback(networkCallback);}catch(Exception ignored){}
        updateNetwork();
        repository=new FeedRepository(this);repository.load(data->{if(started){entries.clear();entries.addAll(data);for(FeedRepository.Entry e:data)MediaQueue.get(this).register(e);updatePrograms(true);}});
    }
    @Override protected void onStop(){
        sounds.stop();miniPlayer.stop();if(weather!=null){weather.close();weather=null;}started=false;for(AlertDialog d:new ArrayList<>(shownDialogs))if(d.isShowing())d.dismiss();++artworkGeneration;handler.removeCallbacks(resumePreview);if(hdmiPreview!=null)hdmiPreview.stop();handler.removeCallbacks(rotateArt);if(backgroundFade!=null)backgroundFade.cancel();backdrop.animate().cancel();incomingBackdrop.animate().cancel();if(assistant!=null)assistant.close();
        if(pendingArt!=null)handler.removeCallbacks(pendingArt);if(pendingProvider!=null){handler.removeCallbacks(pendingProvider);pendingProvider=null;}
        try{unregisterReceiver(minuteReceiver);unregisterReceiver(packageReceiver);}catch(Exception ignored){}
        try{if(networkCallback!=null)connectivity.unregisterNetworkCallback(networkCallback);}catch(Exception ignored){}
        for(View v:focusables)v.animate().cancel();
        if(repository!=null){repository.close();repository=null;}
        ++programGeneration;requestedProgramImages.clear();
        if(controlGlass!=null){controlGlass.cancel();controlGlass=null;}
        if(homeGlass!=null){homeGlass.cancel();homeGlass=null;}
        clearModalScene();
        super.onStop();
    }
    @Override public void onTrimMemory(int level){super.onTrimMemory(level);if(level>=TRIM_MEMORY_UI_HIDDEN&&!started){backdrop.setImageDrawable(null);incomingBackdrop.setImageDrawable(null);showingArtwork="";requestedProgramImages.clear();for(ImageView image:programImages)image.setImageDrawable(null);}}
    private void updateClock(){
        clock.setText(new SimpleDateFormat("H:mm",Locale.TAIWAN).format(new Date()));
        long at=prefs.getLong("sleepAt",0);timerLabel.setText(at>System.currentTimeMillis()?"睡眠 "+Math.max(1,(at-System.currentTimeMillis())/60000)+"分":"睡眠計時");
    }
    private void updateNetwork(){
        if(!started)return;
        NetworkCapabilities cap=connectivity.getNetworkCapabilities(connectivity.getActiveNetwork());
        boolean connected=cap!=null&&cap.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED);String network=cap!=null&&cap.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)?"Wi-Fi":"網路";
        networkLabel.setSingleLine(false);networkLabel.setMaxLines(2);networkLabel.setLineSpacing(3,1);networkLabel.setText(network+"\n"+(connected?"已連線":"設定連線"));
        networkLabel.setTextSize(TypedValue.COMPLEX_UNIT_PX,24);wifi.setContentDescription(network+(connected?"已連線":"設定連線"));
    }
    private void launchApp(String packageName){
        if(packageName.equals("meadow.podcasts")){showQueue();return;}
        Intent intent=getPackageManager().getLeanbackLaunchIntentForPackage(packageName);
        if(intent==null)intent=getPackageManager().getLaunchIntentForPackage(packageName);
        if(intent!=null){try{startActivity(intent);return;}catch(Exception ignored){}}
        toast("尚未安裝此 App，請從 Google Play 安裝");
    }
    private boolean launch(Intent intent){try{if(intent.resolveActivity(getPackageManager())!=null){startActivity(intent);return true;}}catch(Exception ignored){}return false;}
    private void openSetting(String action){if(!launch(new Intent(action)))launch(new Intent(Settings.ACTION_SETTINGS));}
    private void showProgram(FeedRepository.Entry e){
        if(e.provider.equals("spotify")){showPodcast(e.title);return;}
        String summary=e.summary;if(summary.length()>220)summary=summary.substring(0,220)+"…";
        GlassMenuDialog menu=new GlassMenuDialog(this,e.title,providerName(e.provider)+" · 台灣官方精選");
        TextView details=text(summary,27,WHITE,"sans-serif");details.setGravity(Gravity.TOP);details.setLineSpacing(5,1);details.setMaxLines(6);details.setEllipsize(TextUtils.TruncateAt.END);menu.setContent(details,220,null);
        menu.addOption("program-open","前往 "+providerName(e.provider),"實際播放與訂閱依平台為準",R.drawable.ic_play,true,()->openProgram(e));
        menu.addOption("program-add","加入待看","保存到播放清單",R.drawable.ic_check,true,()->{MediaQueue.get(this).add(MediaItem.video(e));toast("已加入待看清單");});
        menu.addOption("program-source","節目資訊來源","查看資料區域與官方來源",R.drawable.ic_info,false,()->showSource(e));menu.open();
    }
    private void openQueuedVideo(String id){MediaItem item=MediaQueue.get(this).find(id);if(item==null)return;FeedRepository.Entry e=new FeedRepository.Entry();e.provider=item.provider;e.title=item.title;e.official=item.official;openProgram(e);}
    private void openProgram(FeedRepository.Entry e){
        if(e.provider.equals("spotify")){searchApp("spotify",e.title);return;}
        String pkg=ContentPolicy.appPackage(e.provider);
        if(ContentPolicy.officialLink(e.provider,e.official)){
            Intent link=new Intent(Intent.ACTION_VIEW,Uri.parse(e.official)).setPackage(pkg).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            if(launch(link))return;
        }
        launchApp(pkg);toast("請在 "+providerName(e.provider)+" 搜尋："+e.title);
    }
    private void showSource(FeedRepository.Entry e){String checked=e.verifiedAt>0?new SimpleDateFormat("yyyy/MM/dd",Locale.TAIWAN).format(new Date(e.verifiedAt)):"尚未核對";showInformation("節目資訊來源",providerName(e.provider)+" · 台灣公開目錄","資料區域："+e.region+"\n資料核對："+checked+"\n分類："+e.collection+"\n來源："+e.source+"\n\n公開節目資訊並非個人觀看紀錄或平台會員推薦。圖片權利屬原權利人；實際上架、訂閱與播放以 App 為準。");}
    private void showInformation(String title,String subtitle,String body){GlassMenuDialog menu=new GlassMenuDialog(this,title,subtitle);TextView details=text(body,28,WHITE,"sans-serif");details.setGravity(Gravity.TOP);details.setLineSpacing(8,1);ScrollView scroll=new ScrollView(this);scroll.setId(View.generateViewId());scroll.setFocusable(true);scroll.setDefaultFocusHighlightEnabled(false);scroll.setContentDescription("內容，可上下捲動");scroll.setVerticalScrollBarEnabled(false);scroll.setPadding(20,12,20,12);StateListDrawable focus=new StateListDrawable();focus.addState(new int[]{android.R.attr.state_focused},Glass.surface(0x10FFFFFF,0x90FFFFFF,24));focus.addState(new int[]{},new ColorDrawable(Color.TRANSPARENT));scroll.setBackground(focus);scroll.addView(details,new ScrollView.LayoutParams(-1,-2));menu.setContent(scroll,440,scroll);menu.open();}
    private void showAccounts(){
        String name=prefs.getString("profile","家庭");
        GlassMenuDialog menu=new GlassMenuDialog(this,"個人檔案","選擇目前檔案，切換家庭或訪客首頁。");
        menu.addOption("profile-current","目前檔案："+name,"按確認直接切換",R.drawable.ic_person,true,()->{
            String next=name.equals("家庭")?"訪客":"家庭";prefs.edit().putString("profile",next).apply();profileLabel.setText(next);
        });menu.open();
    }
    private void showPower(){
        GlassMenuDialog menu=new GlassMenuDialog(this,"電源","讓電視休息，或開啟電視的電源選單。");
        menu.addOption("power-sleep","進入待機","關閉螢幕",R.drawable.ic_power,true,()->powerAction(AccessibilityService.GLOBAL_ACTION_LOCK_SCREEN));
        menu.addOption("power-system","系統電源選單","更多電源選項",R.drawable.ic_settings,true,()->powerAction(AccessibilityService.GLOBAL_ACTION_POWER_DIALOG));menu.open();
    }
    private void powerAction(int action){if(!RemoteService.systemAction(action))toast("請使用遙控器的電源鍵");}
    private void showNetwork(){
        NetworkCapabilities cap=connectivity==null?null:connectivity.getNetworkCapabilities(connectivity.getActiveNetwork());
        String message=cap!=null&&cap.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)?(cap.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)?"Wi-Fi 已連線。":"有線網路已連線。")+"\n手機、電腦與電視使用相同網路，即可投放內容。":"目前無法確認網際網路連線。請開啟 Wi-Fi 設定檢查。";
        GlassMenuDialog menu=new GlassMenuDialog(this,"網路",message);menu.addOption("network-wifi","Wi-Fi 設定","選擇網路或檢查連線",R.drawable.ic_wifi,true,()->openSetting(Settings.ACTION_WIFI_SETTINGS));menu.open();
    }
    private void showAudio(){
        AudioManager manager=(AudioManager)getSystemService(AUDIO_SERVICE);
        GlassMenuDialog menu=new GlassMenuDialog(this,"音訊","在音量滑桿按左右調整，按下選擇其他音訊設定。");
        LinearLayout box=new LinearLayout(this);box.setOrientation(LinearLayout.VERTICAL);box.setPadding(20,4,20,4);
        TextView label=text("電視音量",28,WHITE,"sans-serif-medium");box.addView(label,new LinearLayout.LayoutParams(-1,48));
        SeekBar seek=new SeekBar(this);seek.setId(View.generateViewId());seek.setTag("controlVolumeSeek");seek.setMax(manager.getStreamMaxVolume(AudioManager.STREAM_MUSIC));seek.setProgress(manager.getStreamVolume(AudioManager.STREAM_MUSIC));seek.setPadding(24,4,24,4);seek.setFocusable(true);seek.setDefaultFocusHighlightEnabled(false);seek.setBackground(capsuleBackground(0x203E4855));
        seek.setProgressTintList(android.content.res.ColorStateList.valueOf(0xFFAEDAC2));seek.setProgressBackgroundTintList(android.content.res.ColorStateList.valueOf(0x556E8780));seek.setThumbTintList(android.content.res.ColorStateList.valueOf(0xFFF4F7F6));box.addView(seek,new LinearLayout.LayoutParams(-1,72));
        Runnable paint=()->{boolean focused=seek.hasFocus();String value="電視音量  "+seek.getProgress()+" / "+seek.getMax();label.setText(value);seek.setContentDescription(value);label.setTextColor(focused?WHITE:MUTED);seek.setThumbTintList(android.content.res.ColorStateList.valueOf(focused?0xFF173326:0xFFF4F7F6));seek.setProgressTintList(android.content.res.ColorStateList.valueOf(focused?0xFF35634C:0xFFAEDAC2));seek.setProgressBackgroundTintList(android.content.res.ColorStateList.valueOf(focused?0x5574877A:0x556E8780));};
        seek.setOnFocusChangeListener((v,on)->paint.run());seek.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener(){public void onStartTrackingTouch(SeekBar s){}public void onStopTrackingTouch(SeekBar s){}public void onProgressChanged(SeekBar s,int n,boolean user){if(user)manager.setStreamVolume(AudioManager.STREAM_MUSIC,n,0);paint.run();}});paint.run();
        menu.setContent(box,144,seek);menu.addOption("audio-bluetooth","藍牙耳機","連接或管理音訊裝置",R.drawable.ic_bluetooth,true,()->openSetting(Settings.ACTION_BLUETOOTH_SETTINGS));menu.addOption("audio-system","系統音訊","電視輸出與聲音設定",R.drawable.ic_audio,true,()->openSetting(Settings.ACTION_SOUND_SETTINGS));menu.open();
    }
    private PendingIntent sleepIntent(){return PendingIntent.getBroadcast(this,17,new Intent(this,SleepReceiver.class),PendingIntent.FLAG_UPDATE_CURRENT|PendingIntent.FLAG_IMMUTABLE);}
    private void showTimer(){
        long remaining=Math.max(0,prefs.getLong("sleepAt",0)-System.currentTimeMillis());
        GlassMenuDialog menu=new GlassMenuDialog(this,"睡眠計時",remaining>0?"已設定，大約 "+Math.max(1,(remaining+59999)/60000)+" 分鐘後待機。":"設定電視自動待機的時間。");
        for(int minutes:new int[]{30,60,90})menu.addOption("timer-"+minutes,minutes+" 分鐘後待機","到時自動關閉螢幕",R.drawable.ic_sleep,true,()->setTimer(minutes));
        View cancel=menu.addOption("timer-off","取消計時",remaining>0?"保持電視開啟":"目前沒有設定計時",R.drawable.ic_back,true,()->setTimer(0));cancel.setEnabled(remaining>0);cancel.setFocusable(remaining>0);menu.open();
    }
    private void setTimer(int minutes){
            AlarmManager alarms=(AlarmManager)getSystemService(ALARM_SERVICE);alarms.cancel(sleepIntent());
            if(minutes==0){prefs.edit().remove("sleepAt").apply();toast("已取消睡眠計時");}
            else {
                long at=System.currentTimeMillis()+minutes*60*1000L;
                try{alarms.setExact(AlarmManager.RTC_WAKEUP,at,sleepIntent());prefs.edit().putLong("sleepAt",at).apply();toast("已設定 "+minutes+" 分鐘後待機");}
                catch(SecurityException e){toast("系統尚未允許精確睡眠計時");}
            }
            updateClock();
    }
    private void showInputs(){
        TvInputManager manager=(TvInputManager)getSystemService(TV_INPUT_SERVICE);List<TvInputInfo> inputs=InputController.inputs(this);
        GlassMenuDialog menu=new GlassMenuDialog(this,"輸入來源",inputs.isEmpty()?"尚無可用輸入來源。接上 HDMI 裝置後，會在這裡顯示。":"白色為已連接，灰色為待機或未連接的裝置。");
        Map<String,View> rows=new HashMap<>();AlertDialog[] opened=new AlertDialog[1];
        TvInputManager.TvInputCallback callback=new TvInputManager.TvInputCallback(){@Override public void onInputStateChanged(String id,int state){View row=rows.get(id);if(row!=null)paintInput(row,state);}};
        for(TvInputInfo info:inputs){
            View row=menu.addOption("input-"+info.getId(),info.loadLabel(this).toString(),"",R.drawable.ic_input,false,()->{if(InputController.open(this,info)){if(opened[0]!=null)opened[0].dismiss();}else toast("無法切換此輸入來源");});
            rows.put(info.getId(),row);paintInput(row,manager.getInputState(info.getId()));
        }
        menu.onDismiss(()->{if(manager!=null)manager.unregisterCallback(callback);});opened[0]=menu.open();if(manager!=null)manager.registerCallback(callback,handler);
    }
    private void paintInput(View row,int state){TextView detail=row.findViewWithTag("menuDetail");if(detail!=null)detail.setText(state==TvInputManager.INPUT_STATE_CONNECTED?"已連接":state==TvInputManager.INPUT_STATE_CONNECTED_STANDBY?"裝置待機中":"未連接");GlassMenuDialog.tone(row,state==TvInputManager.INPUT_STATE_CONNECTED?WHITE:state==TvInputManager.INPUT_STATE_CONNECTED_STANDBY?0xFFB6BECA:0xFF85918F,MUTED);}
    private void showCast(){startActivity(new Intent(this,AirPlayActivity.class));}
    private void showSettings(){
        GlassMenuDialog menu=new GlassMenuDialog(this,"設定","調整首頁外觀、資料與電視設定。");
        menu.addOption("settings-system","電視系統設定","開啟 Android TV 設定",R.drawable.ic_settings,true,()->openSetting(Settings.ACTION_SETTINGS));
        menu.addOption("settings-appearance","首頁外觀與省電","背景、動畫與 HDMI 小窗",R.drawable.ic_home,false,this::showAppearance);
        menu.addOption("settings-refresh","重新整理節目資訊","更新台灣區公開目錄",R.drawable.ic_play,true,()->{if(repository!=null)repository.refresh(data->{entries.clear();entries.addAll(data);for(FeedRepository.Entry e:data)MediaQueue.get(this).register(e);updatePrograms(true);});toast("正在背景更新節目資訊");});
        menu.addOption("settings-about","關於 Meadow TV","版本與資料來源",R.drawable.ic_info,false,()->showInformation("Meadow TV "+BuildConfig.VERSION_NAME,"原生 Android TV 首頁","靜態背景、短焦點動畫與節目資料快取。離開首頁時停止更新。\n\n節目資料來自平台台灣公開目錄，圖片權利屬原權利人。\n圖示：Google Material Symbols · Apache 2.0。\nAirPlay 使用獨立接收器。\n\n此 App 不會取得影音帳號密碼或訂閱資料。"));menu.open();
    }
    private void showAppearance(){
        GlassMenuDialog menu=new GlassMenuDialog(this,"首頁外觀與省電","按確認切換，每次調整都會自動保存。");
        String[] keys={"artwork","reduceMotion","hdmiPreview"},tags={"artwork","motion","hdmi"},labels={"顯示靜態節目背景","減少焦點動畫","首頁 HDMI 小窗"},details={"以節目封面作為首頁背景","使用更簡短的焦點動態","顯示已連接輸入來源的預覽（實驗）"};
        int[] icons={R.drawable.ic_play,R.drawable.ic_sleep,R.drawable.ic_input};
        for(int n=0;n<keys.length;n++){final int index=n;final String key=keys[n];menu.addToggle("appearance-"+tags[n],labels[n],details[n],icons[n],()->prefs.getBoolean(key,index==1?Build.VERSION.SDK_INT<=28:true),on->{
                prefs.edit().putBoolean(key,on).apply();
                if(index==0){backdrop.setAlpha(on?1f:0);if(!on){incomingBackdrop.animate().cancel();incomingBackdrop.setAlpha(0);}if(on)updatePrograms(false);}
                else if(index==1)lowMotion=on;else {if(!on)hdmiPreview.stop();syncPreview();}
            });}menu.open();
    }
    private void tryResumePreview(){if(started&&!controlsOpen&&openDialogs==0&&prefs.getBoolean("hdmiPreview",true)&&hdmiPreview!=null)hdmiPreview.start();}
    private void syncPreview(){handler.removeCallbacks(resumePreview);if(started&&!controlsOpen&&openDialogs==0&&prefs.getBoolean("hdmiPreview",true))handler.postDelayed(resumePreview,650);}
    private void dialogClosed(){openDialogs=Math.max(0,openDialogs-1);shownDialogs.removeIf(d->!d.isShowing());
        dialogRegions.keySet().removeIf(d->!d.isShowing());dialogCornerFractions.keySet().removeIf(d->!d.isShowing());transparentDialogs.removeIf(d->!d.isShowing());if(openDialogs==0)clearModalScene();syncPreview();}
    private void clearModalScene(){if(modalGlass!=null){modalGlass.cancel();modalGlass=null;}modalScene=null;dialogRegions.clear();if(modalBackdrop!=null){modalBackdrop.setVisibility(View.INVISIBLE);modalBackdrop.setImageDrawable(null);}}
    org.json.JSONArray assistantCatalog(){org.json.JSONArray list=new org.json.JSONArray();Map<String,Integer> counts=new HashMap<>();for(FeedRepository.Entry e:entries)try{if(list.length()>=30)break;if(counts.getOrDefault(e.provider,0)>=10)continue;String summary=e.summary==null?"":e.summary;if(summary.length()>400)summary=summary.substring(0,400);list.put(new org.json.JSONObject().put("title",e.title).put("provider",e.provider).put("date",e.date).put("source",e.source).put("summary",summary).put("region",e.region).put("locale",e.locale).put("availability",e.availability).put("collection",e.collection).put("verifiedAt",String.valueOf(e.verifiedAt)));counts.put(e.provider,counts.getOrDefault(e.provider,0)+1);}catch(Exception ignored){}return list;}
    void sizeContentPanel(Window window){if(window!=null){android.util.DisplayMetrics metrics=getResources().getDisplayMetrics();window.setLayout(Math.round(metrics.widthPixels*.70f),Math.round(metrics.heightPixels*.70f));}}
    void showFrosted(AlertDialog dialog,Runnable after){showFrosted(dialog,0,after);}
    void showWeatherOverlay(AlertDialog dialog,Runnable after){
        transparentDialogs.add(dialog);show(dialog);Window window=dialog.getWindow();
        if(window!=null){android.util.DisplayMetrics metrics=getResources().getDisplayMetrics();window.setLayout(metrics.widthPixels,metrics.heightPixels);window.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));window.getDecorView().setClipToOutline(false);}
        dialog.setOnDismissListener(d->{dialogClosed();after.run();});
    }
    void playDialogKey(AlertDialog dialog,KeyEvent event){sounds.key(event,dialog::getCurrentFocus);}
    void showFrosted(AlertDialog dialog,float cornerFraction,Runnable after){
        if(cornerFraction>0)dialogCornerFractions.put(dialog,cornerFraction);
        show(dialog);Window window=dialog.getWindow();sizeContentPanel(window);
        if(window!=null&&cornerFraction>0){WindowManager.LayoutParams bounds=window.getAttributes();window.setBackgroundDrawable(surface(0xE0252A34,0x6FD8E6EB,panelRadius(dialog,bounds.width,bounds.height)));}
        dialog.setOnDismissListener(d->{dialogClosed();after.run();});
    }
    private float panelRadius(AlertDialog dialog,int width,int height){Float fraction=dialogCornerFractions.get(dialog);return fraction==null?54:Math.min(width,height)*fraction;}
    private void show(AlertDialog dialog){
        handler.removeCallbacks(resumePreview);if(hdmiPreview!=null)hdmiPreview.stop();
        openDialogs++;shownDialogs.add(dialog);
        dialog.setOnDismissListener(d->dialogClosed());dialog.show();
        Window window=dialog.getWindow();if(window!=null){window.setBackgroundDrawable(surface(0xE0252A34,0x6FD8E6EB,54));window.setDimAmount(0.12f);window.setLayout((int)(getResources().getDisplayMetrics().widthPixels*0.51),-2);window.getDecorView().setClipToOutline(true);}
        // Wait for the caller to set its final panel size. Sample the matching area
        // of the home scene, instead of stretching a fixed crop into every dialog.
        handler.post(()->{
            Window target=dialog.getWindow();if(!started||!dialog.isShowing()||target==null)return;
            android.util.DisplayMetrics metrics=getResources().getDisplayMetrics();WindowManager.LayoutParams bounds=target.getAttributes();
            int width=bounds.width>0?bounds.width:target.getDecorView().getWidth(),height=bounds.height>0?bounds.height:target.getDecorView().getHeight();
            int sampleWidth=Math.max(1,Math.min(1920,Math.round(width*1920f/Math.max(1,metrics.widthPixels))));
            int sampleHeight=height>0?Math.max(1,Math.min(1080,Math.round(height*1080f/Math.max(1,metrics.heightPixels)))):660;
            Rect region=new Rect((1920-sampleWidth)/2,(1080-sampleHeight)/2,(1920+sampleWidth)/2,(1080+sampleHeight)/2);
            dialogRegions.put(dialog,region);
            if(modalScene!=null){if(!transparentDialogs.contains(dialog))target.setBackgroundDrawable(modalScene.panel(region,panelRadius(dialog,width,height)));return;}
            if(modalGlass==null)modalGlass=Glass.requestScene(stage,getResources(),scene->{
                modalGlass=null;if(!started||openDialogs==0)return;modalScene=scene;modalBackdrop.setImageDrawable(scene.backdrop());modalBackdrop.setVisibility(View.VISIBLE);
                for(Map.Entry<AlertDialog,Rect> entry:dialogRegions.entrySet())if(entry.getKey().isShowing()&&entry.getKey().getWindow()!=null&&!transparentDialogs.contains(entry.getKey())){WindowManager.LayoutParams panel=entry.getKey().getWindow().getAttributes();entry.getKey().getWindow().setBackgroundDrawable(scene.panel(entry.getValue(),panelRadius(entry.getKey(),panel.width,panel.height)));}
            });
        });
        if(dialog.getListView()!=null){ListView list=dialog.getListView();list.setBackgroundColor(Color.TRANSPARENT);list.setSelector(capsuleBackground(0x003E4855));list.setDefaultFocusHighlightEnabled(false);list.setOnItemSelectedListener(new android.widget.AdapterView.OnItemSelectedListener(){public void onItemSelected(android.widget.AdapterView<?> p,View v,int at,long id){styleDialogRows(list);}public void onNothingSelected(android.widget.AdapterView<?> p){styleDialogRows(list);}});list.addOnLayoutChangeListener((v,l,t,r,b,ol,ot,or,ob)->styleDialogRows(list));list.setOnScrollListener(new AbsListView.OnScrollListener(){public void onScrollStateChanged(AbsListView v,int state){}public void onScroll(AbsListView v,int first,int count,int total){styleDialogRows(list);}});list.post(()->styleDialogRows(list));}
        clearBackgrounds(dialog.getWindow().getDecorView());UiSounds.disableDefault(dialog.getWindow().getDecorView());dialog.setOnKeyListener((d,code,event)->{sounds.key(event,dialog::getCurrentFocus);return false;});
    }
    private void styleDialogRows(View view){if(view instanceof ListView){ListView list=(ListView)view;for(int n=0;n<list.getChildCount();n++)paintDialogText(list.getChildAt(n),list.hasFocus()&&list.getSelectedItemPosition()==list.getFirstVisiblePosition()+n);}}
    private void paintDialogText(View view,boolean selected){if(view instanceof TextView)((TextView)view).setTextColor(selected?0xFF142B24:0xFFF1F5F3);if(view instanceof android.view.ViewGroup){android.view.ViewGroup g=(android.view.ViewGroup)view;for(int n=0;n<g.getChildCount();n++)paintDialogText(g.getChildAt(n),selected);}}
    private void clearBackgrounds(View v){if(v.getId()==android.R.id.content||"parentPanel".equals(resourceName(v))||"topPanel".equals(resourceName(v))||"contentPanel".equals(resourceName(v))||"buttonPanel".equals(resourceName(v)))v.setBackgroundColor(Color.TRANSPARENT);if(v instanceof android.view.ViewGroup){ViewGroup g=(ViewGroup)v;for(int i=0;i<g.getChildCount();i++)clearBackgrounds(g.getChildAt(i));}}
    private String resourceName(View v){try{return getResources().getResourceEntryName(v.getId());}catch(Exception e){return "";}}
    @Override protected void onResume(){super.onResume();foreground=true;}
    @Override protected void onPause(){foreground=false;super.onPause();}
    @Override protected void onNewIntent(Intent intent){super.onNewIntent(intent);setIntent(intent);if(Intent.ACTION_MAIN.equals(intent.getAction())&&intent.hasCategory(Intent.CATEGORY_HOME)&&(!shownDialogs.isEmpty()||controlsOpen)){intent.putExtra("fromShortcut",true);for(AlertDialog d:new ArrayList<>(shownDialogs))if(d.isShowing())d.dismiss();if(controlsOpen)toggleControls(false);}if(assistant!=null&&!"assistant".equals(intent.getStringExtra("action")))assistant.hidePanel();if(!intent.getBooleanExtra("fromShortcut",false)&&Intent.ACTION_MAIN.equals(intent.getAction())&&intent.hasCategory(Intent.CATEGORY_HOME)&&foreground&&openDialogs==0&&!controlsOpen){if(InputController.openConnectedHdmi(this))return;}String route=intent.getStringExtra("action");intent.removeExtra("action");if("inputs".equals(route))showInputs();if("airplay".equals(route))startActivity(new Intent(this,AirPlayActivity.class));if("assistant".equals(route))showAssistant();if("weather".equals(route))showWeather();if("queue".equals(route))showQueue();if("player".equals(route))showPlayer();if("queue_video".equals(route))openQueuedVideo(intent.getStringExtra("itemId"));}
    @Override public boolean dispatchKeyEvent(KeyEvent event){if(event.getAction()==KeyEvent.ACTION_DOWN&&event.getKeyCode()==KeyEvent.KEYCODE_DPAD_DOWN&&pendingProvider!=null&&Arrays.asList(appTiles).contains(getCurrentFocus())){handler.removeCallbacks(pendingProvider);pendingProvider=null;updatePrograms(false);}if(sounds!=null)sounds.key(event,this::getCurrentFocus);return super.dispatchKeyEvent(event);}
    @Override public boolean onKeyDown(int code,KeyEvent event){if(code==KeyEvent.KEYCODE_MENU||code==KeyEvent.KEYCODE_TV_INPUT){showInputs();return true;}return super.onKeyDown(code,event);}
    private void toast(String message){Toast.makeText(this,message,Toast.LENGTH_LONG).show();}
    @Override public void onBackPressed(){if(controlsOpen)toggleControls(false);else appTiles[Math.min(appTiles.length-1,appIndex)].requestFocus();}
    @Override public void onRequestPermissionsResult(int code,String[] permissions,int[] results){super.onRequestPermissionsResult(code,permissions,results);if(assistant!=null)assistant.permissionResult(code,results);}
    @Override protected void onDestroy(){if(assistant!=null)assistant.close();if(sounds!=null)sounds.close();super.onDestroy();}

    private static final class ScaleRoot extends FrameLayout {
        private final View stage;
        ScaleRoot(Context c,View stage){super(c);this.stage=stage;stage.setPivotX(0);stage.setPivotY(0);addView(stage,new LayoutParams(1920,1080));setClipChildren(false);}
        @Override protected void onMeasure(int ws,int hs){int w=MeasureSpec.getSize(ws),h=MeasureSpec.getSize(hs);setMeasuredDimension(w,h);stage.measure(MeasureSpec.makeMeasureSpec(1920,MeasureSpec.EXACTLY),MeasureSpec.makeMeasureSpec(1080,MeasureSpec.EXACTLY));}
        @Override protected void onLayout(boolean changed,int l,int t,int r,int b){stage.layout(0,0,1920,1080);stage.setScaleX((r-l)/1920f);stage.setScaleY((b-t)/1080f);}
    }
}
