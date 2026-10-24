package com.akai.livingtv;

import android.app.*;
import android.content.*;
import android.os.*;

/** Keeps only an explicit in-flight TV task alive; never starts a task on boot or restart. */
public final class TvTaskService extends Service {
    static final String START="com.akai.livingtv.TASK_START",CANCEL="com.akai.livingtv.TASK_CANCEL";
    private static final int NOTIFICATION_ID=72;
    private static TvTaskService instance;
    private String sessionId="";
    @Override public void onCreate(){super.onCreate();instance=this;NotificationManager manager=(NotificationManager)getSystemService(NOTIFICATION_SERVICE);manager.createNotificationChannel(new NotificationChannel("meadow_tv_tasks","Meadow 電視任務",NotificationManager.IMPORTANCE_LOW));}
    @Override public int onStartCommand(Intent intent,int flags,int startId){
        String id=intent==null?"":intent.getStringExtra("sessionId");if(id==null)id="";
        if(intent!=null&&CANCEL.equals(intent.getAction())){TvToolSession session=TvToolSession.find(id);if(session!=null)session.cancel();else if(sessionId.equals(id))stopSelf();return START_NOT_STICKY;}
        sessionId=id;startForeground(NOTIFICATION_ID,notification());TvToolSession session=TvToolSession.find(id);
        if(session==null){stopSelf(startId);return START_NOT_STICKY;}session.begin();return START_NOT_STICKY;
    }
    static void finish(String id){TvTaskService service=instance;if(service!=null&&service.sessionId.equals(id)){service.stopForeground(true);service.stopSelf();}}
    private Notification notification(){
        PendingIntent cancel=PendingIntent.getService(this,73,new Intent(this,TvTaskService.class).setAction(CANCEL).putExtra("sessionId",sessionId),PendingIntent.FLAG_UPDATE_CURRENT|PendingIntent.FLAG_IMMUTABLE);
        PendingIntent open=PendingIntent.getActivity(this,74,new Intent(this,MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK|Intent.FLAG_ACTIVITY_CLEAR_TOP).putExtra("fromShortcut",true).putExtra("action","assistant"),PendingIntent.FLAG_UPDATE_CURRENT|PendingIntent.FLAG_IMMUTABLE);
        return new Notification.Builder(this,"meadow_tv_tasks").setSmallIcon(R.drawable.ic_settings).setContentTitle("Meadow 正在處理電視任務").setContentText("完成後自動結束；可隨時取消。").setContentIntent(open).setOnlyAlertOnce(true).setOngoing(true).setCategory(Notification.CATEGORY_SERVICE).addAction(new Notification.Action.Builder(null,"取消任務",cancel).build()).build();
    }
    @Override public void onDestroy(){String stopped=sessionId;sessionId="";if(instance==this)instance=null;stopForeground(true);TvToolSession.serviceStopped(stopped);super.onDestroy();}
    @Override public IBinder onBind(Intent intent){return null;}
}
