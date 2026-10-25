package com.akai.livingtv;

import android.content.Context;
import android.os.Looper;
import org.json.*;
import org.junit.*;
import org.junit.runner.RunWith;
import org.robolectric.*;
import org.robolectric.annotation.*;
import java.io.*;
import java.lang.reflect.Field;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;
import static org.junit.Assert.*;
import static org.robolectric.Shadows.shadowOf;

@RunWith(RobolectricTestRunner.class) @Config(sdk=28) @LooperMode(LooperMode.Mode.PAUSED)
public class PodcastRepositoryTest {
    private static final long NOW=1792843200000L;
    private static final String SHOW="show:apple:4242",FEED="https://feeds.example.org/custom.xml";
    private Context context;private ManualExecutor work;
    @Before public void setup()throws Exception {
        context=RuntimeEnvironment.getApplication();work=new ManualExecutor();reset(PodcastRepository.class,null);reset(MediaQueue.class,null);context.getSharedPreferences("podcast_preferences",0).edit().clear().commit();
        for(String name:new String[]{"podcast-shows.json","podcast-library.json","media-queue.json"})for(String suffix:new String[]{"",".bak",".new"})remove(new File(context.getFilesDir(),name+suffix));
    }
    @After public void cleanup()throws Exception {reset(PodcastRepository.class,null);reset(MediaQueue.class,null);}
    private static void reset(Class<?> type,Object value)throws Exception{Field field=type.getDeclaredField("instance");field.setAccessible(true);field.set(null,value);}
    private static void remove(File file){if(file.isDirectory()){File[] children=file.listFiles();if(children!=null)for(File child:children)remove(child);}file.delete();}
    private void settle(){shadowOf(Looper.getMainLooper()).idle();}
    private PodcastRepository repository(PodcastRepository.Transport transport){return new PodcastRepository(context,transport,work,()->NOW);}
    private static byte[] bytes(String text){return text.getBytes(StandardCharsets.UTF_8);}
    private static String directory()throws Exception{return new JSONObject().put("results",new JSONArray().put(new JSONObject().put("collectionId",4242).put("collectionName","自訂節目").put("artistName","自訂出版者").put("feedUrl",FEED).put("artworkUrl600","https://images.example.org/show.jpg").put("collectionViewUrl","https://podcasts.apple.com/tw/podcast/id4242"))).toString();}
    private static String rss(){return "<?xml version=\"1.0\"?><rss version=\"2.0\"><channel><title>自訂節目</title><item><title>第一集 A &amp; B</title><guid>episode-one</guid><pubDate>Sat, 24 Oct 2026 12:00:00 GMT</pubDate><enclosure url=\"https://audio.example.org/one.mp3\" type=\"audio/mpeg\"/></item><item><title>第二集</title><guid>episode-two</guid><enclosure url=\"https://audio.example.org/two.mp3\"/></item></channel></rss>";}
    private PodcastRepository.Transport normal(){return (url,max,prefix)->{if(url.startsWith("https://itunes.apple.com/search?"))return bytes(directory());if(url.equals(FEED))return bytes(rss());if(url.startsWith("https://itunes.apple.com/lookup?"))return bytes("{\"results\":[]}");throw new AssertionError("Unexpected fixture request: "+url);};}
    private PodcastShow discover(PodcastRepository repository){AtomicReference<PodcastRepository.Result<PodcastShow>> result=new AtomicReference<>();repository.searchShowsResult("自訂節目",result::set);work.runNext();settle();assertTrue(result.get().errorCode,result.get().ok());assertEquals(1,result.get().items.size());return result.get().items.get(0);}
    private List<MediaItem> episodes(PodcastRepository repository){AtomicReference<PodcastRepository.Result<MediaItem>> result=new AtomicReference<>();repository.episodesResult(SHOW,result::set);work.runNext();settle();assertTrue(result.get().errorCode,result.get().ok());return result.get().items;}

    @Test public void directoryDiscoveryIsNotSeedRestrictedAndDoesNotDownloadEveryFeed()throws Exception {
        List<String> requests=new ArrayList<>();PodcastRepository repository=repository((url,max,prefix)->{requests.add(url);assertTrue(url.startsWith("https://itunes.apple.com/search?"));return bytes(directory());});
        assertEquals(4,repository.recommendations().size());assertTrue(repository.followedShows().isEmpty());PodcastShow show=discover(repository);
        assertEquals(SHOW,show.showId);assertEquals(show.id,show.showId);assertFalse(show.followed);assertEquals(1,requests.size());assertTrue(requests.get(0).contains("limit=20"));assertFalse(show.metadata().has("feed"));assertFalse(show.metadata().has("directoryId"));
    }
    @Test public void followedShowAndDiscoveredIdSurviveRestartWithoutNetwork()throws Exception {
        PodcastRepository first=repository(normal());discover(first);assertTrue(first.follow(SHOW));assertEquals("",first.lastError());
        PodcastRepository second=repository((url,max,prefix)->{throw new AssertionError("Reload must be local");});assertEquals(1,second.followedShows().size());assertEquals(SHOW,second.followedShows().get(0).showId);assertEquals(NOW,second.followedShows().get(0).followedAt);assertTrue(second.followedShows().get(0).followed);
        assertTrue(second.unfollow(SHOW));PodcastRepository third=repository(normal());assertTrue(third.followedShows().isEmpty());assertNotNull(third.show(SHOW));
    }
    @Test public void unknownIdsAndFailedAtomicStorageDoNotReportSuccessfulFollow()throws Exception {
        PodcastRepository repository=repository(normal());assertFalse(repository.follow("show:apple:999"));assertEquals("not_found",repository.lastError());discover(repository);
        File target=new File(context.getFilesDir(),"podcast-shows.json");assertTrue(target.delete());assertTrue(target.mkdir());assertTrue(new File(target,"blocks-replacement").createNewFile());
        assertFalse(repository.follow(SHOW));assertEquals("storage_failed",repository.lastError());assertFalse(repository.show(SHOW).followed);assertTrue(repository.followedShows().isEmpty());
    }
    @Test public void episodesKeepLegacyIdsAndRetainEntityTextAndShowIdentity()throws Exception {
        PodcastRepository repository=repository(normal());discover(repository);List<MediaItem> found=episodes(repository);assertEquals(2,found.size());MediaItem first=found.get(0);
        assertEquals("第一集 A & B",first.title);assertEquals(SHOW,first.showId);assertEquals(legacyId(FEED+"|episode-one"),first.id);assertEquals(first.id,repository.find(first.id).id);assertEquals(SHOW,first.metadata().getString("showId"));assertFalse(first.metadata().has("audio"));
        assertTrue(repository.follow(SHOW));assertTrue(repository.unfollow(SHOW));PodcastRepository reloaded=repository(normal());assertEquals(first.id,reloaded.find(first.id).id);assertEquals(SHOW,reloaded.find(first.id).showId);
    }
    @Test public void episodeCacheAvoidsRepeatedNetworkAndFollowsRemainIndependent()throws Exception {
        PodcastRepository repository=repository(normal());discover(repository);List<MediaItem> found=episodes(repository);AtomicReference<PodcastRepository.Result<MediaItem>> cached=new AtomicReference<>();repository.episodesResult(SHOW,cached::set);settle();assertTrue(work.pending.isEmpty());assertTrue(cached.get().ok());assertEquals(found.get(0).id,cached.get().items.get(0).id);assertTrue(repository.followedShows().isEmpty());
    }
    @Test public void timeoutIsNotReportedAsAnEmptySuccessfulSearch()throws Exception {
        PodcastRepository repository=repository((url,max,prefix)->{throw new SocketTimeoutException("fixture");});AtomicReference<PodcastRepository.Result<PodcastShow>> result=new AtomicReference<>();repository.searchShowsResult("任何節目",result::set);work.runNext();settle();assertFalse(result.get().ok());assertEquals("timeout",result.get().errorCode);assertTrue(result.get().items.isEmpty());
        PodcastRepository empty=repository((url,max,prefix)->bytes("{\"results\":[]}"));empty.searchShowsResult("沒有結果",result::set);work.runNext();settle();assertTrue(result.get().ok());assertTrue(result.get().items.isEmpty());
    }
    @Test public void missingShowIsRejectedWithoutAnyNetworkRequest(){
        PodcastRepository repository=repository((url,max,prefix)->{throw new AssertionError("Unknown ID cannot fetch");});AtomicReference<PodcastRepository.Result<MediaItem>> result=new AtomicReference<>();repository.episodesResult("https://example.org/invented.xml",result::set);settle();assertEquals("not_found",result.get().errorCode);assertTrue(work.pending.isEmpty());
    }
    @Test public void expiredEpisodeCacheSurvivesTimeoutWithAnExplicitStaleError()throws Exception {
        PodcastRepository first=repository(normal());discover(first);String oldId=episodes(first).get(0).id;
        PodcastRepository offline=new PodcastRepository(context,(url,max,prefix)->{throw new SocketTimeoutException("fixture");},work,()->NOW+TimeUnit.HOURS.toMillis(7));
        AtomicReference<PodcastRepository.Result<MediaItem>> result=new AtomicReference<>();offline.episodesResult(SHOW,result::set);work.runNext();settle();
        assertFalse(result.get().ok());assertEquals("timeout",result.get().errorCode);assertTrue(result.get().stale);assertEquals(oldId,result.get().items.get(0).id);
    }
    @Test public void aFeedWithOnlyPrivateEnclosuresHasNoPlayableEpisodes()throws Exception {
        PodcastRepository repository=repository((url,max,prefix)->{if(url.contains("/search?"))return bytes(directory());if(url.equals(FEED))return bytes("<rss><channel><item><title>Private source</title><guid>private</guid><enclosure url=\"https://192.168.1.3/audio.mp3\"/></item></channel></rss>");return bytes("{\"results\":[]}");});
        discover(repository);AtomicReference<PodcastRepository.Result<MediaItem>> result=new AtomicReference<>();repository.episodesResult(SHOW,result::set);work.runNext();settle();assertFalse(result.get().ok());assertEquals("no_playable_episodes",result.get().errorCode);assertTrue(result.get().items.isEmpty());assertTrue(repository.items().isEmpty());
    }
    @Test public void dtdOrPrivateEnclosuresNeverBecomePlayableEpisodes()throws Exception {
        PodcastRepository repository=repository((url,max,prefix)->{
            if(url.contains("/search?"))return bytes(directory());if(url.equals(FEED))return bytes("<!DOCTYPE rss [<!ENTITY secret SYSTEM 'file:///etc/passwd'>]><rss><channel><item><title>&secret;</title><guid>x</guid><enclosure url=\"https://audio.example.org/x.mp3\"/></item></channel></rss>");return bytes("{\"results\":[]}");
        });discover(repository);AtomicReference<PodcastRepository.Result<MediaItem>> result=new AtomicReference<>();repository.episodesResult(SHOW,result::set);work.runNext();settle();assertFalse(result.get().ok());assertEquals("feed_unavailable",result.get().errorCode);assertTrue(result.get().items.isEmpty());assertTrue(repository.items().isEmpty());
        for(String url:new String[]{"http://audio.example.org/a.mp3","https://127.0.0.1/a.mp3","https://192.168.50.31/a","https://tv.local/a","https://u:p@example.org/a","https://example.org:8443/a"})assertFalse(url,PodcastRepository.safe(url,false));
    }
    @Test public void defaultsUseFollowedShowsRatherThanFetchingThreeSeeds()throws Exception {
        List<String> urls=new ArrayList<>();PodcastRepository.Transport fixture=normal();PodcastRepository repository=repository((url,max,prefix)->{urls.add(url);return fixture.read(url,max,prefix);});discover(repository);assertTrue(repository.follow(SHOW));AtomicReference<List<MediaItem>> result=new AtomicReference<>();repository.defaults(result::set);work.runNext();settle();assertEquals(2,result.get().size());for(MediaItem item:result.get())assertEquals(SHOW,item.showId);assertEquals(2,urls.size());assertEquals(FEED,urls.get(1));
    }
    @Test public void legacyEpisodeCacheMigratesWithoutChangingIdsOrClaimingFollows()throws Exception {
        MediaItem legacy=new MediaItem();legacy.id="pod:legacy-existing-id";legacy.title="既有單集";legacy.show="台灣通勤第一品牌";legacy.audio="https://audio.example.org/legacy.mp3";
        JSONObject item=legacy.json();item.remove("showId");write("podcast-library.json",new JSONObject().put("at",NOW).put("defaultsAt",NOW).put("items",new JSONArray().put(item)).toString());
        PodcastRepository repository=repository((url,max,prefix)->{throw new AssertionError("Fresh old cache must not fetch");});assertEquals(legacy.id,repository.items().get(0).id);assertEquals("show:apple:1510554676",repository.items().get(0).showId);assertTrue(repository.followedShows().isEmpty());AtomicReference<List<MediaItem>> result=new AtomicReference<>();repository.defaults(result::set);assertEquals(1,result.get().size());assertTrue(work.pending.isEmpty());
    }
    @Test public void controllerSearchFollowEpisodesAndAddUseReturnedIdsAndUnfollowKeepsQueue()throws Exception {
        PodcastRepository repository=repository(normal());reset(PodcastRepository.class,repository);AtomicReference<JSONObject> receipt=new AtomicReference<>();
        QueueController.execute(context,new JSONObject().put("operation","search_shows").put("query","自訂節目"),receipt::set);work.runNext();settle();assertTrue(receipt.get().getBoolean("ok"));assertEquals(SHOW,receipt.get().getJSONArray("shows").getJSONObject(0).getString("showId"));
        QueueController.execute(context,new JSONObject().put("operation","follow_show").put("showId",SHOW),receipt::set);assertTrue(receipt.get().getBoolean("persisted"));
        QueueController.execute(context,new JSONObject().put("operation","episodes").put("showId",SHOW),receipt::set);work.runNext();settle();String itemId=receipt.get().getJSONArray("results").getJSONObject(0).getString("id");assertFalse(receipt.get().toString().contains("audio.example.org"));
        QueueController.execute(context,new JSONObject().put("operation","add").put("itemId",itemId),receipt::set);assertTrue(receipt.get().getBoolean("ok"));
        QueueController.execute(context,new JSONObject().put("operation","unfollow_show").put("showId",SHOW),receipt::set);assertTrue(receipt.get().getBoolean("ok"));assertEquals(itemId,MediaQueue.get(context).items().get(0).id);assertEquals("stopped",MediaQueue.get(context).state);
        QueueController.execute(context,new JSONObject().put("operation","follow_show").put("showId","show:apple:unknown"),receipt::set);assertFalse(receipt.get().getBoolean("ok"));assertEquals("not_found",receipt.get().getString("errorCode"));assertFalse(receipt.get().getBoolean("persisted"));
    }
    @Test public void recommendationsUseOfficialGenresFollowsAndSuccessfulPlaybackLocally()throws Exception {
        JSONArray entries=new JSONArray();
        for(int i=1;i<=3;i++)entries.put(new JSONObject().put("collectionId",5000+i).put("collectionName","Fixture show "+i).put("artistName","Publisher "+i).put("feedUrl","https://feeds.example.org/"+i+".xml").put("genres",new JSONArray().put(i==3?"History":"Music").put("Podcast")));
        PodcastRepository repository=repository((url,max,prefix)->bytes(new JSONObject().put("results",entries).toString()));
        AtomicReference<PodcastRepository.Result<PodcastShow>> found=new AtomicReference<>();repository.searchShowsResult("more shows",found::set);work.runNext();settle();assertEquals(3,found.get().items.size());
        assertTrue(repository.recommendations().stream().anyMatch(show->show.showId.equals("show:apple:1477164549")));
        assertEquals(7,repository.recommendations().size());assertTrue(repository.follow("show:apple:5001"));
        List<PodcastShow> ranked=repository.recommendations();assertEquals("show:apple:5001",ranked.get(0).showId);assertEquals("show:apple:5002",ranked.get(1).showId);
        assertEquals(Arrays.asList("Music","Podcast"),repository.show("show:apple:5002").genres);
        assertTrue(repository.unfollow("show:apple:5001"));assertNotEquals("show:apple:5001",repository.recommendations().get(0).showId);
        MediaItem item=new MediaItem();item.id="pod:history-episode";item.showId="show:apple:5003";item.show="Fixture show 3";
        assertTrue(repository.recordSuccessfulPlayback(item));assertFalse("Repeated preparation/retry is deduplicated",repository.recordSuccessfulPlayback(item));assertEquals(1,repository.successfulPlayCount(item.showId));assertEquals(item.showId,repository.recommendations().get(0).showId);
        PodcastRepository restarted=repository((url,max,prefix)->{throw new AssertionError("Ranking must stay local");});assertEquals(item.showId,restarted.recommendations().get(0).showId);assertEquals(1,restarted.successfulPlayCount(item.showId));
        assertTrue(restarted.setPersonalizationEnabled(false));assertEquals(4,restarted.recommendations().size());item.id="pod:another-history-episode";assertFalse(restarted.recordSuccessfulPlayback(item));assertEquals(1,restarted.successfulPlayCount(item.showId));
        assertTrue(restarted.setPersonalizationEnabled(true));assertTrue(restarted.follow("show:apple:5001"));MediaQueue queue=MediaQueue.get(context);queue.add(item);int before=queue.items().size();
        assertTrue(restarted.clearPlaybackPreferences());assertEquals(0,restarted.successfulPlayCount(item.showId));assertTrue(restarted.show("show:apple:5001").followed);assertEquals(before,queue.items().size());assertEquals("stopped",queue.state);
    }
    @Test public void genericPodcastGenreDoesNotCreateAPreferenceOrRegisterUnknownShows()throws Exception {
        PodcastRepository repository=repository(normal());discover(repository);MediaItem unknown=new MediaItem();unknown.id="pod:unknown";unknown.showId="show:apple:missing";assertFalse(repository.recordSuccessfulPlayback(unknown));
        assertEquals(0,repository.successfulPlayCount(unknown.showId));assertFalse(repository.recommendationSummary().isEmpty());
        PodcastShow original=repository.show(SHOW);PodcastShow followed=original.followed(true,NOW);assertEquals(original.genres,PodcastShow.parse(followed.json()).genres);
    }
    @Test public void openingDefaultsRefreshesAtMostFourPreferredShows()throws Exception {
        JSONArray entries=new JSONArray();for(int i=0;i<8;i++)entries.put(new JSONObject().put("collectionId",6000+i).put("collectionName","Followed "+i).put("feedUrl","https://feeds.example.org/followed-"+i+".xml"));List<String> requests=new ArrayList<>();
        PodcastRepository repository=repository((url,max,prefix)->{requests.add(url);return url.contains("/search?")?bytes(new JSONObject().put("results",entries).toString()):bytes(rss());});AtomicReference<PodcastRepository.Result<PodcastShow>> result=new AtomicReference<>();repository.searchShowsResult("many",result::set);work.runNext();settle();for(PodcastShow show:result.get().items)assertTrue(repository.follow(show.showId));
        requests.clear();AtomicReference<List<MediaItem>> episodes=new AtomicReference<>();repository.defaults(episodes::set);work.runNext();settle();assertEquals(4,requests.size());assertEquals(8,episodes.get().size());assertEquals(8,repository.followedShows().size());
    }
    @Test public void olderSeedMetadataGainsGenresWithoutLosingTheUsersFollow()throws Exception {
        PodcastShow old=new PodcastShow("show:apple:1510554676","台灣通勤第一品牌","","https://anchor.fm/s/1ea77470/podcast/rss","","",1510554676L,true,NOW);
        write("podcast-shows.json",new JSONObject().put("shows",new JSONArray().put(old.json())).toString());
        PodcastRepository repository=repository((url,max,prefix)->{throw new AssertionError("Metadata migration must be local");});PodcastShow migrated=repository.show(old.showId);
        assertTrue(migrated.followed);assertEquals(NOW,migrated.followedAt);assertFalse(migrated.author.isEmpty());assertTrue(migrated.genres.contains("社會與文化"));
    }
    private void write(String name,String data)throws IOException{try(FileOutputStream out=context.openFileOutput(name,0)){out.write(bytes(data));}}
    private static String legacyId(String value)throws Exception{byte[] digest=MessageDigest.getInstance("SHA-256").digest(bytes(value));StringBuilder id=new StringBuilder("pod:");for(int n=0;n<12;n++)id.append(String.format(Locale.ROOT,"%02x",digest[n]));return id.toString();}
    private static final class ManualExecutor extends AbstractExecutorService {
        final List<Runnable> pending=new ArrayList<>();boolean shutdown;
        @Override public void execute(Runnable command){if(shutdown)throw new RejectedExecutionException();pending.add(command);}
        void runNext(){pending.remove(0).run();}
        @Override public void shutdown(){shutdown=true;}
        @Override public List<Runnable> shutdownNow(){shutdown=true;List<Runnable> copy=new ArrayList<>(pending);pending.clear();return copy;}
        @Override public boolean isShutdown(){return shutdown;}@Override public boolean isTerminated(){return shutdown&&pending.isEmpty();}@Override public boolean awaitTermination(long timeout,TimeUnit unit){return isTerminated();}
    }
}
