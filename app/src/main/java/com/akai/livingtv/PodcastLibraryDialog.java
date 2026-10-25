package com.akai.livingtv;

import android.app.AlertDialog;
import android.graphics.*;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.StateListDrawable;
import android.text.SpannableString;
import android.text.Spanned;
import android.text.TextUtils;
import android.text.style.ImageSpan;
import android.util.TypedValue;
import android.view.*;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.*;
import java.util.*;

/** Show discovery and a persistent library. Adding an episode never changes playback. */
final class PodcastLibraryDialog {
    private static final int WHITE=0xFFF1F5F2, MUTED=0xFFAFBAC0, INK=0xFF142D20;
    private final MainActivity activity;
    private final PodcastRepository podcasts;
    private final MediaQueue queue;
    private final FeedRepository images;
    private final String initialQuery;
    private final Runnable afterClose;
    private AlertDialog dialog;
    private EditText query;
    private TextView searchButton,voiceSearch,followedTab,recommendedTab,resultsTab,preferencesButton,followButton,message,showTitle,showAuthor;
    private ImageButton close;
    private LinearLayout showRows,episodeRows;
    private ScrollView showScroll,episodeScroll;
    private ImageView detailCover;
    private PodcastShow selectedShow;
    private List<PodcastShow> results=Collections.emptyList();
    private final List<View> showTargets=new ArrayList<>(),episodeTargets=new ArrayList<>(),episodeAdditions=new ArrayList<>();
    private boolean alive,favorites=true,recommending;
    private int searchGeneration,episodeGeneration,showImageGeneration;

    PodcastLibraryDialog(MainActivity activity){this(activity,"",null);}
    PodcastLibraryDialog(MainActivity activity,String query){this(activity,query,null);}
    PodcastLibraryDialog(MainActivity activity,String query,Runnable afterClose){
        this.activity=activity;initialQuery=query==null?"":query.trim();this.afterClose=afterClose;
        podcasts=PodcastRepository.get(activity);queue=MediaQueue.get(activity);images=new FeedRepository(activity);
    }

    void open(){
        alive=true;FrameLayout stage=new FrameLayout(activity);
        FrameLayout root=new FrameLayout(activity){
            @Override protected void onMeasure(int width,int height){setMeasuredDimension(MeasureSpec.getSize(width),MeasureSpec.getSize(height));stage.measure(MeasureSpec.makeMeasureSpec(1560,MeasureSpec.EXACTLY),MeasureSpec.makeMeasureSpec(860,MeasureSpec.EXACTLY));}
            @Override protected void onLayout(boolean changed,int left,int top,int right,int bottom){stage.layout(0,0,1560,860);stage.setPivotX(0);stage.setPivotY(0);stage.setScaleX((right-left)/1560f);stage.setScaleY((bottom-top)/860f);}
        };root.addView(stage,new FrameLayout.LayoutParams(1560,860));
        TextView brand=text("MEADOW",23,0xFFD8E1DB);brand.setLetterSpacing(.28f);put(stage,brand,60,24,330,32);
        TextView title=text("我的節目",46,WHITE);title.setTypeface(Typeface.create("sans-serif",Typeface.BOLD));put(stage,title,60,64,1050,64);
        TextView subtitle=text("收藏喜歡的節目，再把想聽的單集加入播放清單。",23,MUTED);put(stage,subtitle,63,136,1280,34);
        close=new ImageButton(activity);close.setId(View.generateViewId());close.setTag("podcastLibraryClose");close.setImageDrawable(new Glyph("close",WHITE));close.setScaleType(ImageView.ScaleType.FIT_CENTER);close.setPadding(17,17,17,17);close.setFocusable(true);close.setClickable(true);close.setDefaultFocusHighlightEnabled(false);close.setBackground(focus(0x203E4853));close.setContentDescription("關閉我的節目");close.setOnClickListener(v->dialog.dismiss());close.setOnFocusChangeListener((v,on)->close.setColorFilter(on?INK:WHITE));put(stage,close,1438,40,62,62);
        voiceSearch=button("語音",()->{dialog.dismiss();activity.showPodcastVoiceSearch();});decorate(voiceSearch,"mic");voiceSearch.setTag("podcastLibraryVoice");put(stage,voiceSearch,60,198,150,62);
        query=new EditText(activity);query.setId(View.generateViewId());query.setTag("podcastLibraryQuery");query.setSingleLine(true);query.setTextColor(WHITE);query.setHintTextColor(0xFFB8C2CC);query.setTextSize(TypedValue.COMPLEX_UNIT_PX,25);query.setPadding(24,0,24,0);query.setHint("搜尋任何 Podcast 節目或主題");query.setImeOptions(EditorInfo.IME_ACTION_SEARCH);query.setBackground(Glass.surface(0x20343C47,0x666F7D8A,32));
        Glyph searchIcon=new Glyph("search",0xFFBCCDC2);searchIcon.setBounds(0,0,27,27);query.setCompoundDrawablesRelative(searchIcon,null,null,null);query.setCompoundDrawablePadding(10);put(stage,query,228,198,1072,62);
        searchButton=button("搜尋",this::search);decorate(searchButton,"search");searchButton.setTag("podcastLibrarySearch");put(stage,searchButton,1318,198,182,62);
        query.setOnEditorActionListener((v,action,event)->{if(action==EditorInfo.IME_ACTION_SEARCH){search();return true;}return false;});
        followedTab=button("已收藏",()->switchList(true));recommendedTab=button("為你推薦",this::showRecommendations);resultsTab=button("搜尋結果",()->switchList(false));
        followedTab.setTag("podcastFollowedTab");recommendedTab.setTag("podcastRecommendedTab");resultsTab.setTag("podcastResultsTab");
        for(TextView tab:new TextView[]{followedTab,recommendedTab,resultsTab})tab.setOnFocusChangeListener((v,on)->paintTabs());
        put(stage,followedTab,60,281,180,58);put(stage,recommendedTab,251,281,205,58);put(stage,resultsTab,467,281,205,58);
        preferencesButton=button("偏好",this::showPreferences);preferencesButton.setTag("podcastPreferences");preferencesButton.setTextSize(TypedValue.COMPLEX_UNIT_PX,22);put(stage,preferencesButton,687,281,125,58);
        showRows=column();showScroll=scroll(showRows);showScroll.setTag("podcastShows");put(stage,showScroll,49,352,763,449);
        View divider=new View(activity);divider.setBackgroundColor(0x335E7666);put(stage,divider,820,303,1,498);
        TextPairRow detail=new TextPairRow(120);detail.setTag("podcastShowDetail");detailCover=cover();detailCover.setTag("art");put(detail,detailCover,0,0,100,100);
        showTitle=text("選一個節目",31,WHITE);showTitle.setTag("podcastShowTitle");showTitle.setTypeface(Typeface.create("sans-serif",Typeface.BOLD));showTitle.setMaxLines(2);put(detail,showTitle,124,0,525,-2);
        showAuthor=text("按確認查看單集",22,MUTED);showAuthor.setTag("podcastShowAuthor");showAuthor.setSingleLine(true);put(detail,showAuthor,124,0,525,-2);detail.textPair(showTitle,showAuthor);put(stage,detail,850,350,650,-2);
        followButton=button("收藏節目",this::toggleFollow);followButton.setTag("podcastFollow");decorate(followButton,"follow");followButton.setEnabled(false);followButton.setFocusable(false);followButton.setAlpha(.38f);put(stage,followButton,850,495,245,58);
        TextView episodes=text("單集",28,WHITE);episodes.setTypeface(Typeface.create("sans-serif",Typeface.BOLD));put(stage,episodes,850,574,650,40);
        episodeRows=column();episodeScroll=scroll(episodeRows);episodeScroll.setTag("podcastEpisodes");put(stage,episodeScroll,839,620,673,181);
        message=text("",19,MUTED);message.setSingleLine(true);message.setTag("podcastLibraryMessage");put(stage,message,64,815,1436,30);
        dialog=new AlertDialog.Builder(activity).create();dialog.setView(root,0,0,0,0);
        activity.showFrosted(dialog,()->{alive=false;++searchGeneration;++episodeGeneration;++showImageGeneration;images.close();if(afterClose!=null)afterClose.run();});
        Window window=dialog.getWindow();if(window!=null)window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN);
        wireHeader();paintTabs();renderShows(podcasts.followedShows());renderEpisodes(Collections.emptyList(),"選取節目後，這裡會顯示可加入的單集。");
        if(initialQuery.isEmpty())followedTab.requestFocus();else {query.setText(initialQuery);query.post(()->{if(alive)search();});}
    }

    private void wireHeader(){
        voiceSearch.setNextFocusLeftId(voiceSearch.getId());voiceSearch.setNextFocusRightId(query.getId());voiceSearch.setNextFocusUpId(close.getId());voiceSearch.setNextFocusDownId(query.getId());
        query.setNextFocusLeftId(voiceSearch.getId());query.setNextFocusRightId(searchButton.getId());query.setNextFocusUpId(close.getId());
        searchButton.setNextFocusLeftId(query.getId());searchButton.setNextFocusRightId(searchButton.getId());searchButton.setNextFocusUpId(close.getId());
        followedTab.setNextFocusLeftId(followedTab.getId());followedTab.setNextFocusRightId(recommendedTab.getId());recommendedTab.setNextFocusLeftId(followedTab.getId());recommendedTab.setNextFocusRightId(resultsTab.getId());resultsTab.setNextFocusLeftId(recommendedTab.getId());resultsTab.setNextFocusRightId(preferencesButton.getId());preferencesButton.setNextFocusLeftId(resultsTab.getId());preferencesButton.setNextFocusRightId(close.getId());
        for(View tab:new View[]{followedTab,recommendedTab,resultsTab,preferencesButton})tab.setNextFocusUpId(query.getId());
        close.setNextFocusLeftId(preferencesButton.getId());close.setNextFocusUpId(close.getId());close.setNextFocusRightId(close.getId());close.setNextFocusDownId(searchButton.getId());
        followButton.setNextFocusUpId(searchButton.getId());followButton.setNextFocusRightId(followButton.getId());
        wireLists();
    }

    private void paintTabs(){
        if(followedTab==null)return;
        followedTab.setSelected(favorites);recommendedTab.setSelected(recommending);resultsTab.setSelected(!favorites&&!recommending);
        for(TextView tab:new TextView[]{followedTab,recommendedTab,resultsTab}){
            String label=tab==followedTab?"已收藏":tab==recommendedTab?"為你推薦":"搜尋結果";boolean current=tab.isSelected(),focused=tab.hasFocus();
            tab.setText(current?"● "+label:label);tab.setTextColor(current||focused?INK:WHITE);tab.setContentDescription(label+(current?"，目前顯示":"，按確認切換"));
            GradientDrawable surface=new GradientDrawable();surface.setCornerRadius(30);surface.setColor(current?0xFFF4F7F6:focused?0x80FFFFFF:Color.TRANSPARENT);tab.setBackground(surface);
        }
    }

    private void switchList(boolean followed){
        favorites=followed;recommending=false;++searchGeneration;paintTabs();renderShows(followed?podcasts.followedShows():results);notice("");
    }
    private void showRecommendations(){favorites=false;recommending=true;++searchGeneration;paintTabs();renderShows(podcasts.recommendations());notice(podcasts.recommendationSummary());}
    private void showPreferences(){
        GlassMenuDialog menu=new GlassMenuDialog(activity,"推薦偏好","只使用這台電視的收藏、播放與已探索節目。");
        menu.addToggle("podcast-personalization","個人化推薦","關閉後使用精選推薦，並停止記錄新的播放偏好。",R.drawable.ic_person,podcasts::personalizationEnabled,value->{if(!podcasts.setPersonalizationEnabled(value))notice("偏好暫時無法儲存，請稍後再試。");});
        menu.addOption("podcast-clear-preferences","清除播放偏好","保留已收藏節目與播放清單。",R.drawable.ic_settings,false,()->{notice(podcasts.clearPlaybackPreferences()?"已清除播放偏好，收藏與清單已保留。":"偏好暫時無法清除，請稍後再試。");});
        menu.onDismiss(()->{if(!alive)return;if(recommending){renderShows(podcasts.recommendations());notice(podcasts.recommendationSummary());}preferencesButton.requestFocusFromTouch();});menu.open();
    }

    private void search(){
        String term=query.getText().toString().trim();
        if(term.isEmpty()){notice("請先輸入節目名稱或想聽的主題。");query.requestFocus();return;}
        hideKeyboard();favorites=false;recommending=false;paintTabs();int generation=++searchGeneration;
        results=Collections.emptyList();renderShows(results,"正在搜尋節目…");notice("正在搜尋公開 Podcast 目錄…");
        podcasts.searchShowsResult(term,result->{
            if(!alive||generation!=searchGeneration||favorites||recommending)return;
            results=result.items;renderShows(results,result.ok()?"沒有找到這個節目，試試其他名稱或主題。":"搜尋暫時無法完成，請稍後再試。");
            notice(result.ok()?(results.isEmpty()?"沒有搜尋結果。":"找到 "+results.size()+" 個節目，按確認查看單集。"):errorText(result.errorCode,result.stale));
        });
    }

    private void renderShows(List<PodcastShow> shows){renderShows(shows,favorites?"還沒收藏節目\n搜尋喜歡的節目，或用 Meadow 加入節目。":"輸入節目名稱或主題，再按搜尋。");}
    private void renderShows(List<PodcastShow> shows,String emptyText){
        if(!alive)return;int generation=++showImageGeneration;showRows.removeAllViews();showTargets.clear();
        for(PodcastShow value:shows){
            PodcastShow current=podcasts.show(value.showId);PodcastShow show=current==null?value:current;
            TextPairRow row=new TextPairRow(116);row.setId(View.generateViewId());row.setTag(show.showId);row.setFocusable(true);row.setClickable(true);row.setDefaultFocusHighlightEnabled(false);row.setBackground(rowSurface());row.setSelected(selectedShow!=null&&selectedShow.showId.equals(show.showId));
            row.setContentDescription(show.title+(show.followed?"，已收藏":"")+"，按確認查看單集");
            ImageView art=cover();art.setTag("art");put(row,art,16,0,88,88);images.thumbnail(show.image,bitmap->{if(alive&&generation==showImageGeneration&&bitmap!=null)art.setImageBitmap(bitmap);});
            TextView title=text(show.title,28,WHITE);title.setTag("podcastLibraryShowTitle");title.setTypeface(Typeface.create("sans-serif",Typeface.BOLD));title.setMaxLines(2);put(row,title,124,0,603,-2);
            TextView author=text((show.author.isEmpty()?"Podcast":show.author)+(show.followed?" · 已收藏":""),22,MUTED);author.setTag("podcastLibraryShowAuthor");author.setSingleLine(true);put(row,author,124,0,603,-2);row.textPair(title,author);
            row.setOnFocusChangeListener((v,on)->{title.setTextColor(on?INK:WHITE);author.setTextColor(on?0xFF3D6050:MUTED);});
            row.setOnClickListener(v->chooseShow(show));LinearLayout.LayoutParams bounds=new LinearLayout.LayoutParams(-1,-2);bounds.bottomMargin=8;showRows.addView(row,bounds);showTargets.add(row);
        }
        if(shows.isEmpty()){
            TextView empty=text(emptyText,27,MUTED);empty.setMaxLines(4);empty.setLineSpacing(8,1);empty.setGravity(Gravity.TOP);empty.setPadding(14,22,22,18);empty.setTag("podcastLibraryEmpty");showRows.addView(empty,new LinearLayout.LayoutParams(-1,-2));
            if(favorites){TextView find=button("搜尋新節目",()->query.requestFocus());decorate(find,"search");find.setTag("podcastLibraryFind");LinearLayout.LayoutParams bounds=new LinearLayout.LayoutParams(245,58);bounds.leftMargin=14;bounds.topMargin=10;showRows.addView(find,bounds);showTargets.add(find);}
        }
        wireLists();
    }

    private void chooseShow(PodcastShow show){
        if(!alive)return;selectedShow=podcasts.show(show.showId);if(selectedShow==null)selectedShow=show;
        showTitle.setText(selectedShow.title);showAuthor.setText(selectedShow.author.isEmpty()?"Podcast":selectedShow.author);detailCover.setImageDrawable(null);
        String id=selectedShow.showId;images.thumbnail(selectedShow.image,bitmap->{if(alive&&selectedShow!=null&&id.equals(selectedShow.showId)&&bitmap!=null)detailCover.setImageBitmap(bitmap);});
        paintFollow();for(View row:showTargets)row.setSelected(id.equals(row.getTag()));
        int generation=++episodeGeneration;renderEpisodes(Collections.emptyList(),"正在讀取單集…");notice("正在讀取「"+selectedShow.title+"」…");
        podcasts.episodesResult(id,result->{
            if(!alive||generation!=episodeGeneration||selectedShow==null||!id.equals(selectedShow.showId))return;
            renderEpisodes(result.items,result.ok()?"目前還沒有可播放的單集。":"暫時無法讀取單集，請重新選取節目再試。");
            notice(result.ok()?(result.items.isEmpty()?"目前沒有可播放的單集。":""):errorText(result.errorCode,result.stale));
        });
    }

    private void paintFollow(){
        boolean available=selectedShow!=null;followButton.setEnabled(available);followButton.setFocusable(available);followButton.setAlpha(available?1:.38f);
        boolean followed=available&&isFollowed(selectedShow.showId);followButton.setText(followed?"取消收藏":"收藏節目");decorate(followButton,followed?"followed":"follow");followButton.setContentDescription(available?(followed?"取消收藏：":"收藏節目：")+selectedShow.title:"先選取節目再收藏");wireLists();
    }

    private boolean isFollowed(String id){PodcastShow current=podcasts.show(id);return current!=null&&current.followed;}
    private void toggleFollow(){
        if(!alive||selectedShow==null)return;String id=selectedShow.showId;boolean wasFollowed=isFollowed(id);
        boolean saved=wasFollowed?podcasts.unfollow(id):podcasts.follow(id);
        if(!saved){notice(errorText(podcasts.lastError(),false));return;}
        PodcastShow current=podcasts.show(id);if(current!=null)selectedShow=current;
        paintFollow();renderShows(favorites?podcasts.followedShows():recommending?podcasts.recommendations():results);notice(wasFollowed?"已取消收藏，原本的播放清單不受影響。":"已收藏："+selectedShow.title);followButton.requestFocus();
    }

    private void renderEpisodes(List<MediaItem> episodes,String emptyText){
        if(!alive)return;episodeRows.removeAllViews();episodeTargets.clear();episodeAdditions.clear();
        for(MediaItem item:episodes){
            LinearLayout wrapper=new LinearLayout(activity);wrapper.setGravity(Gravity.CENTER_VERTICAL);wrapper.setBackground(episodeSurface());
            TextPairRow row=new TextPairRow(106);row.setId(View.generateViewId());row.setTag("podcastEpisode:"+item.id);row.setFocusable(true);row.setClickable(true);row.setDefaultFocusHighlightEnabled(false);row.setContentDescription(item.title+"，按確認加入播放清單");
            TextView title=text(item.title,26,WHITE);title.setTag("podcastEpisodeTitle");title.setTypeface(Typeface.create("sans-serif",Typeface.BOLD));title.setMaxLines(2);put(row,title,14,0,546,-2);
            TextView date=text(item.date.isEmpty()?item.show:item.date,21,MUTED);date.setTag("podcastEpisodeDate");date.setSingleLine(true);put(row,date,14,0,546,-2);row.textPair(title,date);
            TextView add=button("＋",()->add(item));add.setTag("podcastEpisodeAdd:"+item.id);add.setTextSize(TypedValue.COMPLEX_UNIT_PX,34);add.setContentDescription("加入播放清單："+item.title);
            row.setOnClickListener(v->add(item));Runnable paint=()->{boolean focused=row.hasFocus()||add.hasFocus();wrapper.setSelected(focused);title.setTextColor(focused?INK:WHITE);date.setTextColor(focused?0xFF3D6050:MUTED);add.setTextColor(focused?INK:WHITE);};row.setOnFocusChangeListener((v,on)->paint.run());add.setOnFocusChangeListener((v,on)->paint.run());
            wrapper.addView(row,new LinearLayout.LayoutParams(568,-2));LinearLayout.LayoutParams addBounds=new LinearLayout.LayoutParams(62,62);addBounds.leftMargin=14;addBounds.rightMargin=6;wrapper.addView(add,addBounds);row.setNextFocusRightId(add.getId());add.setNextFocusLeftId(row.getId());add.setNextFocusRightId(add.getId());
            LinearLayout.LayoutParams bounds=new LinearLayout.LayoutParams(-1,-2);bounds.bottomMargin=8;episodeRows.addView(wrapper,bounds);episodeTargets.add(row);episodeAdditions.add(add);
        }
        if(episodes.isEmpty()){TextView empty=text(emptyText,24,MUTED);empty.setLineSpacing(7,1);empty.setPadding(14,14,20,10);empty.setMaxLines(4);empty.setGravity(Gravity.TOP);episodeRows.addView(empty,new LinearLayout.LayoutParams(-1,-2));}
        wireLists();
    }

    private void add(MediaItem item){
        int index=queue.add(item);notice(index>=0?"已加入播放清單："+QueueDialog.shortTitle(item.title):"播放清單已滿，先移除一項再加入。");
    }

    private void wireLists(){
        if(query==null||followedTab==null||followButton==null)return;
        View activeTab=favorites?followedTab:recommending?recommendedTab:resultsTab,first=showTargets.isEmpty()?activeTab:showTargets.get(0);
        query.setNextFocusDownId(first.getId());searchButton.setNextFocusDownId(first.getId());for(View tab:new View[]{followedTab,recommendedTab,resultsTab,preferencesButton})tab.setNextFocusDownId(first.getId());
        View detailTarget=followButton.isEnabled()?followButton:searchButton;
        for(int i=0;i<showTargets.size();i++){
            View row=showTargets.get(i);row.setNextFocusUpId(i==0?activeTab.getId():showTargets.get(i-1).getId());row.setNextFocusDownId(showTargets.get(Math.min(showTargets.size()-1,i+1)).getId());row.setNextFocusLeftId(row.getId());row.setNextFocusRightId(detailTarget.getId());
        }
        View last=showTargets.isEmpty()?query:showTargets.get(showTargets.size()-1);followButton.setNextFocusLeftId(last.getId());followButton.setNextFocusDownId(episodeTargets.isEmpty()?followButton.getId():episodeTargets.get(0).getId());
        for(int i=0;i<episodeTargets.size();i++){
            View row=episodeTargets.get(i),add=episodeAdditions.get(i);row.setNextFocusLeftId(last.getId());row.setNextFocusUpId(i==0?followButton.getId():episodeTargets.get(i-1).getId());add.setNextFocusUpId(i==0?followButton.getId():episodeAdditions.get(i-1).getId());row.setNextFocusDownId(episodeTargets.get(Math.min(episodeTargets.size()-1,i+1)).getId());add.setNextFocusDownId(episodeAdditions.get(Math.min(episodeAdditions.size()-1,i+1)).getId());
        }
    }

    private void notice(String text){if(message!=null)message.setText(text);}
    private static String errorText(String code,boolean stale){
        if(stale)return "暫時無法更新，先顯示已儲存的單集。";
        switch(code){
            case "timeout":return "連線逾時，請稍後再試。";
            case "invalid_query":return "請輸入較短的節目名稱或主題。";
            case "no_playable_episodes":return "這個節目目前沒有可播放的單集。";
            case "not_found":return "找不到這個節目，請重新搜尋。";
            case "storage_failed":return "電視無法儲存變更，請稍後再試。";
            case "library_full":return "已收藏 200 個節目，請先取消收藏一個再加入。";
            case "feed_unavailable":return "節目的單集暫時無法讀取，請稍後再試。";
            default:return "Podcast 目錄暫時無法連線，請稍後再試。";
        }
    }
    private void hideKeyboard(){InputMethodManager keyboard=(InputMethodManager)activity.getSystemService(android.content.Context.INPUT_METHOD_SERVICE);if(keyboard!=null)keyboard.hideSoftInputFromWindow(query.getWindowToken(),0);}
    private LinearLayout column(){LinearLayout rows=new LinearLayout(activity);rows.setOrientation(LinearLayout.VERTICAL);return rows;}
    private ScrollView scroll(View child){ScrollView scroll=new ScrollView(activity);scroll.setId(View.generateViewId());scroll.setVerticalScrollBarEnabled(false);scroll.setFillViewport(false);scroll.setPadding(7,3,8,8);scroll.setClipToPadding(false);scroll.addView(child,new ScrollView.LayoutParams(-1,-2));return scroll;}
    private TextView text(String value,int size,int color){TextView text=new TextView(activity);text.setText(value);text.setTextSize(TypedValue.COMPLEX_UNIT_PX,size);text.setTextColor(color);text.setIncludeFontPadding(false);text.setTypeface(Typeface.create("sans-serif",Typeface.NORMAL));text.setGravity(Gravity.CENTER_VERTICAL);text.setEllipsize(TextUtils.TruncateAt.END);return text;}
    private TextView button(String label,Runnable action){TextView text=text(label,24,WHITE);text.setId(View.generateViewId());text.setGravity(Gravity.CENTER);text.setFocusable(true);text.setClickable(true);text.setDefaultFocusHighlightEnabled(false);text.setBackground(focus(0x203E4853));text.setOnClickListener(v->action.run());text.setOnFocusChangeListener((v,on)->{text.setTextColor(on?INK:WHITE);if(text.getText() instanceof Spanned)for(CenteredGlyphSpan span:((Spanned)text.getText()).getSpans(0,text.length(),CenteredGlyphSpan.class))span.getDrawable().setTint(on?INK:WHITE);});return text;}
    private ImageView cover(){ImageView image=new ImageView(activity);image.setScaleType(ImageView.ScaleType.FIT_CENTER);image.setBackground(Glass.surface(0x30343D48,0x00000000,10));image.setClipToOutline(true);return image;}
    private Drawable focus(int fill){StateListDrawable state=new StateListDrawable();state.addState(new int[]{android.R.attr.state_focused},Glass.surface(0xFFE7F1E4,0xFFFFFFFF,36));state.addState(new int[]{},Glass.surface(fill,0x506E7B84,36));return state;}
    private Drawable rowSurface(){StateListDrawable state=new StateListDrawable();state.addState(new int[]{android.R.attr.state_focused},Glass.surface(0xFFE7F1E4,0xFFFFFFFF,22));state.addState(new int[]{android.R.attr.state_selected},Glass.surface(0x3069B18A,0x886CBA95,22));state.addState(new int[]{},Glass.surface(Color.TRANSPARENT,0x00000000,22));return state;}
    private Drawable episodeSurface(){StateListDrawable state=new StateListDrawable();state.addState(new int[]{android.R.attr.state_selected},Glass.surface(0xFFE7F1E4,0xFFFFFFFF,22));state.addState(new int[]{},Glass.surface(Color.TRANSPARENT,0x00000000,22));return state;}
    private void decorate(TextView button,String kind){String label=button.getText().toString();Glyph glyph=new Glyph(kind,button.hasFocus()?INK:WHITE);glyph.setBounds(0,0,25,25);SpannableString text=new SpannableString("\uFFFC"+label);text.setSpan(new CenteredGlyphSpan(glyph),0,1,Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);button.setText(text);button.setContentDescription(label);button.setPadding(18,0,18,0);}
    private void put(FrameLayout parent,View view,int x,int y,int width,int height){FrameLayout.LayoutParams bounds=new FrameLayout.LayoutParams(width,height);bounds.leftMargin=x;bounds.topMargin=y;parent.addView(view,bounds);}

    /** Natural fallback-font metrics prevent two-line CJK titles clipping their metadata. */
    private final class TextPairRow extends FrameLayout {
        private final int minimum;private TextView title,metadata;
        TextPairRow(int minimum){super(activity);this.minimum=minimum;setMinimumHeight(minimum);}
        void textPair(TextView title,TextView metadata){this.title=title;this.metadata=metadata;}
        @Override protected void onMeasure(int widthSpec,int heightSpec){super.onMeasure(widthSpec,MeasureSpec.makeMeasureSpec(0,MeasureSpec.UNSPECIFIED));int height=title==null?0:title.getMeasuredHeight()+6+metadata.getMeasuredHeight()+24;setMeasuredDimension(getMeasuredWidth(),resolveSize(Math.max(minimum,height),heightSpec));}
        @Override protected void onLayout(boolean changed,int left,int top,int right,int bottom){super.onLayout(changed,left,top,right,bottom);if(title==null)return;int height=title.getMeasuredHeight()+6+metadata.getMeasuredHeight(),y=Math.max(2,(getHeight()-height)/2);title.layout(title.getLeft(),y,title.getRight(),y+title.getMeasuredHeight());metadata.layout(metadata.getLeft(),y+title.getMeasuredHeight()+6,metadata.getRight(),y+height);for(int i=0;i<getChildCount();i++){View child=getChildAt(i);if("art".equals(child.getTag())){int center=(getHeight()-child.getMeasuredHeight())/2;child.layout(child.getLeft(),center,child.getRight(),center+child.getMeasuredHeight());}}}
    }
    private static final class CenteredGlyphSpan extends ImageSpan {
        CenteredGlyphSpan(Drawable glyph){super(glyph);}
        @Override public int getSize(Paint paint,CharSequence text,int start,int end,Paint.FontMetricsInt metrics){if(metrics!=null){Paint.FontMetricsInt font=paint.getFontMetricsInt();metrics.ascent=font.ascent;metrics.descent=font.descent;metrics.top=font.top;metrics.bottom=font.bottom;}return getDrawable().getBounds().width()+9;}
        @Override public void draw(Canvas canvas,CharSequence text,int start,int end,float x,int top,int baseline,int bottom,Paint paint){Drawable glyph=getDrawable();Paint.FontMetricsInt font=paint.getFontMetricsInt();int save=canvas.save();canvas.translate(x,baseline+(font.ascent+font.descent)/2f-glyph.getBounds().height()/2f);glyph.draw(canvas);canvas.restoreToCount(save);}
    }
    private static final class Glyph extends Drawable {
        private final String kind;private final Paint paint=new Paint(Paint.ANTI_ALIAS_FLAG);
        Glyph(String kind,int color){this.kind=kind;paint.setColor(color);paint.setStrokeCap(Paint.Cap.ROUND);paint.setStrokeJoin(Paint.Join.ROUND);}
        @Override public void draw(Canvas canvas){Rect bounds=getBounds();int save=canvas.save();canvas.translate(bounds.left,bounds.top);canvas.scale(bounds.width()/24f,bounds.height()/24f);paint.setStyle(Paint.Style.STROKE);paint.setStrokeWidth(1.8f);
            switch(kind){case "close":canvas.drawLine(6,6,18,18,paint);canvas.drawLine(18,6,6,18,paint);break;case "search":canvas.drawCircle(10,10,6.5f,paint);canvas.drawLine(15,15,21,21,paint);break;case "mic":canvas.drawRoundRect(9,2,15,15,3,3,paint);canvas.drawArc(5,7,19,19,0,180,false,paint);canvas.drawLine(12,19,12,22,paint);canvas.drawLine(8,22,16,22,paint);break;case "followed":canvas.drawCircle(12,12,9,paint);canvas.drawLine(7,12,10,15,paint);canvas.drawLine(10,15,17,8,paint);break;default:canvas.drawRoundRect(5,3,19,21,2,2,paint);canvas.drawLine(8,11,16,11,paint);canvas.drawLine(12,7,12,15,paint);}
            canvas.restoreToCount(save);
        }
        @Override public void setTint(int color){paint.setColor(color);paint.setColorFilter(null);invalidateSelf();}
        @Override public void setAlpha(int alpha){paint.setAlpha(alpha);invalidateSelf();}
        @Override public void setColorFilter(ColorFilter filter){paint.setColorFilter(filter);invalidateSelf();}
        @Override public int getOpacity(){return PixelFormat.TRANSLUCENT;}
    }
}
