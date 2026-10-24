package com.akai.livingtv;
import android.accessibilityservice.AccessibilityService;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.widget.Toast;
public final class SleepReceiver extends BroadcastReceiver {
    @Override public void onReceive(Context c,Intent intent){
        c.getSharedPreferences("living",Context.MODE_PRIVATE).edit().remove("sleepAt").apply();
        if(!RemoteService.systemAction(AccessibilityService.GLOBAL_ACTION_LOCK_SCREEN))
            Toast.makeText(c,"睡眠時間已到，請按電源鍵進入待機",Toast.LENGTH_LONG).show();
    }
}
