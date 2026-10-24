package com.akai.livingtv;

import android.accessibilityservice.AccessibilityService;
import android.content.Intent;
import android.util.Log;
import android.view.KeyEvent;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;
import android.os.*;
import android.content.res.Resources;
import java.util.List;

/** Typed TV navigation and on-demand UI observations; Back is always passed through. */
public final class RemoteService extends AccessibilityService {
    private static RemoteService instance;
    private static TvUiSnapshot latestSnapshot;
    private String windowClass="",windowPackage="";
    private final Handler handler=new Handler(Looper.getMainLooper());
    private static final String RECEIVER="io.github.jqssun.airplay";
    private String waiting="Waiting for connection...",zero="0 connected";
    private boolean checkScheduled;
    private long idleSince,lastRedirect;
    private final Runnable checkIdle=()->{checkScheduled=false;inspectReceiverIdle();};
    public static boolean systemAction(int action){return instance!=null&&instance.performGlobalAction(action);}
    static RemoteService connected(){return instance;}
    static TvUiSnapshot readSnapshot(android.content.Context context){return publish(TvUiSnapshot.capture(context,instance));}
    static TvUiSnapshot publish(TvUiSnapshot snapshot){latestSnapshot=snapshot;return snapshot;}
    static TvUiSnapshot previousSnapshot(String id){return latestSnapshot!=null&&latestSnapshot.id.equals(id)?latestSnapshot:null;}
    String observedWindowClass(String activePackage){return activePackage.equals(windowPackage)?windowClass:"";}
    @Override protected void onServiceConnected(){instance=this;try{Resources r=getPackageManager().getResourcesForApplication(RECEIVER);int w=r.getIdentifier("waiting_for_connection","string",RECEIVER),z=r.getIdentifier("connected_count","string",RECEIVER);if(w!=0)waiting=r.getString(w);if(z!=0)zero=r.getString(z,0);}catch(Exception ignored){}Log.i("LivingRemote","Prime shortcut and AirPlay idle return bound");}
    @Override public void onDestroy(){handler.removeCallbacks(checkIdle);if(instance==this){instance=null;latestSnapshot=null;}super.onDestroy();}
    @Override protected boolean onKeyEvent(KeyEvent event){
        int code=event.getKeyCode();
        boolean meadow=code==KeyEvent.KEYCODE_SEARCH||code==KeyEvent.KEYCODE_VOICE_ASSIST||code==KeyEvent.KEYCODE_ASSIST;
        if(code!=191&&code!=KeyEvent.KEYCODE_HOME&&code!=KeyEvent.KEYCODE_MENU&&code!=KeyEvent.KEYCODE_TV_INPUT&&!meadow)return false;
        if(event.getAction()==KeyEvent.ACTION_UP&&!event.isCanceled()){
            if(meadow){startActivity(new Intent(this,MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK|Intent.FLAG_ACTIVITY_CLEAR_TOP).putExtra("fromShortcut",true).putExtra("action","assistant"));return true;}
            if(code!=191){
                Intent home=new Intent(this,MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK|Intent.FLAG_ACTIVITY_CLEAR_TOP);
                if(code==KeyEvent.KEYCODE_HOME)home.setAction(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME);
                else home.putExtra("fromShortcut",true).putExtra("action","inputs");
                startActivity(home);return true;
            }
            Intent intent=getPackageManager().getLeanbackLaunchIntentForPackage("com.google.android.youtube.tv");
            if(intent!=null)startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            boolean hardware=event.getDeviceId()>0;
            if(hardware)getSharedPreferences("living",MODE_PRIVATE).edit().putLong("primeHardwareSeen",System.currentTimeMillis()).apply();
            Log.i("LivingRemote","Prime -> YouTube, hardware="+hardware+", device="+event.getDeviceId());
        }
        return true;
    }
    @Override public void onAccessibilityEvent(AccessibilityEvent event){
        // Event metadata only. Full trees are read on explicit tool requests, never
        // continuously as an app animates or a video plays.
        if(event.getEventType()==AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED){windowClass=TvUiSnapshot.clean(event.getClassName());windowPackage=TvUiSnapshot.clean(event.getPackageName());}
        if(RECEIVER.contentEquals(event.getPackageName()==null?"":event.getPackageName())&&!checkScheduled){checkScheduled=true;handler.postDelayed(checkIdle,650);}
    }
    private int idleText(AccessibilityNodeInfo node,int depth,int[] remaining){
        if(node==null||depth>18||remaining[0]--<=0)return 0;int mask=0;CharSequence value=node.getText();if(value!=null){if(waiting.contentEquals(value))mask|=1;if(zero.contentEquals(value))mask|=2;}
        for(int i=0;i<node.getChildCount()&&remaining[0]>0;i++){AccessibilityNodeInfo child=node.getChild(i);if(child!=null)try{mask|=idleText(child,depth+1,remaining);}finally{child.recycle();}if(mask==3)break;}return mask;
    }
    private void inspectReceiverIdle(){
        AccessibilityNodeInfo root=getRootInActiveWindow();if(root==null)return;
        boolean idle=false;try{if(RECEIVER.contentEquals(root.getPackageName()==null?"":root.getPackageName()))idle=idleText(root,0,new int[]{256})==3;}finally{root.recycle();}
        long now=SystemClock.uptimeMillis();if(!idle){idleSince=0;return;}if(idleSince==0){idleSince=now;checkScheduled=true;handler.postDelayed(checkIdle,1000);return;}
        if(now-idleSince<900||now-lastRedirect<4000)return;lastRedirect=now;idleSince=0;
        Intent modern=new Intent(this,MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK|Intent.FLAG_ACTIVITY_CLEAR_TOP).putExtra("fromShortcut",true).putExtra("action","airplay");startActivity(modern);Log.i("LivingRemote","Idle AirPlay receiver returned to Meadow");
    }
    @Override public void onInterrupt(){}
}
