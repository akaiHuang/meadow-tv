package com.akai.livingtv;

import android.content.*;
import android.content.pm.*;
import java.util.*;

/** Installed TV launchers only. Four pinned services stay first; no maintenance utilities. */
final class AppCatalog {
    static final String[] PINNED={"com.google.android.youtube.tv","com.netflix.ninja","com.wbd.stream","com.apple.atve.androidtv.appletv"};
    static final String[] PINNED_NAMES={"YouTube","Netflix","HBO Max","Apple TV"};
    static final class App {final String pkg,name;App(String p,String n){pkg=p;name=n;}}
    static List<App> load(Context context){
        List<App> result=new ArrayList<>();Set<String> seen=new HashSet<>();
        for(int i=0;i<PINNED.length;i++){result.add(new App(PINNED[i],PINNED_NAMES[i]));seen.add(PINNED[i]);}
        result.add(new App("meadow.podcasts","Meadow tvOS"));
        seen.addAll(Arrays.asList("com.akai.livingtv","com.gathin.tvhome","io.github.jqssun.airplay"));
        PackageManager pm=context.getPackageManager();List<App> extras=new ArrayList<>();
        for(ResolveInfo r:pm.queryIntentActivities(new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LEANBACK_LAUNCHER),0)){
            String pkg=r.activityInfo.packageName;if(seen.contains(pkg)||(r.activityInfo.applicationInfo.flags&ApplicationInfo.FLAG_SYSTEM)!=0)continue;
            seen.add(pkg);extras.add(new App(pkg,r.loadLabel(pm).toString()));
        }
        extras.sort(Comparator.comparingInt((App a)->a.pkg.equals("com.spotify.tv.android")?0:1).thenComparing(a->a.name,java.text.Collator.getInstance(Locale.TRADITIONAL_CHINESE)));
        result.addAll(extras);return result;
    }
}
