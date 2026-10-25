package com.akai.livingtv;

import org.junit.Test;
import static org.junit.Assert.*;

public class WeatherScenesTest {
    private WeatherScenes.Scene scene(int code,boolean day){return WeatherScenes.scene(new WeatherRepository.Reading("台北","22°",code,day));}
    @Test public void weatherCategoriesAndDaylightSelectRelevantScenes(){
        assertEquals(WeatherScenes.Scene.SUNNY,scene(0,true));assertEquals(WeatherScenes.Scene.SUNNY,scene(1,true));assertEquals(WeatherScenes.Scene.NIGHT,scene(0,false));
        for(int code:new int[]{2,3})assertEquals(WeatherScenes.Scene.CLOUDY,scene(code,true));
        for(int code:new int[]{51,53,55,56,57,61,63,65,66,67,80,81,82})assertEquals(WeatherScenes.Scene.RAIN,scene(code,true));
        for(int code:new int[]{45,48})assertEquals(WeatherScenes.Scene.FOG,scene(code,true));
        for(int code:new int[]{95,96,97,99})assertEquals(WeatherScenes.Scene.STORM,scene(code,true));
        for(int code:new int[]{71,73,75,77,85,86})assertEquals(WeatherScenes.Scene.SNOW,scene(code,true));
    }
    @Test public void nighttimeDoesNotHideDangerousOrWetWeather(){
        assertEquals(WeatherScenes.Scene.RAIN,scene(65,false));assertEquals(WeatherScenes.Scene.STORM,scene(99,false));assertEquals(WeatherScenes.Scene.FOG,scene(45,false));assertEquals(WeatherScenes.Scene.SNOW,scene(75,false));
    }
    @Test public void missingOrUnknownWeatherDoesNotPretendToBeSunnyOrCloudy(){
        assertEquals(WeatherScenes.Scene.UNKNOWN,WeatherScenes.scene(null));assertEquals(WeatherScenes.Scene.UNKNOWN,scene(-1,true));assertEquals(WeatherScenes.Scene.UNKNOWN,scene(100,false));
    }
}
