package com.akai.livingtv;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Looper;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.LooperMode;
import java.io.IOException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.AbstractExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import static org.junit.Assert.*;
import static org.robolectric.Shadows.shadowOf;

@RunWith(RobolectricTestRunner.class) @Config(sdk=28) @LooperMode(LooperMode.Mode.PAUSED)
public class WeatherRepositoryTest {
    private static final long NOW=Instant.parse("2026-10-24T12:00:00Z").toEpochMilli();
    private Context context;
    private SharedPreferences prefs;
    private ManualExecutor executor;
    private final List<WeatherRepository> repositories=new ArrayList<>();
    @Before public void setup(){
        context=RuntimeEnvironment.getApplication();prefs=context.getSharedPreferences("living",0);prefs.edit().clear().commit();executor=new ManualExecutor();
    }
    @After public void cleanup(){for(WeatherRepository repository:repositories)repository.close();}
    private WeatherRepository repository(WeatherRepository.Fetcher fetcher){
        WeatherRepository repository=new WeatherRepository(context,fetcher,executor,()->NOW);repositories.add(repository);return repository;
    }
    private void settle(){shadowOf(Looper.getMainLooper()).idle();}
    static JSONObject forecast()throws Exception {
        return new JSONObject("{\"current\":{\"time\":\"2026-10-24T20:00\",\"temperature_2m\":22.2,\"weather_code\":51,\"is_day\":0},\"daily\":{\"time\":[\"2026-10-24\",\"2026-10-25\",\"2026-10-26\",\"2026-10-27\",\"2026-10-28\",\"2026-10-29\",\"2026-10-30\"],\"weather_code\":[95,3,3,51,51,51,51],\"temperature_2m_max\":[26.2,28.5,27,26.3,25.3,29.3,27.8],\"temperature_2m_min\":[22.1,21.9,21.4,21.1,20.7,20.6,21.5]}}");
    }
    private void seedFull(JSONObject payload,long at){prefs.edit().putString(WeatherRepository.forecastKey(0),payload.toString()).putLong(WeatherRepository.forecastKey(0)+"At",at).commit();}

    @Test public void parsesAnImmutableWeekWithoutChangingApiDates()throws Exception {
        WeatherRepository.Reading reading=WeatherRepository.parseForecast("台北",forecast(),NOW,false);
        assertEquals("22°  有雨",reading.text);assertFalse(reading.day);assertEquals(51,reading.code);assertEquals(NOW,reading.updatedAtMillis);assertFalse(reading.stale);
        assertEquals(7,reading.days.size());assertEquals("2026-10-24",reading.days.get(0).date);assertEquals("2026-10-30",reading.days.get(6).date);
        assertEquals(95,reading.days.get(0).code);assertEquals(26.2,reading.days.get(0).max,0);assertEquals(22.1,reading.days.get(0).min,0);
        try{reading.days.clear();fail("Forecast days must be immutable");}catch(UnsupportedOperationException expected){}
        assertTrue(new WeatherRepository.Reading("台北","22°",61,true).days.isEmpty());
    }
    @Test public void missingOrInvalidNumbersNeverBecomeZeroDegrees()throws Exception {
        JSONObject payload=forecast();payload.getJSONObject("current").put("temperature_2m",JSONObject.NULL);
        JSONObject daily=payload.getJSONObject("daily");daily.getJSONArray("temperature_2m_max").put(0,JSONObject.NULL);daily.getJSONArray("temperature_2m_min").put(0,"NaN");daily.getJSONArray("weather_code").put(0,4);
        WeatherRepository.Reading reading=WeatherRepository.parseForecast("台北",payload,NOW,false);
        assertEquals("—",reading.text);assertEquals(-1,reading.code);assertNull(reading.days.get(0).max);assertNull(reading.days.get(0).min);assertEquals(-1,reading.days.get(0).code);
        daily.getJSONArray("temperature_2m_max").put(1,20);daily.getJSONArray("temperature_2m_min").put(1,25);
        reading=WeatherRepository.parseForecast("台北",payload,NOW,false);assertNull(reading.days.get(1).max);assertNull(reading.days.get(1).min);
        assertEquals("暫不可用",WeatherRepository.description(4));
    }
    @Test public void mismatchedArraysOrInvalidDatesDoNotProduceMislabelledDays()throws Exception {
        JSONObject payload=forecast();payload.getJSONObject("daily").put("weather_code",new JSONArray("[0,3]"));
        assertTrue(WeatherRepository.parseForecast("台北",payload,NOW,false).days.isEmpty());
        payload=forecast();payload.getJSONObject("daily").getJSONArray("time").put(1,"2026-02-30");
        assertTrue(WeatherRepository.parseForecast("台北",payload,NOW,false).days.isEmpty());
        payload=forecast();payload.getJSONObject("daily").getJSONArray("time").put(1,"2026-10-24");
        assertTrue(WeatherRepository.parseForecast("台北",payload,NOW,false).days.isEmpty());
    }
    @Test public void freshLegacyCurrentCacheStillFetchesAWeekAndPreservesCompatibility()throws Exception {
        JSONObject payload=forecast();prefs.edit().putString("weather0",payload.getJSONObject("current").toString()).putLong("weather0At",NOW).commit();
        List<WeatherRepository.Reading> readings=new ArrayList<>();repository(city->payload).load(0,readings::add);settle();
        assertEquals(1,executor.pending.size());assertEquals(1,readings.size());assertTrue(readings.get(0).stale);assertTrue(readings.get(0).days.isEmpty());
        executor.runNext();settle();assertEquals(2,readings.size());assertEquals(7,readings.get(1).days.size());assertFalse(readings.get(1).stale);
        assertTrue(prefs.contains(WeatherRepository.forecastKey(0)));assertEquals(NOW,prefs.getLong("weather0At",0));
        assertEquals(22.2,new JSONObject(prefs.getString("weather0","")).getDouble("temperature_2m"),0);
    }
    @Test public void homepageReusesFreshLegacyCurrentWithoutStartingNetwork()throws Exception {
        prefs.edit().putInt("weatherCity",0).putString("weather0",forecast().getJSONObject("current").toString()).putLong("weather0At",NOW).commit();
        AtomicInteger calls=new AtomicInteger();List<WeatherRepository.Reading> readings=new ArrayList<>();
        repository(city->{calls.incrementAndGet();return forecast();}).load(readings::add);settle();
        assertTrue(executor.pending.isEmpty());assertEquals(0,calls.get());assertEquals(1,readings.size());assertFalse(readings.get(0).stale);assertTrue(readings.get(0).days.isEmpty());assertEquals("22°  有雨",readings.get(0).text);
    }
    @Test public void completeFreshCacheAvoidsNetworkButMidnightNeedsNewDays()throws Exception {
        seedFull(forecast(),NOW-1000);AtomicInteger calls=new AtomicInteger();List<WeatherRepository.Reading> readings=new ArrayList<>();
        WeatherRepository repository=repository(city->{calls.incrementAndGet();return forecast();});repository.load(0,readings::add);settle();
        assertTrue(executor.pending.isEmpty());assertEquals(0,calls.get());assertEquals(1,readings.size());assertFalse(readings.get(0).stale);
        JSONObject yesterday=forecast();JSONArray dates=yesterday.getJSONObject("daily").getJSONArray("time");for(int i=0;i<7;i++)dates.put(i,"2026-10-"+(23+i));seedFull(yesterday,NOW-1000);
        repository.load(0,readings::add);settle();assertEquals(1,executor.pending.size());assertTrue(readings.get(1).stale);
    }
    @Test public void sameCityRequestsCoalesceAndSelectedCityIsCapturedBeforeWorkerRuns()throws Exception {
        List<Integer> requested=new ArrayList<>();WeatherRepository repository=repository(city->{requested.add(city);return forecast();});
        List<WeatherRepository.Reading> first=new ArrayList<>(),second=new ArrayList<>(),other=new ArrayList<>();
        prefs.edit().putInt("weatherCity",0).commit();repository.load(first::add);repository.load(0,second::add);
        prefs.edit().putInt("weatherCity",1).commit();repository.load(other::add);assertEquals(2,executor.pending.size());
        executor.runNext();executor.runNext();settle();assertEquals(java.util.Arrays.asList(0,1),requested);assertEquals(1,first.size());assertEquals(1,second.size());assertEquals("台北",first.get(0).city);assertEquals("新北",other.get(0).city);
    }
    @Test public void failedRefreshRetainsHonestStaleWeekAndOriginalTimestamp()throws Exception {
        long at=NOW-TimeUnit.HOURS.toMillis(1);seedFull(forecast(),at);List<WeatherRepository.Reading> readings=new ArrayList<>();
        repository(city->{throw new IOException("offline");}).load(0,readings::add);settle();executor.runNext();settle();
        assertEquals(2,readings.size());for(WeatherRepository.Reading reading:readings){assertTrue(reading.stale);assertEquals(at,reading.updatedAtMillis);assertEquals(7,reading.days.size());}
        assertEquals(at,prefs.getLong(WeatherRepository.forecastKey(0)+"At",0));
    }
    @Test public void malformedResponseWithoutCacheIsUnavailableAndNotPersisted()throws Exception {
        JSONObject payload=forecast();payload.remove("daily");List<WeatherRepository.Reading> readings=new ArrayList<>();
        repository(city->payload).load(0,readings::add);executor.runNext();settle();WeatherRepository.Reading reading=readings.get(0);
        assertEquals("—",reading.text);assertEquals(-1,reading.code);assertTrue(reading.days.isEmpty());assertTrue(reading.stale);assertEquals(0,reading.updatedAtMillis);assertFalse(prefs.contains(WeatherRepository.forecastKey(0)));
    }
    @Test public void closeDuringFetchPreventsWritesAndCallbacks()throws Exception {
        AtomicReference<WeatherRepository> holder=new AtomicReference<>();WeatherRepository repository=repository(city->{holder.get().close();return forecast();});holder.set(repository);
        List<WeatherRepository.Reading> readings=new ArrayList<>();repository.load(0,readings::add);executor.runNext();settle();
        assertTrue(readings.isEmpty());assertFalse(prefs.contains(WeatherRepository.forecastKey(0)));assertFalse(prefs.contains("weather0"));
    }
    @Test public void closeAlsoSuppressesCallbacksAlreadyPostedToMainThread()throws Exception {
        seedFull(forecast(),NOW);List<WeatherRepository.Reading> readings=new ArrayList<>();WeatherRepository repository=repository(city->forecast());
        repository.load(0,readings::add);repository.close();settle();repository.load(0,readings::add);settle();assertTrue(readings.isEmpty());assertTrue(executor.pending.isEmpty());
    }
    @Test public void apiRequestIncludesDailyFieldsAndTaipeiTimeZone(){
        String url=WeatherRepository.forecastUrl(0);assertTrue(url.contains("latitude=25.033&longitude=121.5654"));assertTrue(url.contains("current=temperature_2m,weather_code,is_day"));assertTrue(url.contains("daily=weather_code,temperature_2m_max,temperature_2m_min"));assertTrue(url.contains("timezone=Asia%2FTaipei&forecast_days=7"));
    }
    private static final class ManualExecutor extends AbstractExecutorService {
        final List<Runnable> pending=new ArrayList<>();private boolean shutdown;
        @Override public void execute(Runnable command){if(shutdown)throw new java.util.concurrent.RejectedExecutionException();pending.add(command);}
        void runNext(){pending.remove(0).run();}
        @Override public void shutdown(){shutdown=true;}
        @Override public List<Runnable> shutdownNow(){shutdown=true;List<Runnable> removed=new ArrayList<>(pending);pending.clear();return removed;}
        @Override public boolean isShutdown(){return shutdown;}
        @Override public boolean isTerminated(){return shutdown&&pending.isEmpty();}
        @Override public boolean awaitTermination(long timeout,TimeUnit unit){return isTerminated();}
    }
}
