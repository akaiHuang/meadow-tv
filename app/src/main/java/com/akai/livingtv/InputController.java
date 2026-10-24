package com.akai.livingtv;

import android.content.*;
import android.media.tv.*;
import java.util.*;

/** Shared by the foreground UI and the optional accessibility shortcut helper. */
final class InputController {
    static List<TvInputInfo> inputs(Context c) {
        List<TvInputInfo> list=new ArrayList<>();
        TvInputManager manager=(TvInputManager)c.getSystemService(Context.TV_INPUT_SERVICE);
        if(manager==null)return list;
        for(TvInputInfo i:manager.getTvInputList()) {
            if(i.isPassthroughInput()&&!i.getServiceInfo().packageName.toLowerCase(Locale.ROOT).contains("factory"))list.add(i);
        }
        list.sort(Comparator.comparing(i->i.loadLabel(c).toString()));return list;
    }
    static boolean open(Context c,TvInputInfo info) {
        try {
            HdmiPreview.releaseActive();
            c.getSharedPreferences("living",0).edit().putString("lastInput",info.getId()).apply();
            c.startActivity(new Intent(Intent.ACTION_VIEW).setDataAndType(TvContract.buildChannelUriForPassthroughInput(info.getId()),"vnd.android.cursor.item/channel").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            return true;
        }catch(Exception e){return false;}
    }
    static boolean openConnectedHdmi(Context c) {
        TvInputManager manager=(TvInputManager)c.getSystemService(Context.TV_INPUT_SERVICE);
        String last=c.getSharedPreferences("living",0).getString("lastInput","");
        String preview=c.getSharedPreferences("living",0).getString("previewInput","");
        List<TvInputInfo> list=inputs(c);
        for(TvInputInfo i:list)if(i.getType()==TvInputInfo.TYPE_HDMI&&i.getId().equals(preview)&&manager.getInputState(i.getId())!=TvInputManager.INPUT_STATE_DISCONNECTED)return open(c,i);
        for(TvInputInfo i:list)if(i.getType()==TvInputInfo.TYPE_HDMI&&i.getId().equals(last)&&manager.getInputState(i.getId())!=TvInputManager.INPUT_STATE_DISCONNECTED)return open(c,i);
        for(TvInputInfo i:list)if(i.getType()==TvInputInfo.TYPE_HDMI&&manager.getInputState(i.getId())==TvInputManager.INPUT_STATE_CONNECTED)return open(c,i);
        for(TvInputInfo i:list)if(i.getType()==TvInputInfo.TYPE_HDMI&&manager.getInputState(i.getId())==TvInputManager.INPUT_STATE_CONNECTED_STANDBY)return open(c,i);
        return false;
    }
}
