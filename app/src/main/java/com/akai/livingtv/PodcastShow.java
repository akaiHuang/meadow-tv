package com.akai.livingtv;

import org.json.JSONObject;
import org.json.JSONArray;
import java.util.*;

/** A public show descriptor. Feed URLs stay in the TV repository, not tool arguments. */
final class PodcastShow {
    final String showId,id,title,author,feed,image,source;
    final long directoryId,followedAt;
    final boolean followed;
    final List<String> genres;
    PodcastShow(String id,String title,String author,String feed,String image,String source,long directoryId,boolean followed,long followedAt){
        this(id,title,author,feed,image,source,directoryId,followed,followedAt,Collections.emptyList());
    }
    PodcastShow(String id,String title,String author,String feed,String image,String source,long directoryId,boolean followed,long followedAt,List<String> genres){
        this.showId=id;this.id=id;this.title=title;this.author=author;this.feed=feed;this.image=image;this.source=source;this.directoryId=directoryId;this.followed=followed;this.followedAt=followedAt;
        LinkedHashSet<String> clean=new LinkedHashSet<>();for(String genre:genres){if(genre!=null&&!genre.trim().isEmpty()&&genre.length()<=80&&clean.size()<8)clean.add(genre.trim());}this.genres=Collections.unmodifiableList(new ArrayList<>(clean));
    }
    PodcastShow followed(boolean value,long at){return new PodcastShow(showId,title,author,feed,image,source,directoryId,value,value?at:0,genres);}
    JSONObject metadata(){JSONObject o=new JSONObject();try{o.put("showId",showId).put("title",title).put("author",author).put("image",image).put("source",source).put("followed",followed).put("followedAt",followedAt).put("genres",new JSONArray(genres));}catch(Exception ignored){}return o;}
    JSONObject json(){JSONObject o=metadata();try{o.put("feed",feed).put("directoryId",directoryId);}catch(Exception ignored){}return o;}
    static PodcastShow parse(JSONObject o){return new PodcastShow(o.optString("showId"),o.optString("title"),o.optString("author"),o.optString("feed"),o.optString("image"),o.optString("source"),o.optLong("directoryId"),o.optBoolean("followed"),o.optLong("followedAt"),genres(o));}
    static List<String> genres(JSONObject o){List<String> result=new ArrayList<>();JSONArray values=o.optJSONArray("genres");if(values!=null)for(int i=0;i<values.length()&&result.size()<8;i++){String value=values.optString(i).trim();if(!value.isEmpty()&&value.length()<=80)result.add(value);}return result;}
}
