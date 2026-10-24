package com.akai.livingtv;

import android.app.AlertDialog;
import android.graphics.*;
import android.graphics.drawable.*;
import android.os.Build;
import android.text.SpannableString;
import android.text.Spanned;
import android.text.TextUtils;
import android.text.style.ImageSpan;
import android.util.TypedValue;
import android.view.*;
import android.view.animation.DecelerateInterpolator;
import android.widget.*;
import java.text.SimpleDateFormat;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.*;
import java.util.function.Consumer;

/** Weekly weather and a separate city rail. Only opening/confirming requests weather. */
final class WeatherDialog {
    private static final int WHITE=0xFFF0F5F2, SOFT=0xFFC1D0CA, DIM=0xFFA6BCAF;
    private static final int PANEL_X=288,PANEL_Y=162,PANEL_WIDTH=1344,PANEL_HEIGHT=756;
    private static final float PANEL_RADIUS=226.8f;
    private final MainActivity activity;
    private WeatherRepository.Reading reading;
    private AlertDialog dialog;
    private FrameLayout stage,weeklyPanel,pickerPanel;
    private LinearLayout daysRow;
    private HorizontalScrollView cityRail;
    private LinearLayout cityRow;
    private TextView otherCities,backWeather,city,temperature,condition,source,railCount;
    private ImageButton close,pickerClose;
    private WeatherGlyph glyph;
    private final FrameLayout[] cities=new FrameLayout[WeatherRepository.CITIES.length];
    private final TextView[] cityLabels=new TextView[cities.length],citySummaries=new TextView[cities.length];
    private final FrameLayout[] currentMarks=new FrameLayout[cities.length];
    private final ImageView[] checks=new ImageView[cities.length];
    private final Map<Integer,WeatherRepository.Reading> cityReadings=new HashMap<>();
    private final List<PhotoView> photos=new ArrayList<>();
    private Bitmap photo;
    private int selected,focusedCity,requestGeneration,animationGeneration;
    private boolean alive,pickerOpen,reduceMotion,needsCityLayout;

    WeatherDialog(MainActivity activity,WeatherRepository.Reading reading){
        this.activity=activity;this.reading=reading;
        selected=Math.max(0,Math.min(cities.length-1,activity.getSharedPreferences("living",0).getInt("weatherCity",0)));focusedCity=selected;
        if(reading!=null&&WeatherRepository.CITIES[selected].equals(reading.city))cityReadings.put(selected,reading);
    }
    void open(){
        if(alive)return;alive=true;
        reduceMotion=activity.getSharedPreferences("living",0).getBoolean("reduceMotion",Build.VERSION.SDK_INT<=28);
        BitmapFactory.Options options=new BitmapFactory.Options();options.inPreferredConfig=Bitmap.Config.RGB_565;options.inSampleSize=2;options.inScaled=false;
        photo=BitmapFactory.decodeResource(activity.getResources(),R.drawable.weather_scene,options);
        stage=new FrameLayout(activity);stage.setLayoutDirection(View.LAYOUT_DIRECTION_LTR);stage.setClipChildren(true);stage.setBackgroundColor(0x66020909);
        FrameLayout root=new FrameLayout(activity){
            @Override protected void onMeasure(int width,int height){setMeasuredDimension(MeasureSpec.getSize(width),MeasureSpec.getSize(height));stage.measure(MeasureSpec.makeMeasureSpec(1920,MeasureSpec.EXACTLY),MeasureSpec.makeMeasureSpec(1080,MeasureSpec.EXACTLY));}
            @Override protected void onLayout(boolean changed,int l,int t,int r,int b){stage.layout(0,0,1920,1080);stage.setPivotX(0);stage.setPivotY(0);stage.setScaleX((r-l)/1920f);stage.setScaleY((b-t)/1080f);}
        };root.addView(stage,new FrameLayout.LayoutParams(1920,1080));
        buildWeekly();buildPicker();paintReading();paintCities();setMode(false,false);
        dialog=new AlertDialog(activity){@Override public void onBackPressed(){back();}};
        dialog.setView(root,0,0,0,0);activity.showWeatherOverlay(dialog,this::finish);
        dialog.setOnKeyListener((d,code,event)->{
            activity.playDialogKey(dialog,event);
            if(code==KeyEvent.KEYCODE_BACK||code==KeyEvent.KEYCODE_ESCAPE){if(event.getAction()==KeyEvent.ACTION_UP)back();return true;}
            return false;
        });
        otherCities.requestFocus();requestReading(false);
    }
    private void buildWeekly(){
        weeklyPanel=new RoundedFrame(PANEL_RADIUS);weeklyPanel.setTag("weatherWeekly");
        GradientDrawable glass=new GradientDrawable(GradientDrawable.Orientation.TL_BR,new int[]{0xB34C6860,0xCA1B2B2B,0xE010201E});glass.setCornerRadius(PANEL_RADIUS);weeklyPanel.setBackground(glass);
        GradientDrawable rim=Glass.surface(Color.TRANSPARENT,0x80D9F7E7,PANEL_RADIUS);rim.setStroke(3,0x80D9F7E7);weeklyPanel.setForeground(rim);
        put(stage,weeklyPanel,PANEL_X,PANEL_Y,PANEL_WIDTH,PANEL_HEIGHT);
        put(weeklyPanel,photo(.5f),0,0,PANEL_WIDTH,340);
        View readingShade=new View(activity);readingShade.setBackground(new GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT,new int[]{0xAC11231E,0x4011231E,0x0011231E}));put(weeklyPanel,readingShade,0,0,PANEL_WIDTH,340);
        View shade=new View(activity);shade.setBackground(new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,new int[]{0x4011231E,0x1811231E,0xF01C302C}));put(weeklyPanel,shade,0,0,PANEL_WIDTH,340);
        otherCities=pill("其他城市","cities",this::showPicker);otherCities.setTag("weatherOtherCities");put(weeklyPanel,otherCities,161,57,192,50);
        close=closeButton(()->dialog.dismiss());close.setTag("weatherClose");put(weeklyPanel,close,1130,55,52,52);
        otherCities.setNextFocusLeftId(otherCities.getId());otherCities.setNextFocusRightId(close.getId());otherCities.setNextFocusUpId(otherCities.getId());otherCities.setNextFocusDownId(otherCities.getId());
        close.setNextFocusLeftId(otherCities.getId());close.setNextFocusRightId(close.getId());close.setNextFocusUpId(close.getId());close.setNextFocusDownId(close.getId());
        city=text("",32,WHITE);city.setTypeface(Typeface.create("sans-serif-medium",Typeface.NORMAL));put(weeklyPanel,city,161,140,760,48);
        temperature=text("—",106,WHITE);temperature.setTypeface(Typeface.create("sans-serif-light",Typeface.NORMAL));put(weeklyPanel,temperature,157,185,180,130);
        glyph=new WeatherGlyph(activity);glyph.setFilled(true);put(weeklyPanel,glyph,341,210,76,76);
        condition=text("正在讀取天氣…",20,0xFFD6E5DD);condition.setSingleLine(true);put(weeklyPanel,condition,163,310,800,36);
        TextView forecastHeading=text("未來七天",22,0xFFDBE8E0);put(weeklyPanel,forecastHeading,165,363,630,40);
        TextView levels=text("最高／最低",16,DIM);levels.setGravity(Gravity.RIGHT|Gravity.CENTER_VERTICAL);put(weeklyPanel,levels,835,363,344,40);
        daysRow=new LinearLayout(activity);daysRow.setOrientation(LinearLayout.HORIZONTAL);daysRow.setTag("weatherDays");put(weeklyPanel,daysRow,154,410,1036,274);
        source=text("Open-Meteo · 讀取中 ／ 背景為氛圍示意",14,0xFF9EB5A8);source.setGravity(Gravity.CENTER);source.setSingleLine(true);source.setEllipsize(TextUtils.TruncateAt.END);put(weeklyPanel,source,188,700,968,30);
    }
    private void buildPicker(){
        // No shared plate, mask, border or rounded parent around the independent cards.
        pickerPanel=new FrameLayout(activity);pickerPanel.setTag("weatherCityPicker");pickerPanel.setClipChildren(false);pickerPanel.setClipToPadding(false);put(stage,pickerPanel,0,0,1920,1080);
        backWeather=pill("返回預報","back",this::showWeekly);backWeather.setTag("weatherBack");put(pickerPanel,backWeather,230,219,210,50);
        pickerClose=closeButton(()->dialog.dismiss());pickerClose.setTag("weatherPickerClose");put(pickerPanel,pickerClose,1638,219,52,52);
        backWeather.setNextFocusLeftId(backWeather.getId());backWeather.setNextFocusRightId(pickerClose.getId());backWeather.setNextFocusUpId(backWeather.getId());
        pickerClose.setNextFocusLeftId(backWeather.getId());pickerClose.setNextFocusRightId(pickerClose.getId());pickerClose.setNextFocusUpId(pickerClose.getId());
        TextView heading=text("其他城市",32,WHITE);heading.setTypeface(Typeface.create("sans-serif-medium",Typeface.NORMAL));put(pickerPanel,heading,230,318,700,50);
        TextView guide=text("← → 選擇城市，按確認查看預報",20,0xFFA9C1B4);guide.setGravity(Gravity.RIGHT|Gravity.CENTER_VERTICAL);put(pickerPanel,guide,1010,325,680,38);
        cityRail=new HorizontalScrollView(activity);cityRail.setId(View.generateViewId());cityRail.setFocusable(false);cityRail.setHorizontalScrollBarEnabled(false);cityRail.setHorizontalFadingEdgeEnabled(false);cityRail.setFadingEdgeLength(0);cityRail.setOverScrollMode(View.OVER_SCROLL_NEVER);cityRail.setClipToPadding(false);cityRail.setClipChildren(true);cityRail.setSmoothScrollingEnabled(!reduceMotion);put(pickerPanel,cityRail,0,384,1920,460);
        cityRow=new LinearLayout(activity);cityRow.setOrientation(LinearLayout.HORIZONTAL);cityRow.setGravity(Gravity.CENTER_VERTICAL);cityRow.setPadding(288,0,288,0);cityRow.setClipChildren(false);cityRow.setClipToPadding(false);cityRail.addView(cityRow,new HorizontalScrollView.LayoutParams(-2,-1));
        for(int index=0;index<cities.length;index++)buildCity(index);
        for(int index=0;index<cities.length;index++){
            cities[index].setNextFocusLeftId(cities[Math.max(0,index-1)].getId());cities[index].setNextFocusRightId(cities[Math.min(cities.length-1,index+1)].getId());cities[index].setNextFocusUpId(index<5?backWeather.getId():pickerClose.getId());cities[index].setNextFocusDownId(cities[index].getId());
        }
        cityRail.getViewTreeObserver().addOnGlobalLayoutListener(()->{if(alive&&pickerOpen&&needsCityLayout&&cityRow.getWidth()>0){needsCityLayout=false;centerCity(false);}});
        railCount=text("1 / "+cities.length,18,0xFFA4BCAD);put(pickerPanel,railCount,230,866,500,36);
        TextView backNote=text("按返回鍵保留目前城市",18,0xFFA4BCAD);backNote.setGravity(Gravity.RIGHT|Gravity.CENTER_VERTICAL);put(pickerPanel,backNote,1010,866,680,36);
        TextView photoNote=text("城市相片為共同氛圍示意，非現場或即時影像。",16,0xFF829C8C);photoNote.setGravity(Gravity.CENTER);put(pickerPanel,photoNote,200,1000,1520,36);
    }
    private void buildCity(int index){
        final int choice=index;RoundedFrame card=new RoundedFrame(26);card.setId(View.generateViewId());card.setTag("weatherCity"+index);card.setFocusable(true);card.setClickable(true);card.setDefaultFocusHighlightEnabled(false);card.setBackground(Glass.surface(0xFF243A31,0x44E8FFF8,26));
        put(card,photo((15+(index*19)%76)/100f),0,0,340,440);
        View shade=new View(activity);shade.setBackground(new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,new int[]{0x060C2119,0x100C2119,0xF50C2119}));put(card,shade,0,0,340,440);
        FrameLayout current=new FrameLayout(activity);current.setBackground(Glass.surface(0xD0348055,0x5589E9B1,20));put(card,current,148,28,158,40);currentMarks[index]=current;
        ImageView check=new ImageView(activity);check.setImageDrawable(new Mark("check",0xFFF3FFF7));put(current,check,10,8,24,24);checks[index]=check;
        TextView currentText=text("目前城市",19,0xFFD8F9E2);put(current,currentText,43,0,106,40);
        TextView label=text(WeatherRepository.CITIES[index],35,WHITE);label.setTypeface(Typeface.create("sans-serif-medium",Typeface.NORMAL));put(card,label,40,317,270,58);cityLabels[index]=label;
        TextView summary=text("查看一週天氣",20,0xFFC0D6C9);summary.setMaxLines(2);summary.setEllipsize(TextUtils.TruncateAt.END);put(card,summary,40,377,270,46);citySummaries[index]=summary;
        StateListDrawable focus=new StateListDrawable();GradientDrawable edge=Glass.surface(Color.TRANSPARENT,0xFFF4FFF9,26);edge.setStroke(4,0xFFF4FFF9);focus.addState(new int[]{android.R.attr.state_focused},edge);focus.addState(new int[]{},Glass.surface(Color.TRANSPARENT,0x44E8FFF8,26));card.setForeground(focus);
        card.setOnFocusChangeListener((v,on)->{if(on&&pickerOpen){focusedCity=choice;centerCity(!reduceMotion);}});card.setOnClickListener(v->confirmCity(choice));
        LinearLayout.LayoutParams params=new LinearLayout.LayoutParams(340,440);params.rightMargin=index==cities.length-1?0:32;cityRow.addView(card,params);cities[index]=card;
    }
    private void showPicker(){if(alive){focusedCity=selected;needsCityLayout=true;setMode(true,true);}}
    private void showWeekly(){if(alive)setMode(false,true);}
    private void setMode(boolean picker,boolean animate){
        pickerOpen=picker;int generation=++animationGeneration;
        weeklyPanel.animate().cancel();pickerPanel.animate().cancel();weeklyPanel.setRotationY(0);pickerPanel.setRotationY(0);weeklyPanel.setAlpha(1);pickerPanel.setAlpha(1);
        weeklyPanel.setDescendantFocusability(picker?ViewGroup.FOCUS_BLOCK_DESCENDANTS:ViewGroup.FOCUS_AFTER_DESCENDANTS);pickerPanel.setDescendantFocusability(picker?ViewGroup.FOCUS_AFTER_DESCENDANTS:ViewGroup.FOCUS_BLOCK_DESCENDANTS);
        weeklyPanel.setImportantForAccessibility(picker?View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS:View.IMPORTANT_FOR_ACCESSIBILITY_AUTO);pickerPanel.setImportantForAccessibility(picker?View.IMPORTANT_FOR_ACCESSIBILITY_AUTO:View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS);
        otherCities.setFocusable(!picker);close.setFocusable(!picker);backWeather.setFocusable(picker);pickerClose.setFocusable(picker);for(View card:cities){card.setFocusable(picker);card.setEnabled(picker);}
        FrameLayout incoming=picker?pickerPanel:weeklyPanel,outgoing=picker?weeklyPanel:pickerPanel;
        incoming.setVisibility(View.VISIBLE);paintCities();backWeather.setNextFocusDownId(cities[focusedCity].getId());pickerClose.setNextFocusDownId(cities[focusedCity].getId());
        Runnable focus=()->{if(!alive||pickerOpen!=picker||generation!=animationGeneration)return;if(picker){cities[focusedCity].requestFocus();centerCity(false);}else otherCities.requestFocus();};
        if(reduceMotion||!animate){outgoing.setVisibility(View.GONE);focus.run();return;}
        incoming.setAlpha(0);incoming.setRotationY(7);incoming.setCameraDistance(22000);outgoing.setCameraDistance(22000);
        outgoing.animate().alpha(0).rotationY(-7).setDuration(200).setInterpolator(new DecelerateInterpolator()).withEndAction(()->{if(alive&&generation==animationGeneration)outgoing.setVisibility(View.GONE);}).start();
        incoming.animate().alpha(1).rotationY(0).setDuration(220).setInterpolator(new DecelerateInterpolator()).withEndAction(focus).start();
    }
    private void confirmCity(int index){
        if(!alive||!pickerOpen||index<0||index>=cities.length)return;selected=index;reading=cityReadings.get(index);paintReading();paintCities();showWeekly();requestReading(true);
    }
    private void requestReading(boolean persist){
        int index=selected,generation=++requestGeneration;
        Consumer<WeatherRepository.Reading> done=value->{if(!alive||generation!=requestGeneration||selected!=index||value==null||!WeatherRepository.CITIES[index].equals(value.city))return;reading=value;cityReadings.put(index,value);paintReading();paintCities();};
        if(persist)activity.selectWeatherCity(index,done);else activity.loadWeatherForecast(index,done);
    }
    private void centerCity(boolean smooth){
        if(!pickerOpen||cityRail==null)return;int left=288+focusedCity*372;int max=Math.max(0,cityRow.getWidth()-cityRail.getWidth());int target=Math.max(0,Math.min(max,left-(1920-340)/2));
        if(cityRail.getScrollX()!=target){if(smooth)cityRail.smoothScrollTo(target,0);else cityRail.scrollTo(target,0);}
        backWeather.setNextFocusDownId(cities[focusedCity].getId());pickerClose.setNextFocusDownId(cities[focusedCity].getId());railCount.setText((focusedCity+1)+" / "+cities.length);
    }
    private void back(){if(!alive)return;if(pickerOpen)showWeekly();else dialog.dismiss();}
    private void updateReading(WeatherRepository.Reading value){if(!alive||value==null||!value.city.equals(WeatherRepository.CITIES[selected]))return;reading=value;cityReadings.put(selected,value);paintReading();paintCities();}
    private void paintReading(){
        city.setText(WeatherRepository.CITIES[selected]);boolean matches=reading!=null&&WeatherRepository.CITIES[selected].equals(reading.city);int code=matches?reading.code:-1;
        temperature.setText(matches?currentDegrees(reading):"—");condition.setText(matches?(code>=0?WeatherRepository.description(code):reading.days.isEmpty()?"天氣暫不可用":"天況未提供"):"正在讀取天氣…");glyph.update(code,!matches||reading.day);
        daysRow.removeAllViews();LocalDate today=LocalDate.now(ZoneId.of("Asia/Taipei"));
        for(int index=0;index<7;index++){
            WeatherRepository.Day day=matches&&index<reading.days.size()?reading.days.get(index):null;FrameLayout column=new FrameLayout(activity);column.setTag("weatherDay"+index);
            if(index>0){View divider=new View(activity);divider.setBackgroundColor(0x1AD1F5E6);put(column,divider,0,0,1,274);}
            LocalDate date=null;if(day!=null)try{date=LocalDate.parse(day.date);}catch(Exception ignored){}
            String weekday=date==null?"—":date.equals(today)?"今天":new String[]{"週一","週二","週三","週四","週五","週六","週日"}[date.getDayOfWeek().getValue()-1];
            TextView name=text(weekday,22,WHITE);name.setTag("weatherWeekday");center(column,name,8,140,40);TextView number=text(date==null?"—":date.getMonthValue()+"/"+date.getDayOfMonth(),16,0xFFADBDB6);number.setTag("weatherDate");center(column,number,43,140,28);
            WeatherGlyph icon=new WeatherGlyph(activity);icon.setTag("weatherDayIcon");icon.setFilled(true);icon.update(day==null?-1:day.code,true);center(column,icon,87,58,58);
            String max=day==null?"—":degrees(day.max),min=day==null?"—":degrees(day.min);TextView high=text(max,31,WHITE);high.setTag("weatherMax");center(column,high,156,140,47);TextView low=text(min,25,0xFFA7BDB0);low.setTag("weatherMin");center(column,low,211,140,42);
            column.setContentDescription((date==null?"日期未提供":day.date)+"，"+(day==null?"天況未提供":WeatherRepository.description(day.code))+"，最高 "+max+"，最低 "+min);column.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_YES);
            daysRow.addView(column,new LinearLayout.LayoutParams(0,-1,1));
        }
        String state=!matches?"讀取中":reading.updatedAtMillis>0?(reading.stale?"較早資料 · ":"更新 ")+stamp(reading.updatedAtMillis):"資料暫不可用";
        if(matches&&reading.days.isEmpty())state=code>=0?"七日預報暫不可用": "暫時無法取得天氣";
        source.setText("Open-Meteo · "+state+" ／ 背景為氛圍示意");
    }
    private void paintCities(){
        for(int index=0;index<cities.length;index++){
            if(cities[index]==null)continue;boolean current=index==selected;cities[index].setSelected(current);currentMarks[index].setVisibility(current?View.VISIBLE:View.INVISIBLE);checks[index].setVisibility(current?View.VISIBLE:View.INVISIBLE);
            cities[index].setContentDescription(WeatherRepository.CITIES[index]+(current?"，目前城市":"")+"，按確認查看一週天氣");
            WeatherRepository.Reading cached=cityReadings.get(index);citySummaries[index].setText(cached!=null&&cached.code>=0?currentDegrees(cached)+" · "+WeatherRepository.description(cached.code):"查看一週天氣");
        }
    }
    private void finish(){
        alive=false;++requestGeneration;++animationGeneration;weeklyPanel.animate().cancel();pickerPanel.animate().cancel();for(View card:cities)card.animate().cancel();
        for(PhotoView view:photos)view.clear();photos.clear();photo=null;
    }
    private String currentDegrees(WeatherRepository.Reading value){try{double number=Double.parseDouble(value.text.split("°",2)[0].trim());return Double.isFinite(number)?Math.round(number)+"°":"—";}catch(Exception ignored){return "—";}}
    private static String degrees(Double value){return value==null||!Double.isFinite(value)?"—":Math.round(value)+"°";}
    private String stamp(long time){SimpleDateFormat format=new SimpleDateFormat("MM/dd HH:mm",Locale.TAIWAN);format.setTimeZone(TimeZone.getTimeZone("Asia/Taipei"));return format.format(new Date(time));}
    private PhotoView photo(float position){PhotoView view=new PhotoView(photo,position);photos.add(view);return view;}
    private TextView pill(String caption,String mark,Runnable action){
        TextView button=text("",22,WHITE);button.setId(View.generateViewId());button.setGravity(Gravity.CENTER);button.setFocusable(true);button.setClickable(true);button.setDefaultFocusHighlightEnabled(false);button.setBackground(pillSurface(25));
        Mark icon=new Mark(mark,WHITE);icon.setBounds(0,0,24,24);SpannableString group=new SpannableString("\uFFFC"+caption);group.setSpan(new InlineMark(icon,9),0,1,Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);button.setText(group);button.setContentDescription(caption);button.setOnClickListener(v->action.run());return button;
    }
    private ImageButton closeButton(Runnable action){ImageButton button=new ImageButton(activity);button.setId(View.generateViewId());button.setImageDrawable(new Mark("close",WHITE));button.setPadding(15,15,15,15);button.setScaleType(ImageView.ScaleType.FIT_CENTER);button.setFocusable(true);button.setClickable(true);button.setDefaultFocusHighlightEnabled(false);button.setBackground(pillSurface(26));button.setContentDescription("關閉天氣");button.setOnClickListener(v->action.run());return button;}
    private StateListDrawable pillSurface(float radius){StateListDrawable states=new StateListDrawable();GradientDrawable focused=Glass.surface(0x80527D70,0xFFFFFFFF,radius);focused.setStroke(3,0xFFFFFFFF);states.addState(new int[]{android.R.attr.state_focused},focused);states.addState(new int[]{},Glass.surface(0x254D6E62,0x80D7E3E0,radius));return states;}
    private TextView text(String value,int size,int color){TextView view=new TextView(activity);view.setText(value);view.setTextSize(TypedValue.COMPLEX_UNIT_PX,size);view.setTextColor(color);view.setTypeface(Typeface.create("sans-serif",Typeface.NORMAL));view.setFontFeatureSettings("tnum");view.setGravity(Gravity.CENTER_VERTICAL);view.setIncludeFontPadding(false);return view;}
    private void center(FrameLayout parent,View child,int y,int width,int height){FrameLayout.LayoutParams params=new FrameLayout.LayoutParams(width,height,Gravity.TOP|Gravity.CENTER_HORIZONTAL);params.topMargin=y;if(child instanceof TextView)((TextView)child).setGravity(Gravity.CENTER);parent.addView(child,params);}
    private void put(FrameLayout parent,View child,int x,int y,int width,int height){FrameLayout.LayoutParams params=new FrameLayout.LayoutParams(width,height);params.leftMargin=x;params.topMargin=y;parent.addView(child,params);}
    private final class RoundedFrame extends FrameLayout {
        private final Path clip=new Path();private final float radius;
        RoundedFrame(float radius){super(activity);this.radius=radius;setClipToOutline(true);}
        @Override protected void onSizeChanged(int width,int height,int oldWidth,int oldHeight){clip.reset();clip.addRoundRect(0,0,width,height,radius,radius,Path.Direction.CW);}
        @Override protected void dispatchDraw(Canvas canvas){int save=canvas.save();canvas.clipPath(clip);super.dispatchDraw(canvas);canvas.restoreToCount(save);}
    }
    private final class PhotoView extends View {
        private Bitmap bitmap;private final float position;private final Matrix matrix=new Matrix();private final Paint paint=new Paint(Paint.ANTI_ALIAS_FLAG|Paint.FILTER_BITMAP_FLAG);
        PhotoView(Bitmap bitmap,float position){super(activity);this.bitmap=bitmap;this.position=position;setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);}
        @Override protected void onSizeChanged(int width,int height,int oldWidth,int oldHeight){if(bitmap==null)return;float scale=Math.max(width/(float)bitmap.getWidth(),height/(float)bitmap.getHeight());matrix.setScale(scale,scale);matrix.postTranslate((width-bitmap.getWidth()*scale)*position,(height-bitmap.getHeight()*scale)*.5f);}
        @Override protected void onDraw(Canvas canvas){if(bitmap!=null)canvas.drawBitmap(bitmap,matrix,paint);}
        void clear(){bitmap=null;invalidate();}
    }
    private static final class InlineMark extends ImageSpan {
        private final int gap;InlineMark(Drawable icon,int gap){super(icon);this.gap=gap;}
        @Override public int getSize(Paint paint,CharSequence text,int start,int end,Paint.FontMetricsInt metrics){if(metrics!=null){Paint.FontMetricsInt font=paint.getFontMetricsInt();metrics.ascent=font.ascent;metrics.descent=font.descent;metrics.top=font.top;metrics.bottom=font.bottom;}return getDrawable().getBounds().width()+gap;}
        @Override public void draw(Canvas canvas,CharSequence text,int start,int end,float x,int top,int baseline,int bottom,Paint paint){Drawable icon=getDrawable();Paint.FontMetricsInt font=paint.getFontMetricsInt();float y=baseline+(font.ascent+font.descent)/2f-icon.getBounds().height()/2f;int save=canvas.save();canvas.translate(x,y);icon.draw(canvas);canvas.restoreToCount(save);}
    }
    private static final class Mark extends Drawable {
        private final String kind;private final Paint paint=new Paint(Paint.ANTI_ALIAS_FLAG);private final Path check=new Path();
        Mark(String kind,int color){this.kind=kind;paint.setColor(color);paint.setStyle(Paint.Style.STROKE);paint.setStrokeWidth(1.8f);paint.setStrokeCap(Paint.Cap.ROUND);paint.setStrokeJoin(Paint.Join.ROUND);check.moveTo(5,12);check.lineTo(10,17);check.lineTo(19,7);}
        @Override public void draw(Canvas canvas){Rect bounds=getBounds();int save=canvas.save();canvas.translate(bounds.left,bounds.top);canvas.scale(bounds.width()/24f,bounds.height()/24f);if(kind.equals("close")){canvas.drawLine(5,5,19,19,paint);canvas.drawLine(19,5,5,19,paint);}else if(kind.equals("back")){canvas.drawLine(14,5,7,12,paint);canvas.drawLine(7,12,14,19,paint);}else if(kind.equals("check"))canvas.drawPath(check,paint);else{canvas.drawRect(3,8,10,20,paint);canvas.drawRect(10,4,21,20,paint);for(int column=0;column<3;column++)for(int row=0;row<3;row++)canvas.drawPoint(column==0?6:column==1?14:18,row==0?9:row==1?14:18,paint);canvas.drawLine(1,20,23,20,paint);}canvas.restoreToCount(save);}
        @Override public void setAlpha(int alpha){paint.setAlpha(alpha);invalidateSelf();}@Override public void setColorFilter(ColorFilter filter){paint.setColorFilter(filter);invalidateSelf();}@Override public int getOpacity(){return PixelFormat.TRANSLUCENT;}
    }
}
