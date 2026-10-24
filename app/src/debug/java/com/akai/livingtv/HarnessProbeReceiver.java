package com.akai.livingtv;

import android.content.*;
import android.util.AtomicFile;
import java.io.*;
import java.nio.charset.StandardCharsets;
import org.json.*;

/** DUMP-protected, debug-build-only acceptance probe. Never packaged in release. */
public final class HarnessProbeReceiver extends BroadcastReceiver {
    @Override public void onReceive(Context context,Intent intent){
        if(!"com.akai.livingtv.HARNESS_PROBE".equals(intent.getAction()))return;
        String id=intent.getStringExtra("probeId");if(id==null||!id.matches("[a-zA-Z0-9_-]{1,60}"))return;
        Context app=context.getApplicationContext();
        try{
            if("task".equals(intent.getStringExtra("mode"))){
                String text=intent.getStringExtra("text");if(text==null||text.length()>1800)return;
                // The explicit foreground launch below mirrors an interactive request.
                app.startActivity(new Intent(app,MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK|Intent.FLAG_ACTIVITY_CLEAR_TOP).putExtra("fromShortcut",true));
                String probe=id;new android.os.Handler(android.os.Looper.getMainLooper()).postDelayed(()->TvToolSession.start(app,text,new JSONArray(),new JSONArray(),null,reply->write(app,probe,TvUiSnapshot.jsonObject("ok",reply.ok,"text",reply.text))),500);
            }else{
                String json=intent.getStringExtra("command");if(json==null||json.length()>4000)return;
                String probe=id;PendingResult pending=goAsync();
                TvCommandDispatcher.execute(app,new JSONObject(json),result->{write(app,probe,result);pending.finish();});
            }
        }catch(Exception error){write(app,id,TvUiSnapshot.jsonObject("ok",false,"errorCode","probe_failed"));}
    }
    private static void write(Context context,String id,JSONObject value){
        FileOutputStream out=null;AtomicFile file=new AtomicFile(new File(context.getFilesDir(),"probe-"+id+".json"));
        try{out=file.startWrite();out.write(value.toString().getBytes(StandardCharsets.UTF_8));file.finishWrite(out);}catch(IOException error){if(out!=null)file.failWrite(out);}
    }
}
