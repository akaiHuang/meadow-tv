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
    @Test public void everyCityKeepsItsOwnLandmarkRegardlessOfItsWeather(){
        java.util.Set<Integer> resources=new java.util.HashSet<>();
        for(int index=0;index<WeatherRepository.CITIES.length;index++){
            String city=WeatherRepository.CITIES[index];assertEquals(index,WeatherScenes.cityIndex(city));
            int resource=WeatherScenes.resource(city);assertTrue(resource!=0);assertTrue("Each city needs a distinct photograph",resources.add(resource));
        }
        assertEquals(9,resources.size());assertEquals(-1,WeatherScenes.cityIndex(null));assertEquals(0,WeatherScenes.resource("unknown"));
        assertEquals(.5f,WeatherScenes.focalX("台北"),.001f);assertEquals(.85f,WeatherScenes.focalX("新北"),.001f);assertEquals(.35f,WeatherScenes.focalX("花蓮"),.001f);
    }
}
