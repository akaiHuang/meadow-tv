package com.akai.livingtv;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.Handler;
import android.os.Looper;
import android.util.LruCache;
import android.util.Log;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.*;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.OffsetDateTime;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

/** Foreground-triggered metadata refresh; no timer, wake lock or background job. */
final class FeedRepository {
    static final long TTL = 6 * 60 * 60 * 1000L;
    static final class Entry {
        int id;
        String title, provider, date, poster, backdrop, official, summary, source;boolean wide;
        String region="",locale="",availability="",collection="",availabilityStarts="",availabilityEnds="";long verifiedAt;
        JSONObject json() throws Exception {
            return new JSONObject().put("id", id).put("title", title).put("provider", provider).put("date", date).put("poster", poster).put("backdrop", backdrop).put("official", official).put("summary", summary).put("source", source).put("wide",wide).put("region",region).put("locale",locale).put("availability",availability).put("collection",collection).put("verifiedAt",verifiedAt).put("availabilityStarts",availabilityStarts).put("availabilityEnds",availabilityEnds);
        }
        static Entry parse(JSONObject o) {
            Entry e = new Entry();
            e.wide=o.optBoolean("wide");e.id=o.optInt("id"); e.title=o.optString("title"); e.provider=o.optString("provider");
            e.date=o.optString("date"); e.poster=o.optString("poster"); e.backdrop=o.optString("backdrop");
            e.official=o.optString("official"); e.summary=o.optString("summary"); e.source=o.optString("source");
            e.region=o.optString("region");e.locale=o.optString("locale");e.availability=o.optString("availability");e.collection=o.optString("collection");e.verifiedAt=o.optLong("verifiedAt");e.availabilityStarts=o.optString("availabilityStarts");e.availabilityEnds=o.optString("availabilityEnds");
            return e;
        }
    }
    private final Context context;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService metadata = Executors.newSingleThreadExecutor(r -> worker(r,"living-feed"));
    // Keep at most two decodes active on the TV's small CPU. The selected hero
    // goes ahead of offscreen-card work that has not started yet.
    private final ThreadPoolExecutor images = new ThreadPoolExecutor(2,2,0,TimeUnit.MILLISECONDS,
            new PriorityBlockingQueue<>(),r -> worker(r,"living-art"));
    private final AtomicLong imageSequence = new AtomicLong();
    private static final int HERO_PRIORITY=0, FULL_PRIORITY=1, THUMBNAIL_PRIORITY=2;
    private final File feedFile, imageDir;
    private final LruCache<String, Bitmap> bitmaps = new LruCache<String, Bitmap>(8 * 1024 * 1024) {
        @Override protected int sizeOf(String key, Bitmap value) { return value.getAllocationByteCount(); }
    };
    private final Map<String,List<Consumer<Bitmap>>> waiting = new HashMap<>();
    private volatile boolean closed;
    private static final class ImageTask implements Runnable,Comparable<ImageTask> {
        final int priority; final long sequence; final Runnable work;
        ImageTask(int p,long s,Runnable r){priority=p;sequence=s;work=r;}
        @Override public int compareTo(ImageTask other){int byPriority=Integer.compare(priority,other.priority);return byPriority!=0?byPriority:Long.compare(sequence,other.sequence);}
        @Override public void run(){work.run();}
    }
    private List<Entry> entries = new ArrayList<>();
    FeedRepository(Context c) {
        context=c.getApplicationContext();
        feedFile=new File(context.getFilesDir(),"feed.json");
        imageDir=new File(context.getCacheDir(),"art"); imageDir.mkdirs();
    }
    private static Thread worker(Runnable r,String name) {
        return new Thread(() -> { android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_BACKGROUND); r.run(); },name);
    }
    void load(Consumer<List<Entry>> callback) {
        load(callback,false);
    }
    void refresh(Consumer<List<Entry>> callback) {
        load(callback,true);
    }
    private void load(Consumer<List<Entry>> callback,boolean force) {
        if(closed)return;
        try{metadata.execute(() -> {
            JSONObject cached=readCache();
            List<Entry> bundled=readBundled();
            List<Entry> previous=cached==null?Collections.emptyList():decode(cached.optJSONArray("items"));
            entries=mergeTaiwanEntries(bundled,previous,System.currentTimeMillis());
            if(!entries.isEmpty())deliver(callback,withPodcasts(entries));
            if(!force&&cached!=null&&cached.optInt("schemaVersion")>=2&&System.currentTimeMillis()>=cached.optLong("fetchedAt")&&System.currentTimeMillis()-cached.optLong("fetchedAt")<TTL&&complete(previous))return;
            try {
                List<Entry> fresh=refresh();
                if(!fresh.isEmpty()&&!closed){
                    List<Entry> merged=mergeTaiwanEntries(entries,fresh,System.currentTimeMillis());
                    JSONArray items=new JSONArray();for(Entry entry:merged)items.put(entry.json());
                    JSONObject data=new JSONObject().put("schemaVersion",2).put("region","TW").put("locale","zh-TW").put("fetchedAt",System.currentTimeMillis()).put("items",items);
                    File temporary=new File(feedFile.getPath()+".tmp");
                    try(FileOutputStream out=new FileOutputStream(temporary)){out.write(data.toString().getBytes(StandardCharsets.UTF_8));}
                    if(closed){temporary.delete();return;}
                    if(!temporary.renameTo(feedFile))temporary.delete();
                    entries=merged;deliver(callback,withPodcasts(merged));
                }
            }catch(Exception error){Log.i("LivingFeed","Keeping verified Taiwan metadata: "+error.getClass().getSimpleName());}
        });}catch(RejectedExecutionException ignored){/* closed while the load was being submitted */}
    }
    private <T> void deliver(Consumer<T> callback,T value) { synchronized(waiting){if(!closed)main.post(() -> { if(!closed) callback.accept(value); });} }
    private List<Entry> withPodcasts(List<Entry> original){
        List<Entry> all=new ArrayList<>(original);
        try(InputStream in=context.getAssets().open("podcast-seed.json")){JSONArray list=new JSONArray(new String(readLimited(in,32000),StandardCharsets.UTF_8));for(int index=0;index<list.length();index++)all.add(Entry.parse(list.getJSONObject(index)));}catch(Exception ignored){}
        return all;
    }
    private JSONObject readCache(){
        try(InputStream in=feedFile.exists()?new FileInputStream(feedFile):context.getAssets().open("feed-seed.json")){return new JSONObject(new String(readLimited(in,2*1024*1024),StandardCharsets.UTF_8));}
        catch(Exception ignored){return null;}
    }
    private List<Entry> readBundled(){
        try(InputStream in=context.getAssets().open("official-catalog.json")){return decode(new JSONObject(new String(readLimited(in,2*1024*1024),StandardCharsets.UTF_8)).optJSONArray("items"));}
        catch(Exception ignored){return Collections.emptyList();}
    }
    private List<Entry> decode(JSONArray array){return decodeTaiwan(array,System.currentTimeMillis());}
    static List<Entry> decodeTaiwan(JSONArray array,long now){
        List<Entry> result=new ArrayList<>();if(array!=null)for(int index=0;index<array.length()&&index<96;index++){JSONObject item=array.optJSONObject(index);if(item!=null){Entry entry=Entry.parse(item);if(verifiedTaiwan(entry,now))result.add(entry);}}return result;
    }
    static boolean verifiedTaiwan(Entry entry,long now){
        if(!"TW".equals(entry.region)||!"zh-TW".equals(entry.locale)||entry.verifiedAt<=0||entry.verifiedAt>now+300000||entry.title==null||entry.title.isEmpty()||!ContentPolicy.artworkLink(entry.poster)||!ContentPolicy.artworkLink(entry.backdrop)||!ContentPolicy.officialLink(entry.provider,entry.official))return false;
        try{
            URI source=new URI(entry.source),official=new URI(entry.official);if(!"https".equals(source.getScheme())||source.getRawUserInfo()!=null||source.getPort()!=-1)return false;
            String path=official.getPath(),sourcePath=source.getPath();
            if("netflix".equals(entry.provider))return "listed_tw".equals(entry.availability)&&"www.netflix.com".equals(source.getHost())&&sourcePath.startsWith("/tw/")&&path.matches("/tw/title/[0-9]+/?");
            if("apple".equals(entry.provider))return "listed_tw".equals(entry.availability)&&"tv.apple.com".equals(source.getHost())&&(sourcePath.equals("/tw")||sourcePath.startsWith("/tw/"))&&path.matches("/tw/(show|movie)/.+");
            if("hbo".equals(entry.provider))return "offering_active_tw".equals(entry.availability)&&"www.hbomax.com".equals(source.getHost())&&(sourcePath.equals("/tw/zh")||sourcePath.startsWith("/tw/zh/"))&&path.matches("/tw/zh/(show|movie)/[A-Za-z0-9-]+/?")&&OffsetDateTime.parse(entry.availabilityStarts).toInstant().toEpochMilli()<=now&&now<OffsetDateTime.parse(entry.availabilityEnds).toInstant().toEpochMilli();
        }catch(Exception ignored){}
        return false;
    }
    static List<Entry> mergeTaiwanEntries(List<Entry> baseline,List<Entry> incoming,long now){
        List<Entry> result=new ArrayList<>();
        for(String provider:new String[]{"netflix","hbo","apple"}){
            List<Entry> old=new ArrayList<>(),fresh=new ArrayList<>();long oldAt=0,freshAt=0;
            for(Entry entry:baseline)if(provider.equals(entry.provider)&&verifiedTaiwan(entry,now)){old.add(entry);oldAt=Math.max(oldAt,entry.verifiedAt);}
            for(Entry entry:incoming)if(provider.equals(entry.provider)&&verifiedTaiwan(entry,now)){fresh.add(entry);freshAt=Math.max(freshAt,entry.verifiedAt);}
            List<Entry> candidates=new ArrayList<>();
            if(freshAt>=oldAt){candidates.addAll(fresh);if(fresh.size()<16)candidates.addAll(old);}else{candidates.addAll(old);if(old.size()<16)candidates.addAll(fresh);}
            Set<String> seen=new HashSet<>();int count=0;for(Entry entry:candidates)if(seen.add(entry.official)){result.add(entry);if(++count>=24)break;}
        }
        return result;
    }
    private static boolean complete(List<Entry> entries){for(String provider:new String[]{"netflix","hbo","apple"}){int count=0;for(Entry entry:entries)if(provider.equals(entry.provider))count++;if(count<16)return false;}return true;}
    private List<Entry> refresh(){
        try{AssistantClient bridge=new AssistantClient(context);try{if(bridge.configured())return decode(bridge.request("/catalog",null).optJSONArray("items"));}finally{bridge.close();}}catch(Exception ignored){}
        // Official pages are parsed by the existing bridge updater, never by the TV GPU/UI thread.
        // An unavailable bridge keeps the verified bundled/cache selection; US schedules are not a TW catalog.
        return Collections.emptyList();
    }
    static byte[] readLimited(InputStream in,int max) throws IOException {
        ByteArrayOutputStream out=new ByteArrayOutputStream(); byte[] buf=new byte[8192]; int n;
        while((n=in.read(buf))!=-1){if(out.size()+n>max)throw new IOException("Response too large");out.write(buf,0,n);}
        return out.toByteArray();
    }
    private byte[] download(String url,int max) throws IOException {
        HttpURLConnection c=(HttpURLConnection)new URL(url).openConnection();
        c.setConnectTimeout(6000);c.setReadTimeout(7000);c.setInstanceFollowRedirects(false);
        c.setRequestProperty("User-Agent","LivingTV/0.1 personal Android-TV launcher");
        try {
            if(c.getResponseCode()!=200)throw new IOException("HTTP "+c.getResponseCode());
            try(InputStream in=c.getInputStream()){return readLimited(in,max);}
        } finally {c.disconnect();}
    }
    void image(String url,Consumer<Bitmap> callback) { requestImage(url,"full:",FULL_PRIORITY,()->decodeArtwork(url,1280,900),callback); }
    void thumbnail(String url,Consumer<Bitmap> callback) { requestImage(url,"thumb:",THUMBNAIL_PRIORITY,()->decodeArtwork(url,640,540),callback); }
    private void requestImage(String url,String variant,int priority,Callable<Bitmap> decode,Consumer<Bitmap> callback) {
        if(closed)return;
        if(!ContentPolicy.artworkLink(url)){callback.accept(null);return;}
        final String key=variant+url;
        synchronized(waiting) {
            if(closed)return;
            Bitmap cached=bitmaps.get(key);
            if(cached!=null){callback.accept(cached);return;}
            if(waiting.containsKey(key)){waiting.get(key).add(callback);return;}
            if(waiting.size()>=64){callback.accept(null);return;}
            List<Consumer<Bitmap>> consumers=new ArrayList<>();consumers.add(callback);waiting.put(key,consumers);
            images.execute(new ImageTask(priority,imageSequence.getAndIncrement(),()->{
                if(closed)return;
                Bitmap bitmap=null;
                try{bitmap=decode.call();}
                catch(Exception e){Log.i("LivingFeed","Artwork unavailable: "+e.getClass().getSimpleName());}
                synchronized(waiting){
                    if(closed)return;
                    if(bitmap!=null)bitmaps.put(key,bitmap);
                    List<Consumer<Bitmap>> callbacks=waiting.remove(key);
                    final Bitmap ready=bitmap;
                    if(callbacks!=null)main.post(()->{for(Consumer<Bitmap> cb:callbacks){if(closed)break;cb.accept(ready);}});
                }
            }));
        }
    }
    private Bitmap decodeArtwork(String url,int maxWidth,int maxHeight) throws Exception {
        if(closed)return null;
        File file=new File(imageDir,hash(url));
        byte[] data;
        if(file.exists()){try(InputStream in=new FileInputStream(file)){data=readLimited(in,4*1024*1024);}file.setLastModified(System.currentTimeMillis());}
        else {
            try(InputStream seed=context.getAssets().open("art/"+hash(url))){data=readLimited(seed,4*1024*1024);}
            catch(IOException missing){data=download(url,4*1024*1024);}
            if(closed)return null;
            File temporary=File.createTempFile("art-",".tmp",imageDir);
            try{try(FileOutputStream out=new FileOutputStream(temporary)){out.write(data);}if(!temporary.renameTo(file))temporary.delete();}finally{temporary.delete();}
            trimDisk();
        }
        if(closed)return null;
        BitmapFactory.Options options=new BitmapFactory.Options();options.inJustDecodeBounds=true;
        BitmapFactory.decodeByteArray(data,0,data.length,options);
        options.inSampleSize=1;
        while(options.outWidth/options.inSampleSize>maxWidth||options.outHeight/options.inSampleSize>maxHeight)options.inSampleSize*=2;
        options.inJustDecodeBounds=false;options.inPreferredConfig=Bitmap.Config.RGB_565;
        return BitmapFactory.decodeByteArray(data,0,data.length,options);
    }
    void background(String url,Consumer<Bitmap> callback){
        requestImage(url,"graded:",HERO_PRIORITY,()->{
            // The hero only needs its final graded pixels. Retaining both the
            // source and graded copy would evict several visible card covers.
            Bitmap original=decodeArtwork(url,1280,900);if(original==null)return null;
            try{
                if(closed)return null;
                Bitmap result=Bitmap.createBitmap(960,540,Bitmap.Config.RGB_565);android.graphics.Canvas c=new android.graphics.Canvas(result);android.graphics.Paint p=new android.graphics.Paint(android.graphics.Paint.FILTER_BITMAP_FLAG);c.drawColor(0xFF0B1211);
                float factor=Math.max(960f/original.getWidth(),540f/original.getHeight());float w=original.getWidth()*factor,h=original.getHeight()*factor;c.drawBitmap(original,null,new android.graphics.RectF((960-w)/2,(540-h)/2,(960+w)/2,(540+h)/2),p);p.setAlpha(255);
                // Keep the subject visible; reserve stronger shade for text and the rail.
                // Both masks are still baked once into the bounded 960x540 bitmap.
                p.setShader(new android.graphics.LinearGradient(0,0,960,0,new int[]{0xA00B1211,0x2B0B1211,0x3D0B1211},new float[]{0,.6f,1},android.graphics.Shader.TileMode.CLAMP));c.drawRect(0,0,960,540,p);
                p.setShader(new android.graphics.LinearGradient(0,0,0,540,new int[]{0x400B1211,0x100B1211,0xD90B1211},new float[]{0,.5f,1},android.graphics.Shader.TileMode.CLAMP));c.drawRect(0,0,960,540,p);return result;
            }finally{
                // This decode belongs only to this software grade; it is never
                // placed in the shared cache or handed to an ImageView.
                original.recycle();
            }
        },callback);
    }
    private static String hash(String text) throws Exception {
        byte[] bytes=MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8));
        StringBuilder result=new StringBuilder();for(byte b:bytes)result.append(String.format(Locale.ROOT,"%02x",b));return result.toString();
    }
    private void trimDisk() {
        File[] files=imageDir.listFiles((dir,name)->!name.endsWith(".tmp"));if(files==null)return;
        Arrays.sort(files,Comparator.comparingLong(File::lastModified));long total=0;for(File f:files)total+=f.length();
        for(File f:files)if(total>20*1024*1024){long length=f.length();if(f.delete())total-=length;}
    }
    void close(){synchronized(waiting){closed=true;metadata.shutdownNow();images.shutdownNow();waiting.clear();bitmaps.evictAll();main.removeCallbacksAndMessages(null);}}
}
