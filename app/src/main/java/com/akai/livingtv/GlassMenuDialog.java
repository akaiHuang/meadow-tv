package com.akai.livingtv;

import android.app.AlertDialog;
import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.StateListDrawable;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import java.util.ArrayList;
import java.util.List;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

/** Control-center menus share one captured backdrop; their controls never start a renderer. */
final class GlassMenuDialog {
    private static final int WIDTH=1560,HEIGHT=860,WHITE=0xFFF1F5F2,MUTED=0xFFBBC7CA,INK=0xFF173326;
    private static final int ROW_HEIGHT=90,CONTENT_TOP=188;
    private final MainActivity activity;
    private final FrameLayout stage,root,contentHost;
    private final LinearLayout rows;
    private final ScrollView scroll;
    private final ImageButton close;
    private final List<MenuRow> options=new ArrayList<>();
    private AlertDialog dialog;
    private View contentFocus;
    private int contentHeight;
    private Runnable dismissed;

    GlassMenuDialog(MainActivity activity,String title,String subtitle){
        this.activity=activity;
        stage=new FrameLayout(activity);
        root=new FrameLayout(activity){
            @Override protected void onMeasure(int width,int height){
                setMeasuredDimension(MeasureSpec.getSize(width),MeasureSpec.getSize(height));
                stage.measure(MeasureSpec.makeMeasureSpec(WIDTH,MeasureSpec.EXACTLY),MeasureSpec.makeMeasureSpec(HEIGHT,MeasureSpec.EXACTLY));
            }
            @Override protected void onLayout(boolean changed,int left,int top,int right,int bottom){
                stage.layout(0,0,WIDTH,HEIGHT);stage.setPivotX(0);stage.setPivotY(0);
                stage.setScaleX((right-left)/(float)WIDTH);stage.setScaleY((bottom-top)/(float)HEIGHT);
            }
        };
        root.setTag("controlMenuRoot");root.setFocusable(false);root.addView(stage,new FrameLayout.LayoutParams(WIDTH,HEIGHT));
        TextView heading=text(title,46,WHITE);heading.setTypeface(android.graphics.Typeface.create("sans-serif",android.graphics.Typeface.BOLD));
        heading.setSingleLine(true);heading.setEllipsize(TextUtils.TruncateAt.END);put(stage,heading,80,47,1260,68);
        TextView caption=text(subtitle,25,MUTED);caption.setMaxLines(2);caption.setGravity(Gravity.TOP);caption.setEllipsize(TextUtils.TruncateAt.END);put(stage,caption,82,120,1258,62);

        close=new ImageButton(activity);close.setId(View.generateViewId());close.setTag("controlMenuClose");
        close.setImageDrawable(new Mark("close",WHITE));close.setScaleType(ImageView.ScaleType.FIT_CENTER);close.setPadding(17,17,17,17);
        close.setFocusable(true);close.setClickable(true);close.setDefaultFocusHighlightEnabled(false);close.setSoundEffectsEnabled(false);
        close.setContentDescription("關閉"+title);close.setBackground(focusSurface(32));
        close.setOnFocusChangeListener((view,focused)->close.setColorFilter(focused?INK:WHITE));
        close.setOnClickListener(view->{if(dialog!=null)dialog.dismiss();});put(stage,close,1416,49,64,64);

        contentHost=new FrameLayout(activity);contentHost.setVisibility(View.GONE);put(stage,contentHost,80,CONTENT_TOP,1400,0);
        rows=new LinearLayout(activity);rows.setOrientation(LinearLayout.VERTICAL);rows.setPadding(4,4,4,4);
        scroll=new ScrollView(activity);scroll.setFocusable(false);scroll.setFillViewport(false);scroll.setVerticalScrollBarEnabled(false);
        scroll.setOverScrollMode(View.OVER_SCROLL_NEVER);scroll.setClipToPadding(false);
        scroll.addView(rows,new ScrollView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,ViewGroup.LayoutParams.WRAP_CONTENT));
        put(stage,scroll,76,CONTENT_TOP,1408,HEIGHT-CONTENT_TOP-52);
        wireFocus();
    }

    View addOption(String key,String label,String detail,int drawableRes,boolean dismissOnSelect,Runnable action){
        MenuRow row=new MenuRow(key,label,detail,drawableRes,null);
        row.setOnClickListener(view->{
            if(dismissOnSelect&&dialog!=null)dialog.dismiss();
            if(action!=null)action.run();
        });
        add(row);return row;
    }

    View addToggle(String key,String label,String detail,int drawableRes,BooleanSupplier checked,Consumer<Boolean> change){
        MenuRow row=new MenuRow(key,label,detail,drawableRes,checked);
        row.setOnClickListener(view->{if(change!=null)change.accept(!row.checked());row.paint();});
        add(row);return row;
    }

    /** stageHeight is the custom content's height in the 1560 x 860 logical stage. */
    void setContent(View content,int stageHeight,View initialFocus){
        contentHost.removeAllViews();contentFocus=initialFocus;
        contentHeight=content==null?0:Math.max(0,Math.min(440,stageHeight));
        if(content!=null){detach(content);contentHost.addView(content,new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,ViewGroup.LayoutParams.MATCH_PARENT));}
        contentHost.setVisibility(contentHeight>0?View.VISIBLE:View.GONE);
        FrameLayout.LayoutParams contentBounds=(FrameLayout.LayoutParams)contentHost.getLayoutParams();contentBounds.height=contentHeight;contentHost.setLayoutParams(contentBounds);
        FrameLayout.LayoutParams listBounds=(FrameLayout.LayoutParams)scroll.getLayoutParams();
        listBounds.topMargin=CONTENT_TOP+(contentHeight>0?contentHeight+16:0);listBounds.height=HEIGHT-listBounds.topMargin-52;scroll.setLayoutParams(listBounds);
        wireFocus();
    }

    void onDismiss(Runnable callback){dismissed=callback;}

    AlertDialog open(){
        if(dialog!=null&&dialog.isShowing())return dialog;
        detach(root);dialog=new AlertDialog.Builder(activity).create();dialog.setView(root,0,0,0,0);
        activity.showFrosted(dialog,()->{if(dismissed!=null)dismissed.run();});
        Window window=dialog.getWindow();if(window!=null)window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN);
        for(MenuRow row:options)row.paint();wireFocus();firstFocus().requestFocus();return dialog;
    }

    /** Dynamic input availability changes idle colors without whitening focused captions. */
    static void tone(View row,int normalText,int normalDetail){
        if(row instanceof MenuRow){MenuRow option=(MenuRow)row;option.normalText=normalText;option.normalDetail=normalDetail;option.paint();return;}
        View label=row.findViewWithTag("menuLabel"),detail=row.findViewWithTag("menuDetail");
        if(label instanceof TextView)((TextView)label).setTextColor(row.isEnabled()&&row.hasFocus()?INK:normalText);
        if(detail instanceof TextView)((TextView)detail).setTextColor(row.isEnabled()&&row.hasFocus()?INK:normalDetail);
    }

    private void add(MenuRow row){
        options.add(row);LinearLayout.LayoutParams bounds=new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,ROW_HEIGHT);bounds.bottomMargin=12;
        rows.addView(row,bounds);wireFocus();
    }
    private boolean available(View view){return view!=null&&view.isEnabled()&&view.isFocusable()&&view.getVisibility()==View.VISIBLE;}
    private View customFocus(){return contentHeight>0&&available(contentFocus)?contentFocus:null;}
    private View firstFocus(){View custom=customFocus();if(custom!=null)return custom;for(MenuRow row:options)if(available(row))return row;return close;}
    private void wireFocus(){
        if(close==null)return;
        List<MenuRow> enabled=new ArrayList<>();for(MenuRow row:options)if(available(row))enabled.add(row);
        View custom=customFocus(),first=enabled.isEmpty()?null:enabled.get(0);
        if(custom!=null&&custom.getId()==View.NO_ID)custom.setId(View.generateViewId());
        close.setNextFocusUpId(close.getId());close.setNextFocusLeftId(close.getId());close.setNextFocusRightId(close.getId());
        close.setNextFocusDownId((custom!=null?custom:first!=null?first:close).getId());
        if(custom!=null){
            custom.setNextFocusUpId(close.getId());custom.setNextFocusDownId((first!=null?first:close).getId());
        }
        for(int i=0;i<enabled.size();i++){
            MenuRow row=enabled.get(i);row.setNextFocusLeftId(row.getId());row.setNextFocusRightId(row.getId());
            row.setNextFocusUpId((i>0?enabled.get(i-1):custom!=null?custom:close).getId());
            row.setNextFocusDownId((i+1<enabled.size()?enabled.get(i+1):row).getId());
        }
    }
    private TextView text(String value,int size,int color){
        TextView view=new TextView(activity);view.setText(value==null?"":value);view.setTextSize(TypedValue.COMPLEX_UNIT_PX,size);
        view.setTextColor(color);view.setIncludeFontPadding(false);view.setGravity(Gravity.CENTER_VERTICAL);return view;
    }
    private static StateListDrawable focusSurface(float radius){
        StateListDrawable surface=new StateListDrawable();
        surface.addState(new int[]{android.R.attr.state_focused},Glass.surface(0xFFF1F6F3,0xDDFFFFFF,radius));
        surface.addState(new int[]{},Glass.surface(0x12FFFFFF,0x50FFFFFF,radius));return surface;
    }
    private static void detach(View view){if(view.getParent() instanceof ViewGroup)((ViewGroup)view.getParent()).removeView(view);}
    private static void put(FrameLayout parent,View view,int left,int top,int width,int height){
        FrameLayout.LayoutParams bounds=new FrameLayout.LayoutParams(width,height);bounds.leftMargin=left;bounds.topMargin=top;parent.addView(view,bounds);
    }

    private final class MenuRow extends FrameLayout {
        private final TextView label,detail;
        private final ImageView icon,accessory;
        private final BooleanSupplier check;
        private int normalText=WHITE,normalDetail=MUTED;
        MenuRow(String key,String title,String description,int drawableRes,BooleanSupplier check){
            super(activity);this.check=check;setId(View.generateViewId());setTag("controlMenu:"+key);
            setFocusable(true);setClickable(true);setDefaultFocusHighlightEnabled(false);setSoundEffectsEnabled(false);
            icon=new ImageView(activity);icon.setScaleType(ImageView.ScaleType.FIT_CENTER);icon.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
            int textLeft=30;if(drawableRes!=0){icon.setImageResource(drawableRes);put(this,icon,30,25,40,40);textLeft=92;}
            label=text(title,29,WHITE);label.setTag("menuLabel");label.setSingleLine(true);label.setEllipsize(TextUtils.TruncateAt.END);
            detail=text(description,22,MUTED);detail.setTag("menuDetail");detail.setSingleLine(true);detail.setEllipsize(TextUtils.TruncateAt.END);
            boolean hasDetail=description!=null&&!description.isEmpty();
            put(this,label,textLeft,hasDetail?11:0,1192-textLeft,hasDetail?37:ROW_HEIGHT);
            put(this,detail,textLeft,49,1192-textLeft,29);detail.setVisibility(hasDetail?View.VISIBLE:View.GONE);
            accessory=new ImageView(activity);accessory.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
            accessory.setScaleType(ImageView.ScaleType.FIT_CENTER);put(this,accessory,check==null?1323:1296,check==null?29:27,check==null?32:60,check==null?32:36);
            setContentDescription((title==null?"":title)+(hasDetail?"，"+description:""));
            setOnFocusChangeListener((view,focused)->paint());paint();
        }
        boolean checked(){return check!=null&&check.getAsBoolean();}
        @Override public void setEnabled(boolean enabled){
            boolean focused=hasFocus();super.setEnabled(enabled);setFocusable(enabled);
            if(label!=null){paint();wireFocus();if(focused&&!enabled&&dialog!=null&&dialog.isShowing())firstFocus().requestFocus();}
        }
        void paint(){
            boolean focused=isEnabled()&&hasFocus();int titleColor=focused?INK:isEnabled()?normalText:0xFF8A969D;
            int detailColor=focused?INK:isEnabled()?normalDetail:0xFF78858E;
            // Input-state callbacks can add a detail after a row was constructed.
            boolean hasDetail=detail.length()>0;detail.setVisibility(hasDetail?View.VISIBLE:View.GONE);
            FrameLayout.LayoutParams labelBounds=(FrameLayout.LayoutParams)label.getLayoutParams();
            int labelTop=hasDetail?11:0,labelHeight=hasDetail?37:ROW_HEIGHT;
            if(labelBounds.topMargin!=labelTop||labelBounds.height!=labelHeight){labelBounds.topMargin=labelTop;labelBounds.height=labelHeight;label.setLayoutParams(labelBounds);}
            setBackground(Glass.surface(focused?0xFFF1F6F3:isEnabled()?0x12FFFFFF:0x08FFFFFF,focused?0xDDFFFFFF:isEnabled()?0x50FFFFFF:0x24FFFFFF,45));
            label.setTextColor(titleColor);detail.setTextColor(detailColor);icon.setColorFilter(titleColor);
            accessory.setImageDrawable(new Mark(check==null?"chevron":checked()?"on":"off",focused?INK:isEnabled()?normalDetail:0xFF78858E));
            String description=label.getText().toString()+(hasDetail?"，"+detail.getText():"");
            if(check!=null){
                boolean selected=checked();setSelected(selected);
                if(android.os.Build.VERSION.SDK_INT>=30)setStateDescription(selected?"開啟":"關閉");
                else description+="，"+(selected?"開啟":"關閉");
            }
            setContentDescription(description);
        }
    }

    /** A few small vector strokes keep icon geometry independent of the installed font. */
    private static final class Mark extends Drawable {
        private final String kind;private final Paint paint=new Paint(Paint.ANTI_ALIAS_FLAG);
        Mark(String kind,int color){this.kind=kind;paint.setColor(color);paint.setStrokeCap(Paint.Cap.ROUND);paint.setStrokeJoin(Paint.Join.ROUND);}
        @Override public void draw(Canvas canvas){
            Rect bounds=getBounds();int saved=canvas.save();canvas.translate(bounds.left,bounds.top);canvas.scale(bounds.width()/24f,bounds.height()/24f);
            paint.setStyle(Paint.Style.STROKE);paint.setStrokeWidth(1.8f);
            if(kind.equals("close")){canvas.drawLine(6,6,18,18,paint);canvas.drawLine(18,6,6,18,paint);}
            else if(kind.equals("chevron")){canvas.drawLine(9,5,16,12,paint);canvas.drawLine(16,12,9,19,paint);}
            else{canvas.drawRoundRect(1,5,23,19,7,7,paint);paint.setStyle(Paint.Style.FILL);canvas.drawCircle(kind.equals("on")?17:7,12,3.6f,paint);}
            canvas.restoreToCount(saved);
        }
        @Override public void setAlpha(int alpha){paint.setAlpha(alpha);invalidateSelf();}
        @Override public void setColorFilter(ColorFilter filter){paint.setColorFilter(filter);invalidateSelf();}
        @Override public int getOpacity(){return PixelFormat.TRANSLUCENT;}
    }
}
