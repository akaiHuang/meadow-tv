package com.akai.livingtv;
import android.app.AlertDialog;import android.content.Context;import android.graphics.*;import android.graphics.drawable.ColorDrawable;import android.os.Looper;import android.view.*;import android.widget.*;
import java.lang.reflect.Field;import java.time.*;import org.json.*;import org.junit.*;import org.junit.runner.RunWith;import org.robolectric.*;import org.robolectric.android.controller.ActivityController;import org.robolectric.annotation.*;
import static org.junit.Assert.*;import static org.robolectric.Shadows.shadowOf;
/** Cached, deterministic native weather flow. No external network or media. */
@RunWith(RobolectricTestRunner.class) @Config(sdk=28,qualifiers="w960dp-h540dp-xhdpi") @GraphicsMode(GraphicsMode.Mode.NATIVE)
public class WeatherLayoutTest {
    ActivityController<MainActivity> controller;WeatherDialog panel;AlertDialog dialog;
    static JSONObject forecast(boolean missingHigh)throws Exception{
        JSONArray dates=new JSONArray(),codes=new JSONArray(),high=new JSONArray(),low=new JSONArray();LocalDate today=LocalDate.now(ZoneId.of("Asia/Taipei"));int[] conditions={61,3,0,2,80,95,45};
        for(int i=0;i<7;i++){dates.put(today.plusDays(i).toString());codes.put(conditions[i]);high.put(i==0&&missingHigh?JSONObject.NULL:24+i);low.put(20+i);}
        return new JSONObject().put("current",new JSONObject().put("temperature_2m",22).put("weather_code",61).put("is_day",1)).put("daily",new JSONObject().put("time",dates).put("weather_code",codes).put("temperature_2m_max",high).put("temperature_2m_min",low));
    }
    static void seedForecasts(Context context)throws Exception{
        android.content.SharedPreferences.Editor p=context.getSharedPreferences("living",0).edit().putBoolean("reduceMotion",true);JSONObject data=forecast(false);long now=System.currentTimeMillis();
        for(int i=0;i<WeatherRepository.CITIES.length;i++)p.putLong("weather"+i+"At",now).putString("weather"+i,data.getJSONObject("current").toString()).putLong("weatherForecast"+i+"At",now).putString("weatherForecast"+i,data.toString());p.commit();
    }
    @Before public void setup()throws Exception{Context c=RuntimeEnvironment.getApplication();c.getSharedPreferences("living",0).edit().clear().commit();seedForecasts(c);controller=Robolectric.buildActivity(MainActivity.class).setup();layout(controller.get().getWindow().getDecorView(),1920,1080);panel=new WeatherDialog(controller.get(),new WeatherRepository.Reading("台北","22°  有雨",61,true));panel.open();dialog=(AlertDialog)field("dialog");settle();}
    @After public void close(){if(dialog!=null)dialog.dismiss();controller.pause().stop().destroy();}
    Object field(String name)throws Exception{Field f=WeatherDialog.class.getDeclaredField(name);f.setAccessible(true);return f.get(panel);}
    void layout(View v,int w,int h){v.measure(View.MeasureSpec.makeMeasureSpec(w,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(h,View.MeasureSpec.EXACTLY));v.layout(0,0,w,h);}
    void settle()throws Exception{for(int i=0;i<4;i++){Thread.sleep(40);shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(250));}WindowManager.LayoutParams b=dialog.getWindow().getAttributes();layout(dialog.getWindow().getDecorView(),b.width,b.height);shadowOf(Looper.getMainLooper()).idle();}
    @Test public void weeklyHasSevenDaysInsideCompactPanelAndTransparentFullScreenHost()throws Exception{
        View weekly=(View)field("weeklyPanel");LinearLayout days=(LinearLayout)field("daysRow");assertEquals(1920,dialog.getWindow().getAttributes().width);assertEquals(1080,dialog.getWindow().getAttributes().height);assertEquals(288,weekly.getLeft());assertEquals(162,weekly.getTop());assertEquals(1344,weekly.getWidth());assertEquals(756,weekly.getHeight());
        assertTrue(dialog.getWindow().getDecorView().getBackground() instanceof ColorDrawable);assertEquals(Color.TRANSPARENT,((ColorDrawable)dialog.getWindow().getDecorView().getBackground()).getColor());assertFalse(dialog.getWindow().getDecorView().getClipToOutline());Outline outline=new Outline();android.graphics.drawable.Drawable background=weekly.getBackground();Rect previous=new Rect(background.getBounds());background.setBounds(0,0,weekly.getWidth(),weekly.getHeight());background.getOutline(outline);background.setBounds(previous);assertEquals(Glass.PANEL_RADIUS,outline.getRadius(),1f);
        assertEquals(7,days.getChildCount());assertEquals("22°",((TextView)field("temperature")).getText().toString());View first=days.getChildAt(0);assertEquals("24°",((TextView)first.findViewWithTag("weatherMax")).getText().toString());assertEquals("20°",((TextView)first.findViewWithTag("weatherMin")).getText().toString());
        for(int i=0;i<7;i++){View day=days.getChildAt(i);assertTrue(day.getRight()<=days.getWidth());TextView hi=day.findViewWithTag("weatherMax"),lo=day.findViewWithTag("weatherMin");assertNotNull(hi.getLayout());assertTrue(hi.getLayout().getLineBottom(hi.getLayout().getLineCount()-1)<=hi.getHeight());assertTrue(hi.getBottom()<=lo.getTop());}
    }
    @Test public void browsingCitiesSpansScreenAndBackDoesNotCommit()throws Exception{
        ((View)field("otherCities")).performClick();settle();assertTrue((Boolean)field("pickerOpen"));FrameLayout[] cities=(FrameLayout[])field("cities");assertEquals(9,cities.length);assertTrue(cities[8].requestFocusFromTouch());settle();assertEquals(0,controller.get().getSharedPreferences("living",0).getInt("weatherCity",0));
        HorizontalScrollView rail=(HorizontalScrollView)cities[0].getParent().getParent();assertEquals(0,rail.getLeft());assertEquals(1920,rail.getWidth());assertTrue(rail.getScrollX()>0);View picker=(View)field("pickerPanel");assertTrue(picker.getBackground()==null||picker.getBackground() instanceof ColorDrawable&&((ColorDrawable)picker.getBackground()).getColor()==Color.TRANSPARENT);
        dialog.onBackPressed();settle();assertTrue(dialog.isShowing());assertFalse((Boolean)field("pickerOpen"));assertEquals("台北",((TextView)field("city")).getText().toString());
    }
    @Test public void confirmedCityUpdatesPreferenceHomepageAndWeek()throws Exception{
        ((View)field("otherCities")).performClick();settle();FrameLayout[] cities=(FrameLayout[])field("cities");assertTrue(cities[7].requestFocusFromTouch());cities[7].performClick();settle();assertEquals(7,controller.get().getSharedPreferences("living",0).getInt("weatherCity",-1));assertFalse((Boolean)field("pickerOpen"));assertEquals("宜蘭",((TextView)field("city")).getText().toString());assertEquals(7,((LinearLayout)field("daysRow")).getChildCount());Field h=MainActivity.class.getDeclaredField("weatherText");h.setAccessible(true);assertTrue(((TextView)h.get(controller.get())).getText().toString().startsWith("宜蘭"));
    }
    @Test public void missingDailyHighStaysUnknownInsteadOfZero()throws Exception{
        dialog.dismiss();controller.get().getSharedPreferences("living",0).edit().putString("weatherForecast0",forecast(true).toString()).putLong("weatherForecast0At",System.currentTimeMillis()).commit();panel=new WeatherDialog(controller.get(),null);panel.open();dialog=(AlertDialog)field("dialog");settle();View first=((LinearLayout)field("daysRow")).getChildAt(0);assertEquals("—",((TextView)first.findViewWithTag("weatherMax")).getText().toString());assertEquals("20°",((TextView)first.findViewWithTag("weatherMin")).getText().toString());
    }
    @Test public void circularCloseIsReachableAndReleasesModal()throws Exception{
        View other=(View)field("otherCities"),close=(View)field("close");assertTrue(other.requestFocusFromTouch());assertSame(close,other.focusSearch(View.FOCUS_RIGHT));assertTrue(close.requestFocusFromTouch());assertEquals(close.getWidth(),close.getHeight());assertTrue(close.performClick());shadowOf(Looper.getMainLooper()).idle();assertFalse(dialog.isShowing());Field f=MainActivity.class.getDeclaredField("openDialogs");f.setAccessible(true);assertEquals(0,f.getInt(controller.get()));
    }
    private WeatherScenes.Scene photoScene(View photo)throws Exception{Field scene=photo.getClass().getDeclaredField("scene");scene.setAccessible(true);return (WeatherScenes.Scene)scene.get(photo);}
    private Bitmap photoBitmap(View photo)throws Exception{Field bitmap=photo.getClass().getDeclaredField("bitmap");bitmap.setAccessible(true);return (Bitmap)bitmap.get(photo);}
    private String photoCity(View photo)throws Exception{Field city=photo.getClass().getDeclaredField("cityName");city.setAccessible(true);return (String)city.get(photo);}
    private void awaitPhoto(View photo)throws Exception{for(int attempt=0;attempt<8&&photoBitmap(photo)==null;attempt++)settle();assertNotNull(photoBitmap(photo));}
    @Test public void cityPreviewsUseTheirOwnConditionsAndBrowsingKeepsCurrentCity()throws Exception{
        dialog.dismiss();int[] codes={0,3,61,0,45,95,71,61,0};
        android.content.SharedPreferences.Editor prefs=controller.get().getSharedPreferences("living",0).edit().putInt("weatherCity",0);
        for(int i=0;i<codes.length;i++){JSONObject data=forecast(false);data.getJSONObject("current").put("weather_code",codes[i]).put("is_day",i==3?0:1);prefs.putString("weatherForecast"+i,data.toString()).putLong("weatherForecast"+i+"At",System.currentTimeMillis());}prefs.commit();
        panel=new WeatherDialog(controller.get(),null);panel.open();dialog=(AlertDialog)field("dialog");settle();((View)field("otherCities")).performClick();settle();
        View[] photos=(View[])field("cityPhotos");WeatherScenes.Scene[] expected={WeatherScenes.Scene.SUNNY,WeatherScenes.Scene.CLOUDY,WeatherScenes.Scene.RAIN,WeatherScenes.Scene.NIGHT,WeatherScenes.Scene.FOG,WeatherScenes.Scene.STORM,WeatherScenes.Scene.SNOW,WeatherScenes.Scene.RAIN,WeatherScenes.Scene.SUNNY};
        for(int i=0;i<photos.length;i++)assertEquals("Each city must use its own weather",expected[i],photoScene(photos[i]));
        awaitPhoto(photos[0]);awaitPhoto(photos[2]);Bitmap taipei=photoBitmap(photos[0]);
        assertNotSame("Different cities must not reuse a differently cropped common picture",taipei,photoBitmap(photos[2]));
        assertNull("Off-screen city photos are not decoded eagerly",photoBitmap(photos[8]));
        FrameLayout[] cards=(FrameLayout[])field("cities");cards[8].requestFocusFromTouch();settle();awaitPhoto(photos[8]);
        assertNotSame("Even identical sunshine must retain distinct city landmarks",taipei,photoBitmap(photos[8]));
        assertEquals("花蓮",photoCity(photos[8]));assertNull("Scrolling releases the off-screen photo reference",photoBitmap(photos[0]));
        cards[5].requestFocusFromTouch();settle();assertEquals(0,controller.get().getSharedPreferences("living",0).getInt("weatherCity",-1));
        assertEquals(WeatherScenes.Scene.SUNNY,photoScene((View)field("weeklyPhoto")));
        cards[5].performClick();settle();assertEquals(5,controller.get().getSharedPreferences("living",0).getInt("weatherCity",-1));assertEquals(WeatherScenes.Scene.STORM,photoScene((View)field("weeklyPhoto")));
        assertEquals("高雄",photoCity((View)field("weeklyPhoto")));awaitPhoto((View)field("weeklyPhoto"));
        ((View)field("otherCities")).performClick();settle();assertEquals(WeatherScenes.Scene.SUNNY,photoScene(photos[0]));assertEquals(9,((java.util.Set<?>)field("requestedCities")).size());
    }
    @Test public void changingWeatherTreatsTheSameCityPhotoWithoutReloadingItsLandmark()throws Exception{
        View photo=(View)field("weeklyPhoto");awaitPhoto(photo);Bitmap cityImage=photoBitmap(photo);
        Bitmap rain=Bitmap.createBitmap(photo.getWidth(),photo.getHeight(),Bitmap.Config.ARGB_8888);photo.draw(new Canvas(rain));
        java.lang.reflect.Method update=photo.getClass().getDeclaredMethod("update",String.class,WeatherRepository.Reading.class);update.setAccessible(true);
        update.invoke(photo,"台北",new WeatherRepository.Reading("台北","22°",0,false));settle();assertSame("Weather does not allocate another copy of the landmark",cityImage,photoBitmap(photo));
        Bitmap night=Bitmap.createBitmap(photo.getWidth(),photo.getHeight(),Bitmap.Config.ARGB_8888);photo.draw(new Canvas(night));
        assertEquals(WeatherScenes.Scene.NIGHT,photoScene(photo));assertNotEquals("Day/rain and clear night must change actual rendered pixels",rain.getPixel(photo.getWidth()/2,photo.getHeight()/2),night.getPixel(photo.getWidth()/2,photo.getHeight()/2));rain.recycle();night.recycle();
    }
    @Test public void dismissingCityPickerDropsLateCallbacksAndReleasesArtwork()throws Exception{
        ((View)field("otherCities")).performClick();View[] photos=(View[])field("cityPhotos");dialog.dismiss();int known=((java.util.Map<?,?>)field("cityReadings")).size();
        for(int i=0;i<4;i++){Thread.sleep(40);shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(100));}
        assertEquals(known,((java.util.Map<?,?>)field("cityReadings")).size());assertNull(field("previewWeather"));assertNull(field("scenes"));
        for(View photo:photos)assertNull(photoBitmap(photo));assertEquals(0,controller.get().getSharedPreferences("living",0).getInt("weatherCity",0));
    }

}
