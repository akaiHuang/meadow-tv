package com.akai.livingtv;
import android.content.Context;
import org.json.*;
import java.util.*;
import java.util.function.Consumer;

/** Typed Harness endpoint; IDs must exist in publisher/TV metadata or the saved queue. */
final class QueueController {
    static void execute(Context c,JSONObject command,Consumer<JSONObject> done){MediaQueue q=MediaQueue.get(c);PodcastRepository podcasts=PodcastRepository.get(c);String op=command.optString("operation");try{
        if(op.equals("search")){podcasts.searchEpisodesResult(command.optString("query"),result->replyEpisodes(done,q,result));return;}
        if(op.equals("search_shows")){podcasts.searchShowsResult(command.optString("query"),result->{JSONObject extra=new JSONObject();try{extra.put("shows",showMetadata(result.items)).put("errorCode",result.errorCode).put("stale",result.stale);}catch(Exception ignored){}reply(done,result.ok(),result.ok()?"已讀取公開 Podcast 節目；尚未收藏或播放。":podcastError(result.errorCode),q,extra);});return;}
        if(op.equals("episodes")){podcasts.episodesResult(command.optString("showId"),result->replyEpisodes(done,q,result));return;}
        if(op.equals("list_shows")){reply(done,true,"這是電視已收藏的 Podcast 節目。",q,new JSONObject().put("shows",showMetadata(podcasts.followedShows())).put("errorCode",""));return;}
        if(op.equals("follow_show")||op.equals("unfollow_show")){
            String showId=command.optString("showId");boolean follow=op.equals("follow_show");boolean ok=follow?podcasts.follow(showId):podcasts.unfollow(showId);String error=podcasts.lastError();
            JSONObject extra=new JSONObject().put("shows",showMetadata(podcasts.followedShows())).put("persisted",ok).put("errorCode",error);PodcastShow show=podcasts.show(showId);if(show!=null)extra.put("show",show.metadata());
            reply(done,ok,ok?(follow?"已收藏此節目；尚未加入待播或播放。":"已取消收藏；保留現有待播清單與播放狀態。"):podcastError(error),q,extra);return;
        }
        boolean ok=false;String message="";int at=command.optInt("index",-1);String id=command.optString("itemId");
        switch(op){
            case "search_video":JSONArray videos=new JSONArray();String term=command.optString("query").toLowerCase(Locale.ROOT).trim();for(MediaItem item:q.availableVideos())if(item.title.toLowerCase(Locale.ROOT).contains(term)&&videos.length()<20)videos.put(item.metadata());reply(done,true,"已讀取公開影片目錄；尚未播放。",q,new JSONObject().put("results",videos));return;
            case "list": ok=true;message="這是電視目前的待播清單與可選項目。";break;
            case "add": JSONArray ids=command.optJSONArray("itemIds");if(ids==null){ids=new JSONArray();ids.put(id);}int accepted=0;for(int n=0;n<ids.length()&&n<20;n++){MediaItem item=q.find(ids.optString(n));if(item!=null&&q.add(item)>=0)accepted++;}ok=accepted==ids.length()&&accepted>0;message="已加入／保留 "+accepted+" 項；"+(ok?"尚未播放。":"部分 ID 不存在，請先搜尋或讀取清單。");break;
            case "play": if(!id.isEmpty()){MediaItem play=q.find(id);if(play==null){reply(done,false,"找不到此節目 ID，沒有啟動其他項目。",q,null);return;}at=q.add(play);}if(at<0)at=q.index>=0?q.index:0;ok=q.select(at);if(ok)MediaQueueService.command(c,"PLAY");message=ok?"已送出播放要求，state=preparing；只有 playing 才代表音源已開始。":"待播清單沒有此項目。";break;
            case "stop":MediaQueueService.stopPlayback(c);ok=true;message="已停止 Podcast 並釋放音訊；保留待播順序。";break;
            case "pause":MediaQueueService.command(c,"PAUSE");ok=true;message="已送出暫停要求。";break;
            case "next":MediaQueueService.command(c,"NEXT");ok=true;message="已送出下一項要求。";break;
            case "remove":ok=q.remove(at);message=ok?"已移除指定項目。":"找不到此索引。";break;
            case "move":ok=q.move(at,command.optInt("to",-1));message=ok?"已調整待播順序。":"排序索引無效。";break;
            case "clear":q.clear();ok=true;message="已停止播放並清空待播清單。";break;
            default:message="不支援此清單操作。";
        }reply(done,ok,message,q,null);
    }catch(Exception e){reply(done,false,"清單操作未完成。",q,null);}}
    private static JSONArray showMetadata(List<PodcastShow> shows){JSONArray result=new JSONArray();for(PodcastShow show:shows)result.put(show.metadata());return result;}
    private static void replyEpisodes(Consumer<JSONObject> done,MediaQueue q,PodcastRepository.Result<MediaItem> result){
        JSONArray found=new JSONArray();for(MediaItem item:result.items)found.put(item.metadata());JSONObject extra=new JSONObject();try{extra.put("results",found).put("errorCode",result.errorCode).put("stale",result.stale);}catch(Exception ignored){}
        reply(done,result.ok(),result.ok()?"已讀取公開 Podcast 單集；尚未播放。":podcastError(result.errorCode),q,extra);
    }
    private static String podcastError(String error){
        switch(error){case "timeout":return "Podcast 來源連線逾時，尚未完成搜尋；可稍後重試。";case "directory_unavailable":return "公開 Podcast 目錄暫時無法連線。";case "feed_unavailable":return "此節目的公開 RSS 暫時無法讀取。";case "no_playable_episodes":return "此節目目前沒有可播放的公開 HTTPS 單集。";case "not_found":return "找不到此節目 ID，請先搜尋或讀取收藏節目。";case "storage_failed":return "電視未能儲存 Podcast 資料，這次操作沒有確認持久保存。";case "library_full":return "收藏節目已達上限，請先移除一個收藏。";case "invalid_query":return "請提供有效的 Podcast 名稱或主題。";default:return "Podcast 操作尚未完成。";}
    }
    private static void reply(Consumer<JSONObject> done,boolean ok,String message,MediaQueue q,JSONObject extra){JSONObject result=new JSONObject();try{result.put("ok",ok).put("message",message).put("queue",q.snapshot());if(extra!=null)for(String key:new String[]{"results","shows","show","errorCode","stale","persisted"})if(extra.has(key))result.put(key,extra.get(key));}catch(Exception ignored){}done.accept(result);}
}
