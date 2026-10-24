package com.akai.livingtv;

import org.json.*;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.*;
import org.robolectric.annotation.Config;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.*;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class) @Config(sdk=28)
public class FeedTaiwanCatalogTest {
    private static final long NOW=Instant.parse("2026-10-25T00:00:00Z").toEpochMilli();
    private FeedRepository.Entry entry(String provider,int id,long at)throws Exception{
        JSONObject item=new JSONObject().put("id",id).put("title","台灣官方節目 "+id).put("provider",provider).put("poster","https://is1-ssl.mzstatic.com/image/poster.jpg").put("backdrop","https://is1-ssl.mzstatic.com/image/hero.jpg").put("region","TW").put("locale","zh-TW").put("verifiedAt",at).put("collection","台灣官方精選").put("date","").put("summary","");
        if(provider.equals("netflix"))item.put("source","https://www.netflix.com/tw/browse/genre/83").put("official","https://www.netflix.com/tw/title/"+(80000000+id)).put("availability","listed_tw");
        else if(provider.equals("apple"))item.put("source","https://tv.apple.com/tw").put("official","https://tv.apple.com/tw/show/test/umc."+id).put("availability","listed_tw");
        else item.put("source","https://www.hbomax.com/tw/zh/series").put("official","https://www.hbomax.com/tw/zh/show/title-"+id).put("availability","offering_active_tw").put("availabilityStarts","2026-10-01T00:00:00Z").put("availabilityEnds","2027-10-01T00:00:00Z");
        return FeedRepository.Entry.parse(item);
    }
    private List<FeedRepository.Entry> group(String provider,int first,int count,long at)throws Exception{List<FeedRepository.Entry> result=new ArrayList<>();for(int n=0;n<count;n++)result.add(entry(provider,first+n,at));return result;}
    private int count(List<FeedRepository.Entry> items,String provider){int result=0;for(FeedRepository.Entry item:items)if(provider.equals(item.provider))result++;return result;}
    @Test public void oldUsSchedulesAndMissingRegionProofAreRejected()throws Exception{
        FeedRepository.Entry item=entry("netflix",1,NOW);item.source="https://www.tvmaze.com/shows/1";assertFalse(FeedRepository.verifiedTaiwan(item,NOW));
        item=entry("netflix",1,NOW);item.region="";assertFalse(FeedRepository.verifiedTaiwan(item,NOW));item.region="TW";item.locale="en-US";assertFalse(FeedRepository.verifiedTaiwan(item,NOW));
        item=entry("netflix",1,NOW);item.official="https://www.netflix.com/title/80000001";assertFalse(FeedRepository.verifiedTaiwan(item,NOW));
        item=entry("apple",1,NOW);item.source="https://tv.apple.com/twisted";assertFalse(FeedRepository.verifiedTaiwan(item,NOW));
        item=entry("hbo",1,NOW);item.source="https://user@www.hbomax.com/tw/zh";assertFalse(FeedRepository.verifiedTaiwan(item,NOW));
    }
    @Test public void hboRequiresAnActivePublishedOfferingWindow()throws Exception{
        FeedRepository.Entry item=entry("hbo",1,NOW);assertTrue(FeedRepository.verifiedTaiwan(item,NOW));
        item.availabilityEnds="2026-10-24T00:00:00Z";assertFalse(FeedRepository.verifiedTaiwan(item,NOW));
        item=entry("hbo",1,NOW);item.availabilityStarts="2026-11-01T00:00:00Z";assertFalse(FeedRepository.verifiedTaiwan(item,NOW));
        item=entry("hbo",1,NOW);item.availability="listed_tw";assertFalse(FeedRepository.verifiedTaiwan(item,NOW));
    }
    @Test public void incompleteRefreshKeepsEachOtherProvidersVerifiedSelection()throws Exception{
        List<FeedRepository.Entry> old=group("hbo",1,20,NOW-1000);old.addAll(group("apple",100,20,NOW-1000));
        List<FeedRepository.Entry> fresh=group("netflix",200,24,NOW);fresh.add(entry("hbo",999,NOW));
        List<FeedRepository.Entry> result=FeedRepository.mergeTaiwanEntries(old,fresh,NOW);
        assertEquals(24,count(result,"netflix"));assertEquals(21,count(result,"hbo"));assertEquals(20,count(result,"apple"));
        assertEquals(40,FeedRepository.mergeTaiwanEntries(old,Collections.emptyList(),NOW).size());
    }
    @Test public void completeNewerProviderReplacesOnlyItsOldSelectionAndOlderCacheCannotWin()throws Exception{
        List<FeedRepository.Entry> bundled=group("netflix",1,24,NOW);List<FeedRepository.Entry> oldCache=group("netflix",100,24,NOW-1000);
        List<FeedRepository.Entry> retained=FeedRepository.mergeTaiwanEntries(bundled,oldCache,NOW);assertEquals(1,retained.get(0).id);
        List<FeedRepository.Entry> refreshed=FeedRepository.mergeTaiwanEntries(oldCache,bundled,NOW);assertEquals(24,refreshed.size());assertEquals(1,refreshed.get(0).id);
    }
    @Test public void metadataRoundTripPreservesEvidenceAndDoesNotInventReleaseDates()throws Exception{
        FeedRepository.Entry before=entry("hbo",1,NOW),after=FeedRepository.Entry.parse(before.json());assertEquals("TW",after.region);assertEquals("zh-TW",after.locale);assertEquals(NOW,after.verifiedAt);assertEquals("offering_active_tw",after.availability);assertEquals(before.availabilityStarts,after.availabilityStarts);assertEquals("",after.date);assertEquals(before.collection,after.collection);
    }
    @Test public void bundledCatalogHasSixteenToTwentyFourVerifiedTwItemsPerPlatform()throws Exception{
        try(InputStream in=RuntimeEnvironment.getApplication().getAssets().open("official-catalog.json")){
            JSONObject catalog=new JSONObject(new String(in.readAllBytes(),StandardCharsets.UTF_8));assertEquals(2,catalog.getInt("schemaVersion"));
            List<FeedRepository.Entry> entries=FeedRepository.decodeTaiwan(catalog.getJSONArray("items"),System.currentTimeMillis());assertEquals(catalog.getJSONArray("items").length(),entries.size());
            for(String provider:new String[]{"netflix","hbo","apple"}){assertTrue(provider,count(entries,provider)>=16);assertTrue(provider,count(entries,provider)<=24);}
            for(FeedRepository.Entry item:entries){assertFalse(item.source.contains("tvmaze"));assertFalse(item.source.contains("tudum"));assertTrue(item.date.isEmpty());}
        }
    }
}
