package com.akai.livingtv;
import org.json.*;
final class MediaItem {
    String id="",title="",show="",showId="",date="",image="",source="",audio="",provider="podcast",official="";
    JSONObject json(){JSONObject o=new JSONObject();try{o.put("id",id).put("title",title).put("show",show).put("showId",showId).put("date",date).put("image",image).put("source",source).put("audio",audio).put("provider",provider).put("official",official);}catch(Exception ignored){}return o;}
    JSONObject metadata(){JSONObject o=json();o.remove("audio");o.remove("image");o.remove("official");return o;}
    static MediaItem parse(JSONObject o){MediaItem i=new MediaItem();i.id=o.optString("id");i.title=o.optString("title");i.show=o.optString("show");i.showId=o.optString("showId");i.date=o.optString("date");i.image=o.optString("image");i.source=o.optString("source");i.audio=o.optString("audio");i.provider=o.optString("provider","podcast");i.official=o.optString("official");return i;}
    static MediaItem video(FeedRepository.Entry e){MediaItem i=new MediaItem();i.id="video:"+e.provider+":"+e.id;i.title=e.title;i.show=e.provider;i.provider=e.provider;i.image=e.poster;i.source=e.source;i.official=e.official;return i;}
}
