package com.akai.livingtv;

import org.json.JSONObject;

/** A public show descriptor. Feed URLs stay in the TV repository, not tool arguments. */
final class PodcastShow {
    final String showId,id,title,author,feed,image,source;
    final long directoryId,followedAt;
    final boolean followed;
    PodcastShow(String id,String title,String author,String feed,String image,String source,long directoryId,boolean followed,long followedAt){
        this.showId=id;this.id=id;this.title=title;this.author=author;this.feed=feed;this.image=image;this.source=source;this.directoryId=directoryId;this.followed=followed;this.followedAt=followedAt;
    }
    PodcastShow followed(boolean value,long at){return new PodcastShow(showId,title,author,feed,image,source,directoryId,value,value?at:0);}
    JSONObject metadata(){JSONObject o=new JSONObject();try{o.put("showId",showId).put("title",title).put("author",author).put("image",image).put("source",source).put("followed",followed).put("followedAt",followedAt);}catch(Exception ignored){}return o;}
    JSONObject json(){JSONObject o=metadata();try{o.put("feed",feed).put("directoryId",directoryId);}catch(Exception ignored){}return o;}
    static PodcastShow parse(JSONObject o){return new PodcastShow(o.optString("showId"),o.optString("title"),o.optString("author"),o.optString("feed"),o.optString("image"),o.optString("source"),o.optLong("directoryId"),o.optBoolean("followed"),o.optLong("followedAt"));}
}
