package com.akai.livingtv;
import android.content.Context;
import android.os.*;
import org.json.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;

/** The TV owns ordered tasks and playback receipts; model text cannot supply media URLs. */
final class MediaQueue {
    private static MediaQueue instance;
    static synchronized MediaQueue get(Context c){if(instance==null)instance=new MediaQueue(c.getApplicationContext());return instance;}
    final Context context;
    private final File file;
    private final ExecutorService disk=Executors.newSingleThreadExecutor();
    private final Handler ui=new Handler(Looper.getMainLooper());
    private final List<MediaItem> queue=new ArrayList<>();
    private final Map<String,MediaItem> videos=new HashMap<>();
    private final Set<Runnable> listeners=new HashSet<>();
    int index=-1;String state="stopped",error="";long stoppedAt;
    private MediaQueue(Context c){context=c;file=new File(c.getFilesDir(),"media-queue.json");try(InputStream in=new FileInputStream(file)){JSONObject o=new JSONObject(new String(FeedRepository.readLimited(in,512000),StandardCharsets.UTF_8));JSONArray a=o.optJSONArray("items");if(a!=null)for(int n=0;n<a.length()&&n<50;n++){MediaItem i=MediaItem.parse(a.getJSONObject(n));if(i.provider.equals("podcast")&&PodcastRepository.safe(i.audio,false)||Arrays.asList("netflix","hbo","apple").contains(i.provider))queue.add(i);}index=Math.min(queue.size()-1,o.optInt("index",-1));}catch(Exception ignored){}}
    synchronized void register(FeedRepository.Entry e){if(Arrays.asList("netflix","hbo","apple").contains(e.provider)){MediaItem i=MediaItem.video(e);videos.put(i.id,i);}}
    synchronized MediaItem find(String id){for(MediaItem i:queue)if(i.id.equals(id))return i;MediaItem v=videos.get(id);return v==null?PodcastRepository.get(context).find(id):v;}
    synchronized List<MediaItem> items(){return new ArrayList<>(queue);}
    synchronized List<MediaItem> availableVideos(){return new ArrayList<>(videos.values());}
    synchronized MediaItem current(){return index>=0&&index<queue.size()?queue.get(index):null;}
    synchronized int add(MediaItem item){for(int n=0;n<queue.size();n++)if(queue.get(n).id.equals(item.id))return n;if(queue.size()>=50)return -1;queue.add(item);save();publish();return queue.size()-1;}
    synchronized boolean select(int at){if(at<0||at>=queue.size())return false;index=at;save();publish();return true;}
    synchronized boolean move(int from,int to){if(from<0||from>=queue.size()||to<0||to>=queue.size())return false;MediaItem current=current();MediaItem i=queue.remove(from);queue.add(to,i);if(current!=null)index=queue.indexOf(current);save();publish();return true;}
    synchronized boolean remove(int at){if(at<0||at>=queue.size())return false;boolean active=at==index;queue.remove(at);if(at<index)index--;else if(index>=queue.size())index=queue.size()-1;if(active){state="stopped";MediaQueueService.stop(context);}save();publish();return true;}
    synchronized void clear(){MediaQueueService.stop(context);queue.clear();index=-1;state="stopped";save();publish();}
    synchronized void update(String next,String message){if((next.equals("stopped")||next.equals("finished")||next.equals("error"))&&(state.equals("playing")||state.equals("preparing")||state.equals("paused")))stoppedAt=SystemClock.uptimeMillis();if(next.equals("playing")||next.equals("preparing"))stoppedAt=0;state=next;error=message;publish();}
    synchronized JSONObject snapshot(){JSONObject o=new JSONObject();JSONArray a=new JSONArray(),library=new JSONArray();for(MediaItem i:queue)a.put(i.metadata());for(MediaItem i:PodcastRepository.get(context).items()){if(library.length()>=16)break;library.put(i.metadata());}for(MediaItem i:videos.values()){if(library.length()>=32)break;library.put(i.metadata());}try{o.put("items",a).put("index",index).put("state",state).put("error",error).put("available",library).put("externalAppAutoAdvance",false);}catch(Exception ignored){}return o;}
    synchronized void listen(Runnable r){listeners.add(r);}
    synchronized void unlisten(Runnable r){listeners.remove(r);}
    private synchronized void publish(){for(Runnable r:new ArrayList<>(listeners))ui.post(r);}
    private synchronized void save(){JSONArray a=new JSONArray();for(MediaItem i:queue)a.put(i.json());JSONObject o=new JSONObject();try{o.put("items",a).put("index",index);}catch(Exception ignored){}String data=o.toString();disk.execute(()->{try(FileOutputStream out=new FileOutputStream(file)){out.write(data.getBytes(StandardCharsets.UTF_8));}catch(Exception ignored){}});}
}
