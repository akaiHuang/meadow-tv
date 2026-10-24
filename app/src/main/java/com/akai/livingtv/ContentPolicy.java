package com.akai.livingtv;

import java.net.URI;
import java.util.Locale;

/** Public metadata is descriptive, never proof of an account entitlement. */
public final class ContentPolicy {
    private ContentPolicy() {}
    public static String provider(String channel) {
        String name = channel == null ? "" : channel.toLowerCase(Locale.ROOT).trim();
        if (name.equals("netflix")) return "netflix";
        if (name.equals("hbo") || name.equals("hbo max") || name.equals("max")) return "hbo";
        if (name.equals("apple tv") || name.equals("apple tv+")) return "apple";
        return "";
    }
    public static boolean officialLink(String provider, String link) {
        try {
            URI uri = new URI(link);
            if (!"https".equalsIgnoreCase(uri.getScheme()) || uri.getRawUserInfo() != null || uri.getPort() != -1) return false;
            String host = uri.getHost();
            if (host == null) return false;
            host = host.toLowerCase(Locale.ROOT);
            if (provider.equals("netflix")) return host.equals("www.netflix.com") || host.equals("netflix.com");
            if (provider.equals("apple")) return host.equals("tv.apple.com");
            if (provider.equals("hbo")) return host.equals("www.hbomax.com") || host.equals("play.hbomax.com") || host.equals("www.max.com") || host.equals("play.max.com");
        } catch (Exception ignored) {}
        return false;
    }
    public static boolean artworkLink(String link) {
        try {
            URI uri = new URI(link);
            return "https".equals(uri.getScheme()) && ("static.tvmaze.com".equals(uri.getHost())||(uri.getHost()!=null&&(uri.getHost().endsWith(".mzstatic.com")||uri.getHost().endsWith(".nflximg.net")||uri.getHost().endsWith(".nflxso.net")||uri.getHost().equals("beam-images.warnermediacdn.com")||uri.getHost().equals("ccp-lh.googleusercontent.com")))) && uri.getRawUserInfo() == null && uri.getPort() == -1;
        } catch (Exception e) { return false; }
    }
    public static String appPackage(String provider) {
        switch (provider) {
            case "netflix": return "com.netflix.ninja";
            case "hbo": return "com.wbd.stream";
            case "apple": return "com.apple.atve.androidtv.appletv";
            default: return "com.google.android.youtube.tv";
        }
    }
}
