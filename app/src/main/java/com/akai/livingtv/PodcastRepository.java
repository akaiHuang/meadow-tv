package com.akai.livingtv;

import android.content.Context;
import android.os.*;
import android.util.AtomicFile;
import android.util.Xml;
import org.json.*;
import org.xmlpull.v1.XmlPullParser;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.*;

/** Public directory/RSS only. The TV owns discovered show IDs, follows, and playable episode IDs. */
final class PodcastRepository {
    private static PodcastRepository instance;
    static synchronized PodcastRepository get(Context c){if(instance==null)instance=new PodcastRepository(c.getApplicationContext());return instance;}
    static final class Result<T> {
        final List<T> items;final String errorCode;final boolean stale;
        Result(List<T> items,String errorCode,boolean stale){this.items=Collections.unmodifiableList(new ArrayList<>(items));this.errorCode=errorCode;this.stale=stale;}
        boolean ok(){return errorCode.isEmpty();}
    }
    interface Transport {byte[] read(String url,int max,boolean prefix)throws Exception;}
    private final Context context;
    private final Handler ui=new Handler(Looper.getMainLooper());
    private final ExecutorService work;
    private final Transport transport;
    private final LongSupplier clock;
    private final LinkedHashMap<String,MediaItem> library=new LinkedHashMap<>();
    private final LinkedHashMap<String,PodcastShow> shows=new LinkedHashMap<>();
    private final Map<String,Long> episodeFetched=new HashMap<>();
    private final List<String> seeds=new ArrayList<>();
    private final AtomicFile cache,showFile;
    private long fetched,defaultsFetched;
    private boolean loading;
    private String lastError="";
    private final List<Consumer<List<MediaItem>>> pending=new ArrayList<>();
    private PodcastRepository(Context c){this(c,PodcastRepository::read,Executors.newSingleThreadExecutor(),System::currentTimeMillis);}
    PodcastRepository(Context c,Transport transport,ExecutorService work,LongSupplier clock){
        context=c.getApplicationContext();this.transport=transport;this.work=work;this.clock=clock;
        cache=new AtomicFile(new File(c.getFilesDir(),"podcast-library.json"));showFile=new AtomicFile(new File(c.getFilesDir(),"podcast-shows.json"));
        try(InputStream in=showFile.openRead()){
            JSONArray entries=new JSONObject(new String(FeedRepository.readLimited(in,1024000),StandardCharsets.UTF_8)).getJSONArray("shows");
            for(int n=0;n<entries.length()&&n<400;n++){PodcastShow show=PodcastShow.parse(entries.getJSONObject(n));if(validShow(show))shows.put(show.showId,show);}
        }catch(Exception ignored){}
        try(InputStream in=c.getAssets().open("podcast-seed.json")){
            JSONArray entries=new JSONArray(new String(FeedRepository.readLimited(in,48000),StandardCharsets.UTF_8));
            for(int n=0;n<entries.length();n++){PodcastShow show=descriptor(entries.getJSONObject(n),false);if(show!=null){seeds.add(show.showId);if(!shows.containsKey(show.showId))shows.put(show.showId,show);}}
        }catch(Exception ignored){}
        try(InputStream in=cache.openRead()){
            JSONObject saved=new JSONObject(new String(FeedRepository.readLimited(in,1024000),StandardCharsets.UTF_8));fetched=saved.optLong("at");defaultsFetched=saved.optLong("defaultsAt");
            JSONObject times=saved.optJSONObject("showFetched");if(times!=null){Iterator<String> keys=times.keys();while(keys.hasNext()){String id=keys.next();episodeFetched.put(id,times.optLong(id));}}
            JSONArray entries=saved.getJSONArray("items");
            for(int n=0;n<entries.length()&&n<100;n++){
                MediaItem item=MediaItem.parse(entries.getJSONObject(n));if(item.id.isEmpty()||!item.provider.equals("podcast")||!safe(item.audio,false))continue;
                if(item.showId.isEmpty())for(PodcastShow show:shows.values())if(show.title.equals(item.show)){item.showId=show.showId;break;}
                library.put(item.id,item);
            }
        }catch(Exception ignored){}
    }
    synchronized MediaItem find(String id){return library.get(id);}
    synchronized List<MediaItem> items(){return new ArrayList<>(library.values());}
    synchronized PodcastShow show(String id){return shows.get(id);}
    synchronized String lastError(){return lastError;}
    synchronized List<PodcastShow> followedShows(){List<PodcastShow> result=new ArrayList<>();for(PodcastShow show:shows.values())if(show.followed)result.add(show);result.sort(Comparator.comparingLong((PodcastShow s)->s.followedAt).reversed());return result;}
    synchronized List<PodcastShow> recommendations(){List<PodcastShow> result=new ArrayList<>();for(String id:seeds){PodcastShow show=shows.get(id);if(show!=null)result.add(show);}return result;}
    synchronized boolean follow(String id){return changeFollow(id,true);}
    synchronized boolean unfollow(String id){return changeFollow(id,false);}
    private boolean changeFollow(String id,boolean followed){
        PodcastShow show=shows.get(id);if(show==null){lastError="not_found";return false;}
        if(show.followed==followed){lastError="";return true;}
        if(followed&&followedShows().size()>=200){lastError="library_full";return false;}
        LinkedHashMap<String,PodcastShow> changed=new LinkedHashMap<>(shows);changed.put(id,show.followed(followed,clock.getAsLong()));
        if(!saveShows(changed)){lastError="storage_failed";return false;}
        shows.clear();shows.putAll(changed);defaultsFetched=0;saveEpisodes();lastError="";return true;
    }
    void searchShows(String query,Consumer<List<PodcastShow>> done){searchShowsResult(query,result->done.accept(result.items));}
    void searchShowsResult(String query,Consumer<Result<PodcastShow>> done){
        String term=query==null?"":query.trim();
        if(term.isEmpty()){post(done,new Result<>(recommendations(),"",false));return;}
        if(term.length()>100||term.matches("(?s).*[\\x00-\\x1f].*")){post(done,new Result<>(Collections.emptyList(),"invalid_query",false));return;}
        work.execute(()->{try{List<PodcastShow> found=directory(term);boolean saved=mergeShows(found);post(done,new Result<>(currentShows(found),saved?"":"storage_failed",false));}
            catch(Exception error){post(done,new Result<>(Collections.emptyList(),failure(error,"directory_unavailable"),false));}});
    }
    void episodes(String showId,Consumer<List<MediaItem>> done){episodesResult(showId,result->done.accept(result.items));}
    void episodesResult(String showId,Consumer<Result<MediaItem>> done){
        PodcastShow show;List<MediaItem> cached;long at;
        synchronized(this){show=shows.get(showId);cached=episodesFor(showId);at=episodeFetched.getOrDefault(showId,0L);}
        if(show==null){post(done,new Result<>(Collections.emptyList(),"not_found",false));return;}
        if(!cached.isEmpty()&&at>0&&clock.getAsLong()>=at&&clock.getAsLong()-at<FeedRepository.TTL){post(done,new Result<>(cached,"",false));return;}
        work.execute(()->{
            try{List<MediaItem> found=fetchEpisodes(show,20);if(found.isEmpty()){post(done,new Result<>(cached,"no_playable_episodes",!cached.isEmpty()));return;}
                boolean saved=store(found,showId);post(done,new Result<>(found,saved?"":"storage_failed",false));}
            catch(Exception error){post(done,new Result<>(cached,failure(error,"feed_unavailable"),!cached.isEmpty()));}
        });
    }
    /** Old episode-search API remains available; show discovery/follow is a separate operation. */
    void search(String query,Consumer<List<MediaItem>> done){if(query==null||query.trim().isEmpty()){defaults(done);return;}searchEpisodesResult(query,result->done.accept(result.items));}
    void searchEpisodesResult(String query,Consumer<Result<MediaItem>> done){
        String term=query==null?"":query.trim();if(term.isEmpty()||term.length()>100){post(done,new Result<>(Collections.emptyList(),"invalid_query",false));return;}
        work.execute(()->{
            List<MediaItem> found=new ArrayList<>();String errorCode="";
            try{List<PodcastShow> matches=directory(term);if(!mergeShows(matches))errorCode="storage_failed";
                for(int n=0;n<matches.size()&&n<6;n++)try{found.addAll(fetchEpisodes(matches.get(n),4));}catch(Exception error){errorCode=failure(error,"feed_unavailable");}
                if(!found.isEmpty()){if(!store(found,null))errorCode="storage_failed";}
                else if(!matches.isEmpty()&&errorCode.isEmpty())errorCode="no_playable_episodes";
            }catch(Exception error){errorCode=failure(error,"directory_unavailable");}
            post(done,new Result<>(found,errorCode,false));
        });
    }
    void defaults(Consumer<List<MediaItem>> done){
        synchronized(this){if(!library.isEmpty()&&defaultsFetched>0&&clock.getAsLong()>=defaultsFetched&&clock.getAsLong()-defaultsFetched<FeedRepository.TTL){done.accept(defaultItems());return;}pending.add(done);if(loading)return;loading=true;}
        work.execute(()->{
            List<PodcastShow> selected=followedShows();if(selected.isEmpty())selected=recommendations();List<MediaItem> found=new ArrayList<>();
            for(PodcastShow show:selected)try{found.addAll(fetchEpisodes(show,4));}catch(Exception ignored){}
            List<Consumer<List<MediaItem>>> callbacks;
            synchronized(this){if(!found.isEmpty())defaultsFetched=clock.getAsLong();store(found,null);loading=false;callbacks=new ArrayList<>(pending);pending.clear();}
            ui.post(()->{List<MediaItem> list=defaultItems();for(Consumer<List<MediaItem>> callback:callbacks)callback.accept(list);});
        });
    }
    private synchronized List<MediaItem> defaultItems(){
        List<PodcastShow> followed=followedShows();if(followed.isEmpty())return items();Set<String> wanted=new HashSet<>();for(PodcastShow show:followed)wanted.add(show.showId);
        List<MediaItem> result=new ArrayList<>();for(MediaItem item:library.values())if(wanted.contains(item.showId))result.add(item);return result;
    }
    private synchronized List<MediaItem> episodesFor(String id){List<MediaItem> result=new ArrayList<>();for(MediaItem item:library.values())if(id.equals(item.showId))result.add(item);return result;}
    private <T> void post(Consumer<Result<T>> done,Result<T> result){ui.post(()->done.accept(result));}
    private List<PodcastShow> directory(String query)throws Exception{
        String url="https://itunes.apple.com/search?country=tw&media=podcast&entity=podcast&limit=20&term="+URLEncoder.encode(query,"UTF-8");
        JSONArray entries=new JSONObject(new String(transport.read(url,600000,false),StandardCharsets.UTF_8)).getJSONArray("results");List<PodcastShow> result=new ArrayList<>();Set<String> seen=new HashSet<>();
        for(int n=0;n<entries.length()&&result.size()<20;n++){PodcastShow show=descriptor(entries.getJSONObject(n),true);if(show!=null&&seen.add(show.showId))result.add(show);}return result;
    }
    private synchronized List<PodcastShow> currentShows(List<PodcastShow> found){List<PodcastShow> result=new ArrayList<>();for(PodcastShow show:found)result.add(shows.getOrDefault(show.showId,show));return result;}
    private synchronized boolean mergeShows(List<PodcastShow> found){
        for(PodcastShow incoming:found){PodcastShow old=shows.get(incoming.showId);shows.put(incoming.showId,old!=null&&old.followed?incoming.followed(true,old.followedAt):incoming);}
        while(shows.size()>400){String removable=null;for(PodcastShow show:shows.values())if(!show.followed&&!seeds.contains(show.showId)){removable=show.showId;break;}if(removable==null)break;shows.remove(removable);}
        return saveShows(shows);
    }
    private boolean saveShows(Map<String,PodcastShow> values){
        JSONArray entries=new JSONArray();for(PodcastShow show:values.values())entries.put(show.json());try{return atomicWrite(showFile,new JSONObject().put("version",1).put("shows",entries).toString());}catch(Exception ignored){return false;}
    }
    private synchronized boolean store(List<MediaItem> found,String showId){
        if(showId!=null)library.values().removeIf(item->showId.equals(item.showId));
        for(MediaItem item:found){library.remove(item.id);library.put(item.id,item);}while(library.size()>100)library.remove(library.keySet().iterator().next());
        if(!found.isEmpty())fetched=clock.getAsLong();if(showId!=null)episodeFetched.put(showId,clock.getAsLong());return saveEpisodes();
    }
    private synchronized boolean saveEpisodes(){
        JSONArray entries=new JSONArray();for(MediaItem item:library.values())entries.put(item.json());JSONObject times=new JSONObject();try{for(Map.Entry<String,Long> entry:episodeFetched.entrySet())times.put(entry.getKey(),entry.getValue());return atomicWrite(cache,new JSONObject().put("at",fetched).put("defaultsAt",defaultsFetched).put("showFetched",times).put("items",entries).toString());}catch(Exception ignored){return false;}
    }
    private static boolean atomicWrite(AtomicFile file,String json){
        FileOutputStream out=null;try{byte[] expected=json.getBytes(StandardCharsets.UTF_8);out=file.startWrite();out.write(expected);file.finishWrite(out);out=null;try(InputStream in=file.openRead()){return Arrays.equals(expected,FeedRepository.readLimited(in,expected.length+1));}}catch(Exception ignored){if(out!=null)file.failWrite(out);return false;}
    }
    private static PodcastShow descriptor(JSONObject value,boolean directory)throws Exception{
        long directoryId=directory?value.optLong("collectionId"):value.optLong("directoryId",Math.abs(value.optLong("id")));
        String feed=https(value.optString(directory?"feedUrl":"feed"));if(!safe(feed,false))feed="";
        String title=trim(value.optString(directory?"collectionName":"title"),240);if(title.isEmpty()||(directoryId<=0&&feed.isEmpty()))return null;
        String id=directoryId>0?"show:apple:"+directoryId:"show:rss:"+hash(feed);
        String image=https(value.optString(directory?"artworkUrl600":"poster",value.optString("artworkUrl100")));if(!safe(image,false))image="";
        String source=https(value.optString(directory?"collectionViewUrl":"source"));if(!safe(source,false))source="";
        return new PodcastShow(id,title,trim(value.optString("artistName",value.optString("author")),160),feed,image,source,directoryId,false,0);
    }
    private static boolean validShow(PodcastShow show){
        if(show.title.isEmpty()||show.title.length()>240||show.showId.length()>100||(!show.feed.isEmpty()&&!safe(show.feed,false)))return false;
        try{return show.showId.equals(show.directoryId>0?"show:apple:"+show.directoryId:"show:rss:"+hash(show.feed))&&(show.directoryId>0||!show.feed.isEmpty());}catch(Exception ignored){return false;}
    }
    private List<MediaItem> fetchEpisodes(PodcastShow show,int limit)throws Exception{
        Exception feedError=null;boolean feedRead=false;
        if(!show.feed.isEmpty())try{List<MediaItem> rss=rssEpisodes(show,limit);feedRead=true;if(!rss.isEmpty())return rss;}catch(Exception error){feedError=error;}
        if(show.directoryId>0)try{
            JSONArray entries=new JSONObject(new String(transport.read("https://itunes.apple.com/lookup?id="+show.directoryId+"&entity=podcastEpisode&limit="+limit+"&country=tw",600000,false),StandardCharsets.UTF_8)).getJSONArray("results");List<MediaItem> found=new ArrayList<>();
            for(int n=0;n<entries.length()&&found.size()<limit;n++){JSONObject entry=entries.getJSONObject(n);if(!entry.optString("wrapperType").equals("podcastEpisode"))continue;String audio=https(entry.optString("episodeUrl"));if(!safe(audio,false))continue;MediaItem item=new MediaItem();item.audio=audio;item.title=trim(entry.optString("trackName"),240);item.show=show.title;item.showId=show.showId;item.date=entry.optString("releaseDate");item.image=https(entry.optString("artworkUrl600",show.image));item.source=entry.optString("trackViewUrl",show.source);item.id="pod:"+hash(show.feed+"|"+entry.optString("episodeGuid",audio));if(!item.title.isEmpty())found.add(item);}
            if(!found.isEmpty())return found;
        }catch(Exception error){if(feedError==null&&!feedRead)feedError=error;}
        if(feedError!=null)throw feedError;return Collections.emptyList();
    }
    private List<MediaItem> rssEpisodes(PodcastShow show,int limit)throws Exception{
        byte[] data=transport.read(show.feed,2*1024*1024,true);XmlPullParser parser=Xml.newPullParser();parser.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES,true);parser.setInput(new ByteArrayInputStream(data),null);
        List<MediaItem> found=new ArrayList<>();MediaItem current=null;String tag="",guid="";int event;
        while((event=parser.nextToken())!=XmlPullParser.END_DOCUMENT){
            if(event==XmlPullParser.DOCDECL)throw new IOException("DTD is not supported");
            if(event==XmlPullParser.START_TAG){String name=parser.getName();tag=name;if(name.equals("title")&&!parser.getNamespace().isEmpty())tag="";
                if(name.equals("item")){current=new MediaItem();current.show=show.title;current.showId=show.showId;current.image=show.image;current.source=show.source;guid="";}
                else if(current!=null&&name.equals("enclosure")){String raw=parser.getAttributeValue(null,"url");if(raw!=null)current.audio=https(raw);}
            }else if((event==XmlPullParser.TEXT||event==XmlPullParser.CDSECT||event==XmlPullParser.ENTITY_REF)&&current!=null){String value=parser.getText();if(value!=null){if(tag.equals("title"))current.title=append(current.title,value,512);if(tag.equals("pubDate"))current.date=append(current.date,value,160);if(tag.equals("guid"))guid=append(guid,value,4096);}}
            else if(event==XmlPullParser.END_TAG){if(parser.getName().equals("item")&&current!=null){current.title=trim(current.title,240);current.date=current.date.trim();guid=guid.trim();if(!current.title.isEmpty()&&safe(current.audio,false)){current.id="pod:"+hash(show.feed+"|"+(guid.isEmpty()?current.audio:guid));found.add(current);if(found.size()>=limit)break;}current=null;}tag="";}
        }return found;
    }
    static boolean safe(String value,boolean resolve){try{URI u=new URI(value);if(!"https".equalsIgnoreCase(u.getScheme())||u.getHost()==null||u.getUserInfo()!=null||(u.getPort()!=-1&&u.getPort()!=443))return false;String h=u.getHost().toLowerCase(Locale.ROOT);if(h.equals("localhost")||h.endsWith(".local")||h.matches("[0-9.]+")||h.contains(":")||!h.contains("."))return false;if(resolve)for(InetAddress a:InetAddress.getAllByName(h))if(a.isAnyLocalAddress()||a.isLoopbackAddress()||a.isSiteLocalAddress()||a.isLinkLocalAddress()||a.isMulticastAddress())return false;return true;}catch(Exception e){return false;}}
    private static byte[] read(String url,int max,boolean prefix)throws Exception{
        for(int n=0;n<5;n++){if(!safe(url,true))throw new IOException("Public HTTPS source required");HttpURLConnection connection=(HttpURLConnection)new URL(url).openConnection();connection.setConnectTimeout(7000);connection.setReadTimeout(8000);connection.setInstanceFollowRedirects(false);connection.setRequestProperty("User-Agent","MeadowTV personal podcast player");
            try{int status=connection.getResponseCode();if(status>=300&&status<400){url=new URL(new URL(url),connection.getHeaderField("Location")).toString();continue;}if(status!=200)throw new IOException("Feed unavailable");try(InputStream in=connection.getInputStream()){if(!prefix)return FeedRepository.readLimited(in,max);ByteArrayOutputStream out=new ByteArrayOutputStream();byte[] buffer=new byte[8192];while(out.size()<max){int count=in.read(buffer,0,Math.min(buffer.length,max-out.size()));if(count<0)break;out.write(buffer,0,count);}return out.toByteArray();}}finally{connection.disconnect();}}
        throw new IOException("Redirect limit");
    }
    private static String failure(Exception error,String fallback){return error instanceof SocketTimeoutException?"timeout":fallback;}
    private static String https(String url){return url.startsWith("http://")?"https://"+url.substring(7):url;}
    private static String trim(String value,int max){String result=value.trim();return result.length()>max?result.substring(0,max):result;}
    private static String append(String value,String more,int max){return value.length()>=max?value:value+more.substring(0,Math.min(more.length(),max-value.length()));}
    private static String hash(String value)throws Exception{byte[] bytes=MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));StringBuilder result=new StringBuilder();for(int n=0;n<12;n++)result.append(String.format(Locale.ROOT,"%02x",bytes[n]));return result.toString();}
}
