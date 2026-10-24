package com.akai.livingtv;

import android.content.*;
import java.net.URI;

/** Provisioned by the local installer, guarded by the shell-only DUMP permission. */
public final class BridgeConfigReceiver extends BroadcastReceiver {
    @Override public void onReceive(Context context,Intent intent) {
        try {
            if("com.akai.livingtv.VERIFY_ASSISTANT".equals(intent.getAction())){
                context.getSharedPreferences("living",0).edit().putBoolean(intent.getBooleanExtra("voiceOnly",false)?"voiceDiagnostic":"assistantDiagnostic",true).apply();
                context.startActivity(new Intent(context,MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK|Intent.FLAG_ACTIVITY_CLEAR_TOP).putExtra("fromShortcut",true).putExtra("action","assistant"));setResultCode(1);return;
            }
            String endpoint=intent.getStringExtra("endpoint"),token=intent.getStringExtra("token"),pin=intent.getStringExtra("pin");
            URI uri=new URI(endpoint);
            if(!"https".equals(uri.getScheme())||uri.getUserInfo()!=null||uri.getQuery()!=null||uri.getFragment()!=null||!uri.getPath().isEmpty()||token==null||token.length()<32||pin==null||!pin.matches("[a-f0-9]{64}"))return;
            if(!AssistantClient.privateHost(uri.getHost()))return;
            context.getSharedPreferences("living",0).edit().putString("bridgeEndpoint",endpoint).putString("bridgeToken",token).putString("bridgePin",pin).apply();
            setResultCode(1);
        }catch(Exception ignored){}
    }
}
